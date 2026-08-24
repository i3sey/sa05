// relayd — серверная сторона туннеля. Работает как origin для Yandex Cloud CDN:
// принимает GET-запросы краёв CDN, расшифровывает аплинк, мультиплексирует
// потоки и ходит наружу (dial-out) с этого сервера.
package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	"yctun/internal/chanhttp"
	"yctun/internal/proto"
)

type Config struct {
	Listen       string `json:"listen"`
	KeyFile      string `json:"key_file"`
	PSK          string `json:"psk"` // hex
	Cover        string `json:"cover,omitempty"`
	StreamTTLSec int    `json:"stream_ttl_sec,omitempty"`
	IdleTTLSec   int    `json:"idle_ttl_sec,omitempty"`
}

const defaultCover = `<!doctype html><html><head><meta charset="utf-8"><title>Status</title></head>
<body style="font-family:sans-serif;max-width:640px;margin:3em auto;color:#333">
<h1>Service status</h1><p>All systems operational.</p>
<p style="color:#999;font-size:0.85em">Monitoring endpoint. No user-facing content here.</p>
</body></html>`

func main() {
	configPath := flag.String("config", "/etc/yctun/relayd.json", "путь к конфигу")
	genKey := flag.String("genkey", "", "сгенерировать статический ключ в файл и выйти")
	flag.Parse()

	if *genKey != "" {
		priv, pub, err := proto.GenStaticKey()
		if err != nil {
			log.Fatal(err)
		}
		if err := os.WriteFile(*genKey, []byte(hex.EncodeToString(priv[:])), 0o600); err != nil {
			log.Fatal(err)
		}
		fmt.Printf("ключ записан в %s\n", *genKey)
		fmt.Printf("pubkey:  %s\n", hex.EncodeToString(pub[:]))
		fmt.Printf("pubhash: %s\n", proto.PubHash(pub[:]))
		return
	}

	cfg := Config{Listen: ":8081", KeyFile: "/etc/yctun/relayd.key"}
	data, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatalf("не удалось прочитать конфиг: %v", err)
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		log.Fatalf("плохой конфиг: %v", err)
	}
	if cfg.Listen == "" {
		cfg.Listen = ":8081"
	}
	if cfg.Cover == "" {
		cfg.Cover = defaultCover
	}

	keyHex, err := os.ReadFile(cfg.KeyFile)
	if err != nil {
		log.Fatalf("нет ключа %s: %v (сначала: relayd -genkey %s)", cfg.KeyFile, err, cfg.KeyFile)
	}
	privBytes, err := hex.DecodeString(strings.TrimSpace(string(keyHex)))
	if err != nil || len(privBytes) != 32 {
		log.Fatalf("плохой ключ в %s", cfg.KeyFile)
	}
	var priv [32]byte
	copy(priv[:], privBytes)
	pub, err := proto.PubKey(priv)
	if err != nil {
		log.Fatal(err)
	}
	psk, err := hex.DecodeString(strings.TrimSpace(cfg.PSK))
	if err != nil || len(psk) < 16 {
		log.Fatal("psk должен быть hex-строкой (минимум 16 байт)")
	}

	srv := chanhttp.NewServer(chanhttp.ServerCfg{
		PSK:        psk,
		StaticPriv: priv,
		StaticPub:  pub,
		CoverHTML:  cfg.Cover,
		StreamTTL:  time.Duration(cfg.StreamTTLSec) * time.Second,
		IdleTTL:    time.Duration(cfg.IdleTTLSec) * time.Second,
	})

	log.Printf("relayd: слушаю %s", cfg.Listen)
	log.Printf("relayd: pubhash %s", proto.PubHash(pub[:]))
	s := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv,
		ReadHeaderTimeout: 30 * time.Second,
		IdleTimeout:       130 * time.Second,
	}
	log.Fatal(s.ListenAndServe())
}

var _ = rand.Read
