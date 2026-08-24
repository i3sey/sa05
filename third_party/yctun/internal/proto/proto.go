// proto: сквозная криптография и wire-формат.
//
// AEAD-фрейм (на проводе): [seq u64 BE][len u16 BE][ciphertext||tag]
// nonce = 8 нулей || seq || 8 нулей; ключи у направлений разные (c2s / s2c),
// поэтому nonce уникален. Внутри ciphertext: [padlen u8][pad][mux-фрейм].
package proto

import (
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
)

var ErrTooOld = errors.New("frame too old (replay window)")

// ---------- ключи ----------

func GenStaticKey() (priv, pub [32]byte, err error) {
	if _, err = rand.Read(priv[:]); err != nil {
		return
	}
	pubB, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return
	}
	copy(pub[:], pubB)
	return
}

func PubKey(priv [32]byte) ([32]byte, error) {
	var pub [32]byte
	b, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return pub, err
	}
	copy(pub[:], b)
	return pub, nil
}

// DeriveKeys: shared = X25519(priv, peerPub); K = HKDF-SHA256(ikm=shared,
// salt=psk, info="yctun-v1-c2s"/"yctun-v1-s2c").
func DeriveKeys(priv, peerPub [32]byte, psk []byte) (c2s, s2c []byte, err error) {
	shared, err := curve25519.X25519(priv[:], peerPub[:])
	if err != nil {
		return nil, nil, err
	}
	c2s, err = hkdfExpand(shared, psk, "yctun-v1-c2s", 32)
	if err != nil {
		return nil, nil, err
	}
	s2c, err = hkdfExpand(shared, psk, "yctun-v1-s2c", 32)
	if err != nil {
		return nil, nil, err
	}
	return c2s, s2c, nil
}

func hkdfExpand(ikm, salt []byte, info string, n int) ([]byte, error) {
	r := hkdf.New(sha256.New, ikm, salt, []byte(info))
	out := make([]byte, n)
	_, err := io.ReadFull(r, out)
	return out, err
}

func PubHash(pub []byte) string {
	h := sha256.Sum256(pub)
	return hex.EncodeToString(h[:])
}

// ---------- AEAD ----------

type Sealer struct {
	mu   sync.Mutex
	aead cipher.AEAD
	seq  uint64
}

func NewSealer(key []byte) (*Sealer, error) {
	c, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: c}, nil
}

func nonce(seq uint64) []byte {
	var n [24]byte
	binary.BigEndian.PutUint64(n[8:16], seq)
	return n[:]
}

// Seal запечатывает payload (mux-фрейм) со случайным паддингом.
func (s *Sealer) Seal(payload []byte) []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	padLen := randPad()
	pt := make([]byte, 1+padLen+len(payload))
	pt[0] = byte(padLen)
	if _, err := rand.Read(pt[1 : 1+padLen]); err == nil {
		// если rand сбой — паддинг нулевой, не критично
	}
	copy(pt[1+padLen:], payload)
	out := make([]byte, 10+len(pt)+chacha20poly1305.Overhead)
	binary.BigEndian.PutUint64(out[0:8], s.seq)
	binary.BigEndian.PutUint16(out[8:10], uint16(len(pt)+chacha20poly1305.Overhead))
	s.aead.Seal(out[10:10], nonce(s.seq), pt, nil)
	s.seq++
	return out
}

func randPad() int {
	var b [1]byte
	rand.Read(b[:])
	return int(b[0] & 0x7F) // 0..127
}

type Opener struct {
	mu     sync.Mutex
	aead   cipher.AEAD
	seen   map[uint64]struct{}
	max    uint64
	maxSet bool
}

func NewOpener(key []byte) (*Opener, error) {
	c, err := chacha20poly1305.NewX(key)
	if err != nil {
		return nil, err
	}
	return &Opener{aead: c, seen: map[uint64]struct{}{}}, nil
}

const replayWindow = 65536

// Open принимает полный AEAD-фрейм, возвращает mux-фрейм.
func (o *Opener) Open(frame []byte) ([]byte, error) {
	if len(frame) < 10+chacha20poly1305.Overhead {
		return nil, fmt.Errorf("frame too short: %d", len(frame))
	}
	seq := binary.BigEndian.Uint64(frame[0:8])
	flen := int(binary.BigEndian.Uint16(frame[8:10]))
	if 10+flen > len(frame) {
		return nil, fmt.Errorf("bad frame length")
	}
	o.mu.Lock()
	if o.maxSet && seq <= o.max {
		if o.max-seq >= replayWindow {
			o.mu.Unlock()
			return nil, ErrTooOld
		}
		if _, dup := o.seen[seq]; dup {
			o.mu.Unlock()
			return nil, ErrTooOld
		}
	}
	o.mu.Unlock()
	pt, err := o.aead.Open(nil, nonce(seq), frame[10:10+flen], nil)
	if err != nil {
		return nil, err
	}
	o.remember(seq)
	if len(pt) < 1 {
		return nil, fmt.Errorf("bad plaintext")
	}
	padLen := int(pt[0])
	if 1+padLen > len(pt) {
		return nil, fmt.Errorf("bad padding")
	}
	return pt[1+padLen:], nil
}

func (o *Opener) remember(seq uint64) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.maxSet = true
	if seq > o.max {
		o.max = seq
	}
	if len(o.seen) > replayWindow*2 {
		// сброс: всё, что младше max-window, больше не придёт (или будет отброшено)
		nw := map[uint64]struct{}{}
		for s := range o.seen {
			if o.max-s < replayWindow {
				nw[s] = struct{}{}
			}
		}
		o.seen = nw
	}
	o.seen[seq] = struct{}{}
}

// NextFrameLen парсит заголовок фрейма из потока байт.
func NextFrameLen(b []byte) (total int, ok bool) {
	if len(b) < 10 {
		return 0, false
	}
	flen := int(binary.BigEndian.Uint16(b[8:10]))
	total = 10 + flen
	return total, len(b) >= total
}

// ---------- mux-фрейм ----------

type Frame struct {
	Stream uint32
	Type   byte
	Seq    uint32
	Win    uint32
	Data   []byte
}

const (
	TypeOpen    = 1
	TypeOpenOK  = 2
	TypeOpenErr = 3
	TypeData    = 4
	TypeAck     = 5
	TypeFin     = 6
	TypeRst     = 7
	TypeNop     = 8
)

func EncodeMux(f Frame) []byte {
	out := make([]byte, 13+len(f.Data))
	binary.BigEndian.PutUint32(out[0:4], f.Stream)
	out[4] = f.Type
	binary.BigEndian.PutUint32(out[5:9], f.Seq)
	binary.BigEndian.PutUint32(out[9:13], f.Win)
	copy(out[13:], f.Data)
	return out
}

func DecodeMux(b []byte) (Frame, error) {
	var f Frame
	if len(b) < 13 {
		return f, fmt.Errorf("mux frame too short")
	}
	f.Stream = binary.BigEndian.Uint32(b[0:4])
	f.Type = b[4]
	f.Seq = binary.BigEndian.Uint32(b[5:9])
	f.Win = binary.BigEndian.Uint32(b[9:13])
	f.Data = b[13:]
	return f, nil
}
