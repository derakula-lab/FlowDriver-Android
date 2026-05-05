package androidlib

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/NullLatency/flow-driver/internal/config"
	"github.com/NullLatency/flow-driver/internal/httpclient"
	"github.com/NullLatency/flow-driver/internal/storage"
	"github.com/NullLatency/flow-driver/internal/transport"
	socks5 "github.com/things-go/go-socks5"
	"github.com/things-go/go-socks5/statute"
)

// ─── helpers ────────────────────────────────────────────────────────────────

type oauthClientJSON struct {
	Installed struct {
		ClientID     string   `json:"client_id"`
		ClientSecret string   `json:"client_secret"`
		AuthURI      string   `json:"auth_uri"`
		RedirectURIs []string `json:"redirect_uris"`
	} `json:"installed"`
}

type tokenCache struct {
	RefreshToken string `json:"refresh_token"`
}

// rawResolver جلوی DNS leak رو می‌گیره (عین main.go اصلی)
type rawResolver struct{}

func (rawResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	return ctx, nil, nil
}

func generateSessionID() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// ─── OAuth helpers (برای اندروید — بدون stdin) ──────────────────────────────

// GetOAuthURL کردنشیال رو می‌گیره و لینک لاگین گوگل رو برمی‌گردونه.
// این لینک رو باید با Intent توی مرورگر اندروید باز کنی.
func GetOAuthURL(credentialsJSON string) (string, error) {
	var cr oauthClientJSON
	if err := json.Unmarshal([]byte(credentialsJSON), &cr); err != nil {
		return "", fmt.Errorf("invalid credentials JSON: %w", err)
	}

	redirectURI := "http://localhost"
	if len(cr.Installed.RedirectURIs) > 0 {
		redirectURI = cr.Installed.RedirectURIs[0]
	}

	authURL := fmt.Sprintf(
		"%s?client_id=%s&redirect_uri=%s&response_type=code&scope=https://www.googleapis.com/auth/drive.file&access_type=offline",
		cr.Installed.AuthURI,
		url.QueryEscape(cr.Installed.ClientID),
		url.QueryEscape(redirectURI),
	)
	return authURL, nil
}

// ExchangeOAuthCode کد از redirect URL رو می‌گیره، با گوگل عوض می‌کنه،
// token رو ذخیره می‌کنه و JSON اون رو برمی‌گردونه.
//
// code: می‌تونه خود کد باشه یا کل redirect URL (هر دو قبوله)
// dataDir: مسیر writable اندروید — مثلاً context.getFilesDir().getAbsolutePath()
// transportConfigJSON: اگه domain fronting لازم داری پاسش بده، وگرنه "" بده
func ExchangeOAuthCode(credentialsJSON, code, dataDir, transportConfigJSON string) (string, error) {
	var cr oauthClientJSON
	if err := json.Unmarshal([]byte(credentialsJSON), &cr); err != nil {
		return "", fmt.Errorf("invalid credentials JSON: %w", err)
	}

	// اگه کل URL پاس داده شد، کد رو ازش بکش بیرون
	if strings.HasPrefix(code, "http") {
		if u, err := url.Parse(code); err == nil {
			if q := u.Query().Get("code"); q != "" {
				code = q
			}
		}
	}
	code = strings.TrimSpace(code)
	if code == "" {
		return "", fmt.Errorf("authorization code is empty")
	}

	redirectURI := "http://localhost"
	if len(cr.Installed.RedirectURIs) > 0 {
		redirectURI = cr.Installed.RedirectURIs[0]
	}

	// HTTP client (با یا بدون domain fronting)
	var httpClient *http.Client
	if transportConfigJSON != "" {
		var tc httpclient.TransportConfig
		if err := json.Unmarshal([]byte(transportConfigJSON), &tc); err == nil {
			httpClient = httpclient.NewCustomClient(tc)
		}
	}
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 30 * time.Second}
	}

	// Exchange code → tokens
	v := url.Values{}
	v.Set("grant_type", "authorization_code")
	v.Set("code", code)
	v.Set("client_id", cr.Installed.ClientID)
	v.Set("client_secret", cr.Installed.ClientSecret)
	v.Set("redirect_uri", redirectURI)

	req, err := http.NewRequest("POST", "https://www.googleapis.com/oauth2/v4/token", strings.NewReader(v.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("token request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("token request returned %d: %s", resp.StatusCode, string(body))
	}

	var resData struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&resData); err != nil {
		return "", fmt.Errorf("failed to decode token response: %w", err)
	}
	if resData.RefreshToken == "" {
		return "", fmt.Errorf("no refresh_token received — make sure app is published or user is a test user")
	}

	cache := tokenCache{RefreshToken: resData.RefreshToken}
	tokenBytes, err := json.MarshalIndent(cache, "", "  ")
	if err != nil {
		return "", err
	}

	// ذخیره روی دیسک تا backend.Login بتونه پیداش کنه
	if dataDir != "" {
		credPath := filepath.Join(dataDir, "credentials.json")
		if err := os.WriteFile(credPath+".token", tokenBytes, 0600); err != nil {
			return "", fmt.Errorf("failed to save token file: %w", err)
		}
	}

	return string(tokenBytes), nil
}

// ─── FlowClient ─────────────────────────────────────────────────────────────

// FlowClient شیء اصلی که اندروید باهاش کار می‌کنه
type FlowClient struct {
	cancel     context.CancelFunc
	listener   net.Listener // FIX: نگه داشتن listener تا بتونیم موقع Stop ببندیمش
	listenAddr string
}

// NewClient کلاینت رو می‌سازه و شروع می‌کنه.
//
// credentialsJSON : محتوای credentials.json
// tokenJSON       : خروجی ExchangeOAuthCode (یا "" اگه می‌خوای دوباره auth بشه)
// configJSON      : AppConfig به صورت JSON
// dataDir         : مسیر writable اندروید
func NewClient(credentialsJSON, tokenJSON, configJSON, dataDir string) (*FlowClient, error) {
	// نوشتن فایل‌ها روی دیسک اندروید
	credPath := filepath.Join(dataDir, "credentials.json")
	if err := os.WriteFile(credPath, []byte(credentialsJSON), 0600); err != nil {
		return nil, fmt.Errorf("failed to write credentials: %w", err)
	}
	if tokenJSON != "" {
		if err := os.WriteFile(credPath+".token", []byte(tokenJSON), 0600); err != nil {
			return nil, fmt.Errorf("failed to write token: %w", err)
		}
	}

	var cfg config.AppConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return nil, fmt.Errorf("invalid config JSON: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	// ساخت backend
	customHTTP := httpclient.NewCustomClient(cfg.Transport)
	backend := storage.NewGoogleBackend(customHTTP, credPath, cfg.GoogleFolderID)

	if err := backend.Login(ctx); err != nil {
		cancel()
		return nil, fmt.Errorf("backend login failed: %w", err)
	}

	// Auto folder creation (عین main.go اصلی)
	if cfg.GoogleFolderID == "" {
		folderID, err := backend.FindFolder(ctx, "Flow-Data")
		if err != nil {
			cancel()
			return nil, fmt.Errorf("failed to search for folder: %w", err)
		}
		if folderID == "" {
			folderID, err = backend.CreateFolder(ctx, "Flow-Data")
			if err != nil {
				cancel()
				return nil, fmt.Errorf("failed to create folder: %w", err)
			}
		}
		cfg.GoogleFolderID = folderID
	}

	// راه‌اندازی engine
	cid := cfg.ClientID
	if cid == "" {
		cid = generateSessionID()[:8]
	}
	engine := transport.NewEngine(backend, true, cid)
	if cfg.RefreshRateMs > 0 {
		engine.SetPollRate(cfg.RefreshRateMs)
	}
	if cfg.FlushRateMs > 0 {
		engine.SetFlushRate(cfg.FlushRateMs)
	}
	engine.Start(ctx)

	// آدرس SOCKS5
	listenAddr := cfg.ListenAddr
	if listenAddr == "" {
		listenAddr = "127.0.0.1:1080"
	}

	// FIX: اول listener رو باز کن تا بتونیم موقع Stop ببندیمش
	// با ListenAndServe قدیمی، پورت بعد از Stop آزاد نمیشد
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("failed to listen on %s: %w", listenAddr, err)
	}

	// سرور SOCKS5 (عین main.go اصلی)
	server := socks5.NewServer(
		socks5.WithDial(func(dc context.Context, network, addr string) (net.Conn, error) {
			sessionID := generateSessionID()
			session := transport.NewSession(sessionID)
			session.TargetAddr = addr
			engine.AddSession(session)
			session.EnqueueTx(nil)
			return transport.NewVirtualConn(session, engine), nil
		}),
		socks5.WithAssociateHandle(func(ctx context.Context, w io.Writer, req *socks5.Request) error {
			socks5.SendReply(w, statute.RepCommandNotSupported, nil)
			return fmt.Errorf("UDP not supported")
		}),
		socks5.WithResolver(rawResolver{}),
	)

	// FIX: از Serve(ln) استفاده می‌کنیم نه ListenAndServe
	// وقتی ln.Close() صدا بشه، این goroutine هم تموم میشه
	go func() {
		_ = server.Serve(ln)
	}()

	return &FlowClient{
		cancel:     cancel,
		listener:   ln,
		listenAddr: listenAddr,
	}, nil
}

// Stop کلاینت رو کاملاً خاموش می‌کنه
// هم context رو cancel میکنه (engine متوقف میشه)
// هم listener رو میبنده (پورت آزاد میشه، SOCKS5 server تموم میشه)
func (f *FlowClient) Stop() {
	if f.cancel != nil {
		f.cancel()
	}
	// FIX: بستن listener → پورت آزاد میشه → NewClient پروفایل بعدی میتونه bind کنه
	if f.listener != nil {
		f.listener.Close()
	}
}

// GetListenAddr آدرس SOCKS5 رو برمی‌گردونه (مثلاً "127.0.0.1:1080")
func (f *FlowClient) GetListenAddr() string {
	return f.listenAddr
}