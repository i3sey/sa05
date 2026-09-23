// Package chanhttp serves the GET-only, uncached CDN origin. Never log URLs:
// encrypted uplink frames are carried in their paths.
package chanhttp

import (
	"context"
	"crypto/hmac"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"yctun/internal/proto"
	"yctun/internal/session"
)

const maxPollBytes = 512 << 10
const maxSessions = 128

type ServerCfg struct {
	Users      map[string][]byte // user ID -> independent 32-byte PSK; remove entry to revoke
	DenyCIDRs  []*net.IPNet      // origin/panel addresses and other protected networks
	StaticPriv [32]byte
	StaticPub  [32]byte
	StreamTTL  time.Duration
	IdleTTL    time.Duration
}
type Server struct {
	cfg   ServerCfg
	mu    sync.Mutex
	sid   map[string]*sidState
	seen  map[string]time.Time
	slots chan struct{}
}
type sidState struct {
	user      string
	opener    *proto.Opener
	sealer    *proto.Sealer
	sess      *session.Session
	mu        sync.Mutex
	pollBuf   [][]byte
	pollBytes int
	lastSeen  time.Time
	wake      chan struct{}
	done      chan struct{}
	once      sync.Once
}

func (st *sidState) close() { st.once.Do(func() { close(st.done); st.sess.Close() }) }
func (st *sidState) signal() {
	select {
	case st.wake <- struct{}{}:
	default:
	}
}

func NewServer(cfg ServerCfg) *Server {
	if cfg.IdleTTL <= 0 {
		cfg.IdleTTL = 5 * time.Minute
	}
	s := &Server{cfg: cfg, sid: make(map[string]*sidState), seen: make(map[string]time.Time), slots: make(chan struct{}, 64)}
	go s.sweeper()
	return s
}
func (s *Server) sweeper() {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for range t.C {
		s.mu.Lock()
		for id, st := range s.sid {
			st.mu.Lock()
			idle := time.Since(st.lastSeen)
			st.mu.Unlock()
			if idle > s.cfg.IdleTTL {
				st.close()
				delete(s.sid, id)
			}
		}
		for id, until := range s.seen {
			if time.Now().After(until) {
				delete(s.seen, id)
			}
		}
		s.mu.Unlock()
	}
}
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store, private, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", 405)
		return
	}
	// Streams are intentionally unsupported: CDN buffering makes them unreliable.
	// Every uplink GET also polls for the downlink, with bounded queues.
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		http.Error(w, "busy", 429)
		return
	}
	if len(r.URL.RawQuery) != 0 || len(r.URL.Path) > 16000 {
		http.Error(w, "bad request", 400)
		return
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/s/"), "/")
	if !strings.HasPrefix(r.URL.Path, "/s/") || len(parts) < 2 || !validHex(parts[0], 32) {
		http.NotFound(w, r)
		return
	}
	sid := parts[0]
	if parts[1] == "hello" {
		s.handleHello(w, sid, parts)
		return
	}
	if len(parts) < 2 || len(parts) > 3 || !validHex(parts[1], 16) {
		http.NotFound(w, r)
		return
	}
	s.handleUplink(w, r, sid, parts)
}
func validHex(v string, n int) bool {
	if len(v) != n {
		return false
	}
	_, err := hex.DecodeString(v)
	return err == nil
}

// /s/<sid>/hello/<user>/<eph base64url>/<unix seconds>/<nonce hex>/<MAC hex>
func (s *Server) handleHello(w http.ResponseWriter, sid string, parts []string) {
	if len(parts) != 7 || !validHex(parts[5], 32) || !validHex(parts[6], 64) || len(parts[3]) != 43 ||
		len(parts[4]) < 10 || len(parts[4]) > 11 {
		http.NotFound(w, nil)
		return
	}
	user, ephText, tsText, nonce := parts[2], parts[3], parts[4], parts[5]
	if len(user) < 1 || len(user) > 48 || strings.ContainsAny(user, "/ .") {
		http.NotFound(w, nil)
		return
	}
	ts, err := strconv.ParseInt(tsText, 10, 64)
	if err != nil || abs(time.Now().Unix()-ts) > 90 {
		http.Error(w, "expired", 403)
		return
	}
	eph, err := base64.RawURLEncoding.DecodeString(ephText)
	if err != nil || len(eph) != 32 {
		http.NotFound(w, nil)
		return
	}
	mac, err := hex.DecodeString(parts[6])
	if err != nil {
		http.NotFound(w, nil)
		return
	}
	psk, ok := s.cfg.Users[user]
	if !ok || !hmac.Equal(mac, proto.HelloMAC(psk, user, sid, ephText, tsText, nonce)) {
		http.Error(w, "forbidden", 403)
		return
	}
	c2s, s2c, err := proto.DeriveSessionKeys(s.cfg.StaticPriv, [32]byte(eph), psk, user, sid)
	if err != nil {
		http.Error(w, "bad key", 400)
		return
	}
	opener, err := proto.NewOpener(c2s)
	if err != nil {
		http.Error(w, "bad key", 400)
		return
	}
	sealer, err := proto.NewSealer(s2c)
	if err != nil {
		http.Error(w, "bad key", 400)
		return
	}
	st := &sidState{user: user, opener: opener, sealer: sealer, lastSeen: time.Now(), wake: make(chan struct{}, 1), done: make(chan struct{})}
	st.sess = session.New(s.dialOut)
	// Commit replay token and session atomically, after authenticating hello.
	s.mu.Lock()
	replayID := user + ":" + sid + ":" + nonce
	if _, exists := s.seen[replayID]; exists {
		s.mu.Unlock()
		st.close()
		http.Error(w, "replay", 403)
		return
	}
	if _, exists := s.sid[sid]; exists || len(s.sid) >= maxSessions {
		s.mu.Unlock()
		st.close()
		http.Error(w, "busy", 429)
		return
	}
	s.seen[replayID] = time.Now().Add(3 * time.Minute)
	s.sid[sid] = st
	s.mu.Unlock()
	go s.dispatchLoop(st)
	w.Header().Set("Content-Type", "text/plain")
	w.Write([]byte(base64.RawURLEncoding.EncodeToString(s.cfg.StaticPub[:])))
}
func abs(n int64) int64 {
	if n < 0 {
		return -n
	}
	return n
}

func (s *Server) dispatchLoop(st *sidState) {
	for {
		select {
		case <-st.done:
			return
		case f := <-st.sess.Out():
			b := st.sealer.Seal(proto.EncodeMux(f))
			for {
				st.mu.Lock()
				if st.pollBytes+len(b) <= maxPollBytes {
					st.pollBuf = append(st.pollBuf, b)
					st.pollBytes += len(b)
					st.mu.Unlock()
					break
				}
				st.mu.Unlock()
				select {
				case <-st.done:
					return
				case <-st.wake:
				}
			}
		}
	}
}
func (s *Server) handleUplink(w http.ResponseWriter, r *http.Request, sid string, parts []string) {
	s.mu.Lock()
	st := s.sid[sid]
	s.mu.Unlock()
	if st == nil {
		http.NotFound(w, r)
		return
	}
	select {
	case <-st.done:
		http.NotFound(w, r)
		return
	default:
	}
	st.mu.Lock()
	st.lastSeen = time.Now()
	st.mu.Unlock()
	if len(parts) == 3 {
		payload, err := base64.RawURLEncoding.DecodeString(parts[2])
		if err != nil || len(payload) > 11000 {
			http.Error(w, "bad request", 400)
			return
		}
		for len(payload) > 0 {
			total, ok := proto.NextFrameLen(payload)
			if !ok || total < 26 {
				http.Error(w, "bad frame", 400)
				return
			}
			if pt, err := st.opener.Open(payload[:total]); err == nil {
				if f, err := proto.DecodeMux(pt); err == nil && f.Type != proto.TypeNop {
					st.sess.HandleFrame(f)
				}
			}
			payload = payload[total:]
		}
	}
	st.mu.Lock()
	buf := st.pollBuf
	st.pollBuf = nil
	st.pollBytes = 0
	st.mu.Unlock()
	st.signal()
	if len(buf) == 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	for _, b := range buf {
		if _, err := w.Write(b); err != nil {
			return
		}
	}
}

// Dial only the resolved public address, not a second DNS lookup (rebinding).
func (s *Server) denied(ip net.IP) bool {
	for _, network := range s.cfg.DenyCIDRs {
		if network.Contains(ip) {
			return true
		}
	}
	for _, cidr := range []string{"100.64.0.0/10", "198.18.0.0/15", "192.0.0.0/24", "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "2001:db8::/32"} {
		_, network, _ := net.ParseCIDR(cidr)
		if network.Contains(ip) {
			return true
		}
	}
	return false
}
func (s *Server) dialOut(addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return nil, fmt.Errorf("invalid port")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	for _, entry := range ips {
		ip := entry.IP
		if ip.IsGlobalUnicast() && !ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() && !ip.IsLinkLocalMulticast() && !ip.IsUnspecified() && !ip.IsMulticast() && !ip.Equal(net.ParseIP("169.254.169.254")) && !s.denied(ip) {
			return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", net.JoinHostPort(ip.String(), port))
		}
	}
	return nil, fmt.Errorf("no public address")
}

// Probe keeps the original CDN route smoke test available without access to /s.
func Probe(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store, private, max-age=0")
	if r.Method != http.MethodGet || r.URL.Path != "/probe" {
		http.NotFound(w, r)
		return
	}
	q := r.URL.Query()
	nonce, client := q.Get("nonce"), q.Get("client")
	if len(q) != 2 || !validHex(nonce, 32) || !validHex(client, 16) {
		http.Error(w, "invalid request", 400)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{"origin": "cdn-pilot-de2", "nonce": nonce, "client": client})
}
