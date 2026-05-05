package transport

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/NullLatency/flow-driver/internal/storage"
)

// Engine manages the local sessions, periodically flushes Tx buffers to files,
// and polls for new Rx files.
type Engine struct {
	backend storage.Backend
	myDir   Direction
	peerDir Direction
	id      string

	sessions  map[string]*Session
	sessionMu sync.RWMutex

	closedSessions   map[string]time.Time
	closedSessionsMu sync.Mutex

	pollTicker  time.Duration
	flushTicker time.Duration

	OnNewSession func(sessionID, targetAddr string, s *Session)

	// CHANGE: increased concurrency 16 → 32 for heavier parallel media loads
	sem chan struct{}

	processed   map[string]bool
	processedMu sync.Mutex

	flushTrigger chan struct{}

	// CHANGE: rxReady signals pollLoop to skip sleep and re-poll immediately
	rxReady chan struct{}
}

func NewEngine(backend storage.Backend, isClient bool, clientID string) *Engine {
	e := &Engine{
		backend:        backend,
		id:             clientID,
		sessions:       make(map[string]*Session),
		closedSessions: make(map[string]time.Time),
		processed:      make(map[string]bool),
		// CHANGE: reduced both tickers to 50ms (was 100ms) for lower baseline latency
		pollTicker:  50 * time.Millisecond,
		flushTicker: 50 * time.Millisecond,
		// buffered channel of 1 — multiple concurrent writes collapse into one flush
		flushTrigger: make(chan struct{}, 1),
		// CHANGE: rxReady channel — poll loop signals itself to skip sleep between batches
		rxReady: make(chan struct{}, 1),
	}
	if isClient {
		e.myDir = DirReq
		e.peerDir = DirRes
	} else {
		e.myDir = DirRes
		e.peerDir = DirReq
	}
	// CHANGE: 32 concurrent upload/download goroutines (was 16)
	e.sem = make(chan struct{}, 32)
	return e
}

// TriggerFlush signals the flush loop to run immediately.
// Safe to call from any goroutine; never blocks.
func (e *Engine) TriggerFlush() {
	select {
	case e.flushTrigger <- struct{}{}:
	default:
	}
}

func (e *Engine) SetRefreshRate(ms int) {
	if ms > 0 {
		e.pollTicker = time.Duration(ms) * time.Millisecond
		if e.flushTicker == 300*time.Millisecond {
			e.flushTicker = time.Duration(ms) * time.Millisecond
		}
	}
}

func (e *Engine) SetPollRate(ms int) {
	if ms > 0 {
		e.pollTicker = time.Duration(ms) * time.Millisecond
	}
}

func (e *Engine) SetFlushRate(ms int) {
	if ms > 0 {
		e.flushTicker = time.Duration(ms) * time.Millisecond
	}
}

func (e *Engine) Start(ctx context.Context) {
	go e.flushLoop(ctx)
	go e.pollLoop(ctx)
	go e.cleanupLoop(ctx)
}

func (e *Engine) GetSession(id string) *Session {
	e.sessionMu.RLock()
	defer e.sessionMu.RUnlock()
	return e.sessions[id]
}

func (e *Engine) AddSession(s *Session) {
	e.sessionMu.Lock()
	defer e.sessionMu.Unlock()
	e.sessions[s.ID] = s
	log.Printf("Engine.AddSession: Added session %s (Total now: %d)", s.ID, len(e.sessions))
}

func (e *Engine) flushLoop(ctx context.Context) {
	ticker := time.NewTicker(e.flushTicker)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			e.flushAll(ctx)
		case <-e.flushTrigger:
			e.flushAll(ctx)
		}
	}
}

func (e *Engine) flushAll(ctx context.Context) {
	e.sessionMu.RLock()
	sessions := make([]*Session, 0, len(e.sessions))
	for _, s := range e.sessions {
		sessions = append(sessions, s)
	}
	e.sessionMu.RUnlock()

	// CHANGE: collect all envelopes first, then upload per-client in parallel goroutines
	type clientBatch struct {
		cid  string
		envs []Envelope
	}

	muxes := make(map[string][]Envelope)
	var closedSessionIDs []string

	for _, s := range sessions {
		s.mu.Lock()

		if time.Since(s.lastActivity) > 5*time.Minute {
			s.closed = true
		}

		shouldSend := len(s.txBuf) > 0 || (s.txSeq == 0 && e.myDir == DirReq) || s.closed

		if !shouldSend {
			s.mu.Unlock()
			continue
		}

		payload := s.txBuf

		// Chunk cap: max 512KB per flush to keep uploads fast
		const maxUploadSize = 2 * 1024 * 1024  // 2MB
		if len(payload) > maxUploadSize {
 		   s.txBuf = payload[maxUploadSize:]
  		  payload = payload[:maxUploadSize]
   		 go e.TriggerFlush()
		} else {
 		   s.txBuf = nil
		}

		s.txCond.Broadcast()

		env := Envelope{
			SessionID:  s.ID,
			Seq:        s.txSeq,
			Payload:    payload,
			Close:      s.closed,
			TargetAddr: s.TargetAddr,
		}

		s.txSeq++
		if s.closed {
			closedSessionIDs = append(closedSessionIDs, s.ID)
		}

		cid := s.ClientID
		if cid == "" && e.myDir == DirReq {
			cid = e.id
		}

		muxes[cid] = append(muxes[cid], env)
		s.mu.Unlock()
	}

	// CHANGE: upload each client-batch concurrently instead of sequentially
	var uploadWg sync.WaitGroup
	for cid, mux := range muxes {
		uploadWg.Add(1)
		go func(cid string, m []Envelope) {
			defer uploadWg.Done()

			e.sem <- struct{}{}
			defer func() { <-e.sem }()

			fnameCID := cid
			if fnameCID == "" {
				fnameCID = "unknown"
			}
			filename := fmt.Sprintf("%s-%s-mux-%d.bin", e.myDir, fnameCID, time.Now().UnixNano())

			// CHANGE: pre-size buffer to avoid repeated allocs during encode
			var buf bytes.Buffer
			buf.Grow(64 * 1024)
			for _, env := range m {
				if err := env.Encode(&buf); err != nil {
					log.Printf("mux encode error: %v", err)
					return
				}
			}
			if err := e.backend.Upload(ctx, filename, &buf); err != nil {
				log.Printf("upload error %s: %v", filename, err)
			}
		}(cid, mux)
	}
	uploadWg.Wait()

	for _, id := range closedSessionIDs {
		e.RemoveSession(id)
	}
}

func (e *Engine) pollLoop(ctx context.Context) {
	currentPollInterval := e.pollTicker
	maxPollInterval := 2 * time.Second
	timer := time.NewTimer(currentPollInterval)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		// CHANGE: also wake up immediately when rxReady is signalled (data just arrived)
		case <-e.rxReady:
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
		case <-timer.C:
		}

		// ZERO-TRAFFIC CLIENT OPTIMIZATION
		if e.myDir == DirReq {
			e.sessionMu.RLock()
			count := len(e.sessions)
			e.sessionMu.RUnlock()
			if count == 0 {
				timer.Reset(currentPollInterval)
				continue
			}
		}

		prefix := string(e.peerDir) + "-"
		if e.myDir == DirReq {
			prefix += e.id + "-mux-"
		} else {
			prefix += ""
		}

		files, err := e.backend.ListQuery(ctx, prefix)
		if err != nil {
			log.Printf("poll list error: %v", err)
			timer.Reset(currentPollInterval)
			continue
		}

		if len(files) == 0 {
			if e.myDir == DirRes {
				e.sessionMu.RLock()
				activeSessions := len(e.sessions)
				e.sessionMu.RUnlock()

				if activeSessions == 0 {
					currentPollInterval += 500 * time.Millisecond
					if currentPollInterval > maxPollInterval {
						currentPollInterval = maxPollInterval
					}
				} else {
					currentPollInterval = e.pollTicker
				}
			}
			timer.Reset(currentPollInterval)
			continue
		}

		// Data found — reset to fastest poll rate
		currentPollInterval = e.pollTicker

		var wg sync.WaitGroup
		// CHANGE: track whether any file was successfully processed this round
		var gotData bool
		var gotDataMu sync.Mutex

		for _, f := range files {
			parts := strings.Split(f, "-")
			if len(parts) >= 3 {
				tsStr := parts[len(parts)-1]
				tsStr = strings.TrimSuffix(tsStr, ".bin")
				ts, _ := strconv.ParseInt(tsStr, 10, 64)
				if ts > 0 && time.Since(time.Unix(0, ts)) > 5*time.Minute {
					e.backend.Delete(ctx, f)
					continue
				}
			}

			e.processedMu.Lock()
			already := e.processed[f]
			if !already {
				e.processed[f] = true
			}
			e.processedMu.Unlock()

			if already {
				continue
			}

			wg.Add(1)
			go func(fname string) {
				defer wg.Done()

				e.sem <- struct{}{}
				defer func() { <-e.sem }()

				rc, err := e.backend.Download(ctx, fname)
				if err != nil {
					if strings.Contains(err.Error(), "404") {
						return
					}
					log.Printf("download error %s: %v", fname, err)
					e.processedMu.Lock()
					delete(e.processed, fname)
					e.processedMu.Unlock()
					return
				}
				defer rc.Close()

				var fileClientID string
				parts := strings.Split(fname, "-")
				if len(parts) >= 4 && parts[2] == "mux" {
					fileClientID = parts[1]
				}

				count := 0
				for {
					var env Envelope
					if err := env.Decode(rc); err != nil {
						if err != io.EOF && err != io.ErrUnexpectedEOF {
							log.Printf("mux decode error %s: %v", fname, err)
						}
						break
					}
					count++

					e.closedSessionsMu.Lock()
					if _, exists := e.closedSessions[env.SessionID]; exists {
						e.closedSessionsMu.Unlock()
						continue
					}
					e.closedSessionsMu.Unlock()

					e.sessionMu.Lock()
					s, exists := e.sessions[env.SessionID]
					if !exists && e.myDir == DirRes && e.OnNewSession != nil {
						s = NewSession(env.SessionID)
						s.ClientID = fileClientID
						e.sessions[env.SessionID] = s
						e.sessionMu.Unlock()
						log.Printf("Engine: Triggering new session %s for Client %s", env.SessionID, fileClientID)
						e.OnNewSession(env.SessionID, env.TargetAddr, s)
					} else {
						e.sessionMu.Unlock()
					}

					if s != nil {
						s.ProcessRx(&env)
					}
				}

				if count > 0 {
					gotDataMu.Lock()
					gotData = true
					gotDataMu.Unlock()
				}

				e.backend.Delete(ctx, fname)
			}(f)
		}

		wg.Wait()

		// CHANGE: if we got real data, signal rxReady to loop again immediately
		// instead of sleeping 50ms. This eliminates the fixed inter-batch delay
		// that was the main source of latency for streaming content.
		if gotData {
			select {
			case e.rxReady <- struct{}{}:
			default:
			}
		}

		timer.Reset(currentPollInterval)
	}
}

func (e *Engine) RemoveSession(id string) {
	e.sessionMu.Lock()
	delete(e.sessions, id)
	e.sessionMu.Unlock()

	e.closedSessionsMu.Lock()
	e.closedSessions[id] = time.Now()
	e.closedSessionsMu.Unlock()
}

func (e *Engine) cleanupLoop(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// Cleanup old tombstones
			e.closedSessionsMu.Lock()
			for id, t := range e.closedSessions {
				if time.Since(t) > 30*time.Second {
					delete(e.closedSessions, id)
				}
			}
			e.closedSessionsMu.Unlock()

			// CHANGE: clear processed map more aggressively (500 instead of 1000)
			// to avoid stale entries blocking re-downloads after transient failures
			e.processedMu.Lock()
			if len(e.processed) > 500 {
				e.processed = make(map[string]bool)
			}
			e.processedMu.Unlock()

			if e.myDir == DirReq {
				e.sessionMu.RLock()
				count := len(e.sessions)
				e.sessionMu.RUnlock()
				if count == 0 {
					continue
				}
			}

			files, _ := e.backend.ListQuery(ctx, string(e.myDir)+"-")
			for _, f := range files {
				parts := strings.Split(f, "-")
				if len(parts) >= 3 {
					tsStr := parts[len(parts)-1]
					tsStr = strings.TrimSuffix(tsStr, ".json")
					tsStr = strings.TrimSuffix(tsStr, ".bin")
					ts, err := strconv.ParseInt(tsStr, 10, 64)
					if err == nil {
						t := time.Unix(0, ts)
						if time.Since(t) > 30*time.Second {
							e.processedMu.Lock()
							alreadyDone := e.processed[f]
							e.processedMu.Unlock()

							if alreadyDone || time.Since(t) > 60*time.Second {
								e.backend.Delete(ctx, f)
							}
						}
					}
				}
			}
		}
	}
}