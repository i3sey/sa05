// relayc — клиентская сторона туннеля: SOCKS5 на localhost → GET-туннель
// через Yandex Cloud CDN → relayd на VPS.
package main

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"yctun/internal/chanhttp"
	"yctun/internal/proto"
	"yctun/internal/session"
	"yctun/internal/socks5"
)

type Config struct {
	BaseURL    string   `json:"base_url"`   // https://dom.sa05.eu.cc
	UserID     string   `json:"user_id"`    // independent per-subscription identity
	PSK        string   `json:"psk"`        // hex
	ServerPub  string   `json:"server_pub"` // hex 32 байта
	Listen     string   `json:"listen"`     // 127.0.0.1:1080
	Chunk      int      `json:"chunk,omitempty"`
	Workers    int      `json:"workers,omitempty"`
	PollMs     int      `json:"poll_ms,omitempty"`
	Stream     *bool    `json:"stream,omitempty"`
	Streams    int      `json:"streams,omitempty"`
	PostUplink *bool    `json:"post_uplink,omitempty"`
	DNSServers []string `json:"dns_servers,omitempty"`
}

func main() {
	configPath := flag.String("config", "relayc.json", "путь к конфигу")
	flag.Parse()
	data, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatalf("конфиг: %v", err)
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		log.Fatalf("конфиг: %v", err)
	}
	usePublicDNS(cfg.DNSServers)
	base, err := url.Parse(cfg.BaseURL)
	if err != nil || base.Scheme != "https" || base.Hostname() != "dom.sa05.eu.cc" || base.Port() != "" || base.User != nil || base.RawQuery != "" || base.Fragment != "" || (base.Path != "" && base.Path != "/") {
		log.Fatal("base_url должен быть https://dom.sa05.eu.cc")
	}
	if cfg.UserID == "" || len(cfg.UserID) > 48 || strings.ContainsAny(cfg.UserID, "/ .") {
		log.Fatal("user_id отсутствует или некорректен")
	}
	if cfg.PostUplink != nil && *cfg.PostUplink {
		log.Fatal("CDN принимает только GET: post_uplink должен быть false")
	}
	psk, err := hex.DecodeString(cfg.PSK)
	if err != nil || len(psk) != 32 {
		log.Fatal("psk должен быть hex (мин. 16 байт)")
	}
	serverPub, err := hex.DecodeString(cfg.ServerPub)
	if err != nil || len(serverPub) != 32 {
		log.Fatal("server_pub должен быть hex (32 байта)")
	}

	// эфемерный ключ и sid сессии
	ephPriv, ephPub, err := proto.GenStaticKey()
	if err != nil {
		log.Fatal(err)
	}
	sid := randHex(16)

	// hello: получаем статический pubkey сервера (base64 raw)
	ephText := base64.RawURLEncoding.EncodeToString(ephPub[:])
	ts := fmt.Sprint(time.Now().Unix())
	nonce := randHex(16)
	helloPath := "/s/" + sid + "/hello/" + cfg.UserID + "/" + ephText + "/" + ts + "/" + nonce + "/" + hex.EncodeToString(proto.HelloMAC(psk, cfg.UserID, sid, ephText, ts, nonce))
	gotPubB64, err := httpGet(base, helloPath)
	if err != nil {
		log.Fatal("hello: CDN HTTPS request failed")
	}
	gotPub, err := base64.RawURLEncoding.DecodeString(string(gotPubB64))
	if err != nil || len(gotPub) != 32 {
		log.Fatalf("hello: плохой ответ (%q)", string(gotPubB64))
	}
	if !equalBytes(gotPub, serverPub) {
		log.Fatalf("pubkey сервера не совпал с конфигом! pubhash с сервера: %s",
			proto.PubHash(gotPub))
	}

	c2s, s2c, err := proto.DeriveSessionKeys(ephPriv, [32]byte(serverPub), psk, cfg.UserID, sid)
	if err != nil {
		log.Fatal(err)
	}
	sealer, err := proto.NewSealer(c2s)
	if err != nil {
		log.Fatal(err)
	}
	opener, err := proto.NewOpener(s2c)
	if err != nil {
		log.Fatal(err)
	}

	sess := session.New(nil)

	stream := false
	if cfg.Stream != nil {
		stream = *cfg.Stream
	}
	postUplink := false
	if cfg.PostUplink != nil {
		postUplink = *cfg.PostUplink
	}
	t := chanhttp.NewClient(base, sid, sealer, opener, chanhttp.ClientCfg{
		Chunk:      cfg.Chunk,
		Workers:    cfg.Workers,
		PollMs:     cfg.PollMs,
		Stream:     stream,
		Streams:    cfg.Streams,
		PostUplink: postUplink,
	}, sess.HandleFrame)
	t.OnReconnect = sess.Resend

	// session.Out -> seal -> transport
	go func() {
		for f := range sess.Out() {
			t.Send(sealer.Seal(proto.EncodeMux(f)))
		}
	}()
	go t.Run()

	ctx, cancel := context.WithCancel(context.Background())
	srv := &socks5.Server{
		Listen: cfg.Listen,
		Open: func(c context.Context, addr string) (net.Conn, error) {
			st, err := sess.Open(c, addr)
			if err != nil {
				return nil, err
			}
			return &streamConn{Stream: st}, nil
		},
	}
	log.Printf("relayc: SOCKS5 на %s -> %s (sid=%s)", cfg.Listen, cfg.BaseURL, sid)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		log.Printf("останавливаюсь...")
		cancel()
		t.Stop()
		time.Sleep(300 * time.Millisecond)
		os.Exit(0)
	}()

	if err := srv.Run(ctx); err != nil {
		log.Fatal(err)
	}
}

// streamConn: session.Stream как net.Conn (для SOCKS-сервера).
type streamConn struct {
	*session.Stream
}

func (c *streamConn) LocalAddr() net.Addr              { return fakeAddr("local") }
func (c *streamConn) RemoteAddr() net.Addr             { return fakeAddr("remote") }
func (c *streamConn) SetDeadline(time.Time) error      { return nil }
func (c *streamConn) SetReadDeadline(time.Time) error  { return nil }
func (c *streamConn) SetWriteDeadline(time.Time) error { return nil }

type fakeAddr string

func (f fakeAddr) Network() string { return "tunnel" }
func (f fakeAddr) String() string  { return string(f) }

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// Android (GOOS=android, CGO off) читает resolv.conf → [::1]:53, а netd
// приложениям туда не отвечает. Hello идёт до поднятия SOCKS, поэтому
// резолвим через публичный DNS напрямую — UID клиента исключён из TUN.
// На БС сначала Yandex DNS (77.88.8.8), затем общие фолбэки.
func usePublicDNS(servers []string) {
	if len(servers) == 0 && os.Getenv("GOOS") != "android" && os.Getenv("ANDROID_ROOT") == "" {
		return
	} // desktop uses system resolver
	addresses := make([]string, 0, len(servers)+3)
	for _, ip := range servers {
		if net.ParseIP(ip) != nil {
			addresses = append(addresses, net.JoinHostPort(ip, "53"))
		}
	}
	addresses = append(addresses, "77.88.8.8:53", "8.8.8.8:53", "1.1.1.1:53")
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			var last error
			for _, dns := range addresses {
				c, err := d.DialContext(ctx, "udp", dns)
				if err == nil {
					return c, nil
				}
				last = err
			}
			return nil, last
		},
	}
}

func httpGet(base *url.URL, p string) ([]byte, error) {
	reqURL, hdrPath := chanhttp.TunnelRequestURL(base, p)
	req, err := http.NewRequest(http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, err
	}
	if hdrPath != "" {
		req.Header.Set("X-Yctun-Path", hdrPath)
	}
	client := &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}}, CheckRedirect: chanhttp.RejectRedirect}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("CDN HTTPS request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, &statusErr{resp.StatusCode}
	}
	return io.ReadAll(io.LimitReader(resp.Body, 4096))
}

type statusErr struct{ code int }

func (e *statusErr) Error() string { return "http status " + itoa(e.code) }

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [8]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

func equalBytes(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var x byte
	for i := range a {
		x |= a[i] ^ b[i]
	}
	return x == 0
}
