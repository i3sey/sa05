// relayd is an isolated HTTPS origin for the Yandex CDN, never the VPN :443.
package main

import (
	"crypto/hmac"
	"encoding/hex"
	"encoding/json"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"yctun/internal/chanhttp"
	"yctun/internal/proto"
)

type Config struct {
	Listen     string   `json:"listen"`
	KeyFile    string   `json:"key_file"`
	UsersFile  string   `json:"users_file"`
	CertFile   string   `json:"cert_file"`
	TLSKeyFile string   `json:"tls_key_file"`
	DenyCIDRs  []string `json:"deny_cidrs"`
}

func main() {
	configPath := flag.String("config", "/etc/yctun-cdn/relayd.json", "config path")
	genKey := flag.String("genkey", "", "write new server private key and exit")
	flag.Parse()
	if *genKey != "" {
		priv, pub, err := proto.GenStaticKey()
		if err != nil {
			log.Fatal(err)
		}
		if err = os.WriteFile(*genKey, []byte(hex.EncodeToString(priv[:])), 0600); err != nil {
			log.Fatal(err)
		}
		log.Printf("server_pub: %s", hex.EncodeToString(pub[:]))
		return
	}
	data, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatal(err)
	}
	var cfg Config
	if err = json.Unmarshal(data, &cfg); err != nil {
		log.Fatal(err)
	}
	if cfg.Listen == "" || cfg.KeyFile == "" || cfg.UsersFile == "" || cfg.CertFile == "" || cfg.TLSKeyFile == "" {
		log.Fatal("missing config")
	}
	secret := os.Getenv("CDN_PILOT_ORIGIN_SECRET")
	if len(secret) < 48 {
		log.Fatal("missing CDN origin secret")
	}
	raw, err := os.ReadFile(cfg.KeyFile)
	if err != nil {
		log.Fatal(err)
	}
	key, err := hex.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(key) != 32 {
		log.Fatal("bad static key")
	}
	var priv [32]byte
	copy(priv[:], key)
	pub, err := proto.PubKey(priv)
	if err != nil {
		log.Fatal(err)
	}
	raw, err = os.ReadFile(cfg.UsersFile)
	if err != nil {
		log.Fatal(err)
	}
	var hexUsers map[string]string
	if err = json.Unmarshal(raw, &hexUsers); err != nil {
		log.Fatal(err)
	}
	users := make(map[string][]byte)
	for id, secretHex := range hexUsers {
		if len(id) < 1 || len(id) > 48 || strings.ContainsAny(id, "/ .") {
			log.Fatal("invalid user ID")
		}
		psk, err := hex.DecodeString(secretHex)
		if err != nil || len(psk) != 32 {
			log.Fatal("invalid user PSK")
		}
		users[id] = psk
	}
	if len(users) == 0 {
		log.Fatal("no users")
	}
	if len(cfg.DenyCIDRs) == 0 {
		log.Fatal("deny_cidrs required (include the origin/panel public IP)")
	}
	var denied []*net.IPNet
	for _, cidr := range cfg.DenyCIDRs {
		_, n, err := net.ParseCIDR(cidr)
		if err != nil {
			log.Fatal("invalid deny_cidrs")
		}
		denied = append(denied, n)
	}
	relay := chanhttp.NewServer(chanhttp.ServerCfg{Users: users, StaticPriv: priv, StaticPub: pub, DenyCIDRs: denied})
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The origin is reachable from the public Internet. Only CDN-inserted
		// requests can reach either the probe or the authenticated tunnel handler.
		if !hmac.Equal([]byte(r.Header.Get("X-CDN-Pilot-Secret")), []byte(secret)) {
			http.Error(w, "cdn required", 403)
			return
		}
		if r.URL.Path == "/probe" {
			chanhttp.Probe(w, r)
			return
		}
		relay.ServeHTTP(w, r)
	})
	srv := &http.Server{Addr: cfg.Listen, Handler: handler, ReadHeaderTimeout: 8 * time.Second, ReadTimeout: 25 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 << 10}
	log.Printf("relayd CDN origin listening (pubhash=%s, users=%d)", proto.PubHash(pub[:]), len(users))
	log.Fatal(srv.ListenAndServeTLS(cfg.CertFile, cfg.TLSKeyFile))
}
