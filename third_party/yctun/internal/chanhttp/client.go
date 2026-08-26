// chanhttp: транспорт туннеля поверх HTTP GET через CDN.
//
// Клиентская сторона:
//   - N воркеров шлют GET /s/<sid>/<nonce>/<b64(sealed frames)> — аплинк;
//     батчат до chunk байт, при простое — пустой heartbeat каждые poll_ms.
//   - стрим-ридер тянет GET /s/<sid>/stream/<nonce> — даунлинк потоком;
//     при обрыве переподключается с backoff и делает session.Resend().
//   - если стрим недоступен — даунлинк едет в телах ответов uplink-запросов.
package chanhttp

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"time"

	"yctun/internal/proto"
)

type ClientCfg struct {
	Chunk      int           // байт на uplink-запрос (после b64 ~ +33%)
	Workers    int           // параллельных uplink-воркеров
	PollMs     int           // интервал heartbeat при простое
	Stream     bool          // использовать стрим-даунлинк
	Streams    int           // число параллельных стримов (умолч. 4)
	PostUplink bool          // аплинк с payload через POST (Functions); пустой poll — GET
	Timeout    time.Duration // таймаут одного GET (умолч. 20s)
}

type Client struct {
	base   *url.URL
	sid    string
	sealer *proto.Sealer
	opener *proto.Opener
	cfg    ClientCfg

	sendCh chan []byte // запечатанные фреймы от session
	onRecv func(proto.Frame)
	// OnReconnect вызывается после обрыва стрим-даунлинка (ретрансмит)
	OnReconnect func()

	hc       *http.Client // аплинк (с таймаутом)
	streamHC *http.Client // стрим (без таймаута)

	stopCh chan struct{}
	doneCh chan struct{}
}

func NewClient(base *url.URL, sid string, sealer *proto.Sealer, opener *proto.Opener, cfg ClientCfg, onRecv func(proto.Frame)) *Client {
	if cfg.Chunk <= 0 {
		cfg.Chunk = 12288
	}
	if cfg.Workers <= 0 {
		cfg.Workers = 6
	}
	if cfg.PollMs <= 0 {
		cfg.PollMs = 400
	}
	if cfg.Streams <= 0 {
		cfg.Streams = 4
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 20 * time.Second
	}
	tr := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		TLSClientConfig:     &tls.Config{},
		ForceAttemptHTTP2:   true,
		DisableCompression:  true,
		MaxIdleConnsPerHost: 64,
		IdleConnTimeout:     60 * time.Second,
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
	}
	return &Client{
		base:     base,
		sid:      sid,
		sealer:   sealer,
		opener:   opener,
		cfg:      cfg,
		hc:       &http.Client{Transport: tr, Timeout: cfg.Timeout + 10*time.Second},
		streamHC: &http.Client{Transport: tr}, // без Timeout: стрим живёт долго
		sendCh:   make(chan []byte, 2048),
		onRecv:   onRecv,
		stopCh:   make(chan struct{}),
		doneCh:   make(chan struct{}),
	}
}

// Send — запечатанный фрейм на отправку (аплинк).
func (c *Client) Send(b []byte) {
	select {
	case c.sendCh <- b:
	case <-c.stopCh:
	}
}

func (c *Client) Run() {
	defer close(c.doneCh)
	if c.cfg.Stream {
		for i := 0; i < c.cfg.Streams; i++ {
			go c.streamLoop()
		}
	}
	for i := 0; i < c.cfg.Workers; i++ {
		go c.workerLoop()
	}
	<-c.stopCh
}

func (c *Client) Stop() {
	select {
	case <-c.stopCh:
	default:
		close(c.stopCh)
	}
	<-c.doneCh
}

func nonceHex() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// workerLoop: батчит фреймы и шлёт GET; ответ — даунлинк-фреймы.
func (c *Client) workerLoop() {
	ua := pickUA()
	for {
		select {
		case <-c.stopCh:
			return
		default:
		}

		var batch [][]byte
		total := 0
		timer := time.NewTimer(2 * time.Millisecond)
	drain:
		for total < c.cfg.Chunk {
			select {
			case b := <-c.sendCh:
				batch = append(batch, b)
				total += len(b)
				if total >= c.cfg.Chunk {
					break drain
				}
			case <-timer.C:
				break drain
			}
		}
		timer.Stop()

		var body []byte
		if len(batch) > 0 {
			body = concatSealed(batch)
		}
		respBody, err := c.roundTrip(ua, body)
		if err != nil {
			// ретраи того же батча
			backoff := 150 * time.Millisecond
			for attempt := 0; attempt < 3 && err != nil; attempt++ {
				select {
				case <-c.stopCh:
					return
				case <-time.After(backoff):
				}
				respBody, err = c.roundTrip(ua, body)
				backoff *= 2
			}
			if err != nil {
				log.Printf("yctun: uplink GET failed: %v", err)
			}
		}
		c.parseFrames(respBody)

		if len(batch) == 0 {
			select {
			case <-c.stopCh:
				return
			case <-time.After(time.Duration(c.cfg.PollMs) * time.Millisecond):
			}
		}
	}
}

func (c *Client) roundTrip(ua string, body []byte) ([]byte, error) {
	nonce := nonceHex()
	p := "/s/" + c.sid + "/" + nonce
	method := http.MethodGet
	var reqBody io.Reader
	if len(body) > 0 {
		if c.cfg.PostUplink {
			method = http.MethodPost
			reqBody = bytes.NewReader(body)
		} else {
			p += "/" + base64.RawURLEncoding.EncodeToString(body)
		}
	}
	reqURL, hdrPath := tunnelRequestURL(c.base, p)
	req, err := http.NewRequest(method, reqURL, reqBody)
	if err != nil {
		return nil, err
	}
	if hdrPath != "" {
		req.Header.Set("X-Yctun-Path", hdrPath)
	}
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "*/*")
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 204 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 1<<20))
}

// streamLoop: стрим-даунлинк с переподключением.
func (c *Client) streamLoop() {
	backoff := 300 * time.Millisecond
	ua := pickUA()
	for {
		select {
		case <-c.stopCh:
			return
		default:
		}
		err := c.streamOnce(ua)
		if err != nil {
			log.Printf("yctun: downlink stream: %v", err)
			if c.OnReconnect != nil {
				c.OnReconnect()
			}
		}
		select {
		case <-c.stopCh:
			return
		case <-time.After(backoff):
		}
		if backoff < 5*time.Second {
			backoff *= 2
		}
	}
}

func (c *Client) streamOnce(ua string) error {
	reqURL, hdrPath := tunnelRequestURL(c.base, "/s/"+c.sid+"/stream/"+nonceHex())
	req, err := http.NewRequest(http.MethodGet, reqURL, nil)
	if err != nil {
		return err
	}
	if hdrPath != "" {
		req.Header.Set("X-Yctun-Path", hdrPath)
	}
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "*/*")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() {
		select {
		case <-c.stopCh:
			cancel()
		case <-ctx.Done():
		}
	}()
	req = req.WithContext(ctx)
	resp, err := c.streamHC.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		io.Copy(io.Discard, io.LimitReader(resp.Body, 512))
		return fmt.Errorf("stream status %d", resp.StatusCode)
	}
	return c.readFrames(resp.Body)
}

// parseFrames — разобрать тело ответа на AEAD-фреймы.
func (c *Client) parseFrames(body []byte) {
	for {
		total, ok := proto.NextFrameLen(body)
		if !ok {
			return
		}
		c.decode(body[:total])
		body = body[total:]
	}
}

func (c *Client) readFrames(r io.Reader) error {
	var buf bytes.Buffer
	tmp := make([]byte, 32<<10)
	for {
		n, err := r.Read(tmp)
		if n > 0 {
			buf.Write(tmp[:n])
			for {
				total, ok := proto.NextFrameLen(buf.Bytes())
				if !ok {
					break
				}
				c.decode(buf.Next(total))
			}
		}
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
	}
}

func (c *Client) decode(frame []byte) {
	pt, err := c.opener.Open(frame)
	if err != nil {
		if err != proto.ErrTooOld {
			log.Printf("yctun: decrypt fail: %v", err)
		}
		return
	}
	f, err := proto.DecodeMux(pt)
	if err != nil {
		log.Printf("yctun: mux decode fail: %v", err)
		return
	}
	if f.Type == proto.TypeNop {
		return
	}
	c.onRecv(f)
}

func concatSealed(batch [][]byte) []byte {
	total := 0
	for _, b := range batch {
		total += len(b)
	}
	out := make([]byte, 0, total)
	for _, b := range batch {
		out = append(out, b...)
	}
	return out
}

var uaList = []string{
	"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
	"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
	"Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0",
}

func pickUA() string {
	return uaList[time.Now().UnixNano()%int64(len(uaList))]
}
