package proto

import (
	"bytes"
	"testing"
)

func TestSealOpenRoundtrip(t *testing.T) {
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	s, err := NewSealer(key)
	if err != nil {
		t.Fatal(err)
	}
	o, err := NewOpener(key)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 1000; i++ {
		payload := []byte("hello tunnel " + string(rune(i)))
		got, err := o.Open(s.Seal(payload))
		if err != nil {
			t.Fatalf("i=%d: %v", i, err)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("i=%d: payload mismatch", i)
		}
	}
}

func TestReplayDropped(t *testing.T) {
	key := make([]byte, 32)
	s, _ := NewSealer(key)
	o, _ := NewOpener(key)
	f := s.Seal([]byte("data"))
	if _, err := o.Open(f); err != nil {
		t.Fatal(err)
	}
	if _, err := o.Open(f); err == nil {
		t.Fatal("replay прошёл — это дыра")
	}
}

func TestDeriveSymmetric(t *testing.T) {
	psk := []byte("secret-psk")
	apriv, apub, _ := GenStaticKey()
	bpriv, bpub, _ := GenStaticKey()
	c2sA, s2cA, _ := DeriveKeys(apriv, bpub, psk)
	c2sB, s2cB, _ := DeriveKeys(bpriv, apub, psk)
	if !bytes.Equal(c2sA, c2sB) || !bytes.Equal(s2cA, s2cB) {
		t.Fatal("ключи не сошлись")
	}
}

func TestFrameLen(t *testing.T) {
	key := make([]byte, 32)
	s, _ := NewSealer(key)
	a := s.Seal([]byte("aaaa"))
	b := s.Seal([]byte("bbbbbbbbbbbb"))
	stream := append(a, b...)
	n1, ok := NextFrameLen(stream)
	if !ok || n1 != len(a) {
		t.Fatalf("n1=%d want %d", n1, len(a))
	}
	stream = stream[n1:]
	n2, ok := NextFrameLen(stream)
	if !ok || n2 != len(b) {
		t.Fatalf("n2=%d want %d", n2, len(b))
	}
	if _, ok := NextFrameLen(stream[:5]); ok {
		t.Fatal("короткий хвост не должен парситься")
	}
}
