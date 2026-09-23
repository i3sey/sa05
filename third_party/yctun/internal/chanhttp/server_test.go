package chanhttp

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"yctun/internal/proto"
)

func TestAuthenticatedHelloIsolatedAndBoundToSession(t *testing.T) {
	priv, pub, _ := proto.GenStaticKey()
	a := make([]byte, 32)
	b := make([]byte, 32)
	rand.Read(a)
	rand.Read(b)
	s := NewServer(ServerCfg{StaticPriv: priv, StaticPub: pub, Users: map[string][]byte{"alice": a, "bob": b}})
	hello := func(user, sid string, psk []byte, ts string, nonce string) (int, string) {
		_, eph, _ := proto.GenStaticKey()
		ep := base64.RawURLEncoding.EncodeToString(eph[:])
		mac := hex.EncodeToString(proto.HelloMAC(psk, user, sid, ep, ts, nonce))
		r := httptest.NewRequest("GET", fmt.Sprintf("/s/%s/hello/%s/%s/%s/%s/%s", sid, user, ep, ts, nonce, mac), nil)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		return w.Code, w.Body.String()
	}
	sidA := strings.Repeat("a", 32)
	sidB := strings.Repeat("b", 32)
	ts := fmt.Sprint(time.Now().Unix())
	if status, _ := hello("alice", sidA, b, ts, strings.Repeat("c", 32)); status != 403 {
		t.Fatalf("wrong user PSK: %d", status)
	}
	if status, _ := hello("alice", sidA, a, fmt.Sprint(time.Now().Add(-5*time.Minute).Unix()), strings.Repeat("d", 32)); status != 403 {
		t.Fatalf("expired hello: %d", status)
	}
	if status, body := hello("alice", sidA, a, ts, strings.Repeat("e", 32)); status != 200 || body != base64.RawURLEncoding.EncodeToString(pub[:]) {
		t.Fatalf("alice: %d", status)
	}
	if status, _ := hello("bob", sidB, b, ts, strings.Repeat("f", 32)); status != 200 {
		t.Fatalf("bob: %d", status)
	}
	if status, _ := hello("bob", sidA, b, ts, strings.Repeat("a", 32)); status != 429 {
		t.Fatalf("sid hijack: %d", status)
	}
	for _, sid := range []string{sidA, sidB} {
		r := httptest.NewRequest("GET", "/s/"+sid+"/"+strings.Repeat("a", 16), nil)
		w := httptest.NewRecorder()
		s.ServeHTTP(w, r)
		if w.Code != 204 {
			t.Fatalf("poll %s: %d", sid, w.Code)
		}
	}
	s.mu.Lock()
	for _, st := range s.sid {
		st.close()
	}
	s.mu.Unlock()
}
func TestProbeAndPrivateDialDenied(t *testing.T) {
	w := httptest.NewRecorder()
	Probe(w, httptest.NewRequest("GET", "/probe?nonce="+strings.Repeat("a", 32)+"&client="+strings.Repeat("b", 16), nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"origin":"cdn-pilot-de2"`) {
		t.Fatalf("probe %d %s", w.Code, w.Body.String())
	}
	s := NewServer(ServerCfg{})
	for _, addr := range []string{"127.0.0.1:80", "10.0.0.1:443", "169.254.169.254:80", "[::1]:80"} {
		if c, err := s.dialOut(addr); err == nil {
			c.Close()
			t.Fatalf("private dial allowed: %s", addr)
		}
	}
}
func TestNoPostOrStreaming(t *testing.T) {
	s := NewServer(ServerCfg{})
	for _, p := range []string{"/s/" + strings.Repeat("a", 32) + "/stream/aaaaaaaaaaaaaaaa", "/s/" + strings.Repeat("a", 32) + "/aaaaaaaaaaaaaaaa"} {
		w := httptest.NewRecorder()
		s.ServeHTTP(w, httptest.NewRequest("POST", p, nil))
		if w.Code != 405 {
			t.Fatalf("POST %s = %d", p, w.Code)
		}
	}
}
