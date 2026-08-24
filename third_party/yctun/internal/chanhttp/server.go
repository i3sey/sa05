// Серверная сторона chanhttp: HTTP-обработчик, который CDN дёргает как origin.
// Маршруты:
//
//	GET /s/<sid>/hello/<b64(ephPub)>        — отдать статический pubkey сервера
//	GET /s/<sid>/stream/<nonce>             — стрим-даунлинк (тело течёт)
//	GET /s/<sid>/<nonce>[/<b64(payload)>]   — аплинк; в ответе даунлинк (poll)
//
// Всё прочее — cover-страница. Невалидный AEAD молча отбрасывается.
package chanhttp

import (
	"encoding/base64"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"yctun/internal/proto"
	"yctun/internal/session"
)

type ServerCfg struct {
	PSK        []byte
	StaticPriv [32]byte
	StaticPub  [32]byte
	CoverHTML  string
	StreamTTL  time.Duration // макс. время жизни стрим-ответа
	IdleTTL    time.Duration // снос сессии без активности
}

type Server struct {
	cfg ServerCfg
	mu  sync.Mutex
	sid map[string]*sidState
}

type sidState struct {
	sealer   *proto.Sealer // s2c
	opener   *proto.Opener // c2s
	sess     *session.Session
	streams  []chan []byte // активные стрим-ответы (round-robin)
	streamRR int
	pollBuf  [][]byte // запечатанные фреймы, ждущие poll-ответа
	lastSeen time.Time
	mu       sync.Mutex
}

func NewServer(cfg ServerCfg) *Server {
	if cfg.StreamTTL <= 0 {
		cfg.StreamTTL = 2 * time.Minute
	}
	if cfg.IdleTTL <= 0 {
		cfg.IdleTTL = 15 * time.Minute
	}
	s := &Server{cfg: cfg, sid: map[string]*sidState{}}
	go s.sweeper()
	return s
}

func (s *Server) sweeper() {
	for range time.Tick(1 * time.Minute) {
		s.mu.Lock()
		for id, st := range s.sid {
			st.mu.Lock()
			idle := time.Since(st.lastSeen)
			st.mu.Unlock()
			if idle > s.cfg.IdleTTL {
				st.sess.Close()
				delete(s.sid, id)
			}
		}
		s.mu.Unlock()
	}
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !strings.HasPrefix(r.URL.Path, "/s/") {
		s.serveCover(w)
		return
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/s/"), "/")
	if len(parts) < 2 || len(parts[0]) != 32 {
		s.serveCover(w)
		return
	}
	sid := parts[0]
	action := parts[1]

	switch action {
	case "hello":
		s.handleHello(w, sid, parts)
	case "stream":
		log.Printf("STREAM open sid=%s", sid[:8])
		s.handleStream(w, r, sid)
		log.Printf("STREAM closed sid=%s", sid[:8])
	default:
		s.handleUplink(w, sid, parts)
	}
}

func (s *Server) serveCover(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=300")
	fmt.Fprint(w, s.cfg.CoverHTML)
}

// hello: /s/<sid>/hello/<b64(ephPub)> → b64(staticPub)
func (s *Server) handleHello(w http.ResponseWriter, sid string, parts []string) {
	if len(parts) < 3 {
		s.serveCover(w)
		return
	}
	ephPub, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(ephPub) != 32 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	c2s, s2c, err := proto.DeriveKeys(s.cfg.StaticPriv, [32]byte(ephPub), s.cfg.PSK)
	if err != nil {
		http.Error(w, "internal", http.StatusInternalServerError)
		return
	}
	sealer, err := proto.NewSealer(s2c)
	if err != nil {
		http.Error(w, "internal", http.StatusInternalServerError)
		return
	}
	opener, err := proto.NewOpener(c2s)
	if err != nil {
		http.Error(w, "internal", http.StatusInternalServerError)
		return
	}
	st := &sidState{
		sealer:   sealer,
		opener:   opener,
		sess:     session.New(s.dialOut),
		lastSeen: time.Now(),
	}
	go s.dispatchLoop(st)
	s.mu.Lock()
	if old, ok := s.sid[sid]; ok {
		old.sess.Close()
	}
	s.sid[sid] = st
	s.mu.Unlock()

	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "text/plain")
	w.Write([]byte(base64.RawURLEncoding.EncodeToString(s.cfg.StaticPub[:])))
}

func (s *Server) dialOut(addr string) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", addr, 10*time.Second)
	log.Printf("DIAL %s -> err=%v", addr, err)
	return conn, err
}

// dispatchLoop: единственный потребитель session.Out — раздаёт фреймы
// либо в активный стрим, либо в poll-буфер.
func (s *Server) dispatchLoop(st *sidState) {
	for f := range st.sess.Out() {
		b := st.sealer.Seal(proto.EncodeMux(f))
		st.mu.Lock()
		if len(st.streams) > 0 {
			ch := st.streams[st.streamRR%len(st.streams)]
			st.streamRR++
			select {
			case ch <- b:
			default:
				st.pollBuf = append(st.pollBuf, b)
			}
		} else {
			st.pollBuf = append(st.pollBuf, b)
		}
		st.mu.Unlock()
	}
}

// uplink: /s/<sid>/<nonce>[/<b64(payload)>]
func (s *Server) handleUplink(w http.ResponseWriter, sid string, parts []string) {
	s.mu.Lock()
	st := s.sid[sid]
	s.mu.Unlock()
	if st == nil {
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusNoContent)
		return
	}
	st.mu.Lock()
	st.lastSeen = time.Now()
	st.mu.Unlock()

	if len(parts) >= 3 && parts[2] != "" {
		payload, err := base64.RawURLEncoding.DecodeString(parts[2])
		if err == nil {
			n := 0
			for {
				total, ok := proto.NextFrameLen(payload)
				if !ok {
					break
				}
				if pt, err := st.opener.Open(payload[:total]); err == nil {
					if f, err := proto.DecodeMux(pt); err == nil {
						st.sess.HandleFrame(f)
						n++
					}
				}
				payload = payload[total:]
			}
			if n > 0 {
				log.Printf("UPLINK sid=%s frames=%d", sid[:8], n)
			}
		}
	}

	// ответ: если стрим активен — пусто (стрим везёт даунлинк),
	// иначе — всё накопленное.
	st.mu.Lock()
	streamActive := len(st.streams) > 0
	var buf [][]byte
	if !streamActive {
		buf = st.pollBuf
		st.pollBuf = nil
	}
	st.mu.Unlock()

	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/octet-stream")
	if len(buf) == 0 {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.WriteHeader(http.StatusOK)
	total := 0
	for _, b := range buf {
		if total+len(b) > 1<<20 {
			break
		}
		w.Write(b)
		total += len(b)
	}
}

// stream: /s/<sid>/stream/<nonce> — долгий ответ, тело = поток фреймов.
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request, sid string) {
	s.mu.Lock()
	st := s.sid[sid]
	s.mu.Unlock()
	if st == nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	st.mu.Lock()
	st.lastSeen = time.Now()
	if len(st.streams) >= 8 {
		st.mu.Unlock()
		http.Error(w, "busy", http.StatusTooManyRequests)
		return
	}
	ch := make(chan []byte, 512)
	st.streams = append(st.streams, ch)
	// слить poll-буфер в стрим
	buf := st.pollBuf
	st.pollBuf = nil
	st.mu.Unlock()
	for _, b := range buf {
		ch <- b
	}

	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/octet-stream")
	w.WriteHeader(http.StatusOK)
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "no flush", http.StatusInternalServerError)
		return
	}
	fl.Flush()

	hb := time.NewTicker(2 * time.Second)
	defer hb.Stop()
	flushTick := time.NewTicker(5 * time.Millisecond)
	defer flushTick.Stop()
	deadline := time.After(s.cfg.StreamTTL)
	var pending []byte
	flush := func() {
		if len(pending) == 0 {
			return
		}
		w.Write(pending)
		fl.Flush()
		pending = pending[:0]
	}
	for {
		select {
		case b := <-ch:
			pending = append(pending, b...)
			// долив без блокировки до крупного куска (CDN режет по flush-чанкам)
		drain:
			for len(pending) < 256<<10 {
				select {
				case b2 := <-ch:
					pending = append(pending, b2...)
				default:
					break drain
				}
			}
			if len(pending) >= 256<<10 {
				flush()
			}
		case <-flushTick.C:
			flush()
		case <-hb.C:
			// heartbeat: если idle — паддинг-фрейм, чтобы CDN не резал ответ
			if len(pending) == 0 {
				w.Write(st.sealer.Seal(proto.EncodeMux(proto.Frame{Type: proto.TypeNop})))
				fl.Flush()
			}
		case <-deadline:
			goto done
		case <-r.Context().Done():
			goto done
		}
	}
done:
	flush()
	st.mu.Lock()
	for i, c := range st.streams {
		if c == ch {
			st.streams = append(st.streams[:i], st.streams[i+1:]...)
			break
		}
	}
	st.mu.Unlock()
}
