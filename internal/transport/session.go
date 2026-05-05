package transport

import (
	"log"
	"sync"
	"time"
)

// Direction indicates if a file is req (client to server) or res (server to client)
type Direction string

const (
	DirReq Direction = "req"
	DirRes Direction = "res"
)

type queuedEnvelope struct {
	env        *Envelope
	receivedAt time.Time
}

// Session represents an active proxy connection mapped to files.
type Session struct {
	ID           string
	mu           sync.Mutex
	txBuf        []byte
	txSeq        uint64
	rxSeq        uint64
	rxQueue      map[uint64]*queuedEnvelope
	lastActivity time.Time
	closed       bool
	rxClosed     bool
	TargetAddr   string
	ClientID     string

	txCond *sync.Cond
	RxChan chan []byte

	lastTimeoutCheck time.Time


}

func NewSession(id string) *Session {
	s := &Session{
		ID:           id,
		rxQueue:      make(map[uint64]*queuedEnvelope),
		lastActivity: time.Now(),
		RxChan:           make(chan []byte, 65536),
		lastTimeoutCheck: time.Now(),
	}
	s.txCond = sync.NewCond(&s.mu)
	return s
}

func (s *Session) EnqueueTx(data []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for len(s.txBuf) > 8*1024*1024 && !s.closed {
		s.txCond.Wait()
	}

	s.txBuf = append(s.txBuf, data...)
	s.lastActivity = time.Now()
}

func (s *Session) ClearTx() {
	s.mu.Lock()
	s.txBuf = nil
	s.txCond.Broadcast()
	s.mu.Unlock()
}

func (s *Session) ProcessRx(env *Envelope) {
	s.mu.Lock()

	if s.rxClosed {
		s.mu.Unlock()
		return
	}

	if time.Since(s.lastTimeoutCheck) > 5*time.Second {
		s.lastTimeoutCheck = time.Now()
		for seq, item := range s.rxQueue {
			if time.Since(item.receivedAt) > 60*time.Second {
				log.Printf("Session %s: rxQueue timeout on seq %d — closing", s.ID, seq)
				s.closed = true
				s.rxClosed = true
				close(s.RxChan)
				s.mu.Unlock()
				return
			}
		}
	}

	s.lastActivity = time.Now()

	var toSend [][]byte
	shouldClose := false

	if env.Seq == s.rxSeq {
		if len(env.Payload) > 0 {
			toSend = append(toSend, env.Payload)
		}
		s.rxSeq++

		if env.Close {
			shouldClose = true
		} else {
			for {
				nextItem, ok := s.rxQueue[s.rxSeq]
				if !ok {
					break
				}
				if len(nextItem.env.Payload) > 0 {
					toSend = append(toSend, nextItem.env.Payload)
				}
				delete(s.rxQueue, s.rxSeq)
				s.rxSeq++
				if nextItem.env.Close {
					shouldClose = true
					break
				}
			}
		}

		if shouldClose {
			s.rxClosed = true
			s.closed = true
		}
	} else if env.Seq > s.rxSeq {
		s.rxQueue[env.Seq] = &queuedEnvelope{
			env:        env,
			receivedAt: time.Now(),
		}
	}

	s.mu.Unlock()

    for _, payload := range toSend {
        timer := time.NewTimer(30 * time.Second)
        select {
        case s.RxChan <- payload:
            timer.Stop()
        case <-timer.C:
            log.Printf("Session %s: RxChan send timeout", s.ID)
            s.mu.Lock()
            if !s.rxClosed {
                s.closed = true
                s.rxClosed = true
                close(s.RxChan)
            }
            s.mu.Unlock()
            return
        }
    }

    if shouldClose {
        s.mu.Lock()
        if !s.rxClosed {
            s.rxClosed = true
            close(s.RxChan)
        }
        s.mu.Unlock()
    }
}