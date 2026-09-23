package session

import (
	"net"
	"testing"
	"yctun/internal/proto"
)

func TestOutOfOrderFirstFrameDoesNotAckMissingZero(t *testing.T) {
	sess := New(nil)
	st := newStream(sess, 1)
	st.deliverData(1, []byte("second"))
	seq, _ := st.ackState()
	if seq != ^uint32(0) {
		t.Fatalf("acked absent first frame: %d", seq)
	}
	st.pend = []pending{{seq: 0, data: []byte("first")}, {seq: 1, data: []byte("second")}}
	st.inFlight = 11
	st.applyAck(seq, MaxRecvBuf)
	if len(st.pend) != 2 {
		t.Fatalf("ACK prematurely discarded data: %d", len(st.pend))
	}
	st.deliverData(0, []byte("first"))
	seq, _ = st.ackState()
	if seq != 1 {
		t.Fatalf("not contiguous: %d", seq)
	}
	st.applyAck(seq, MaxRecvBuf)
	if len(st.pend) != 0 {
		t.Fatalf("ACK failed to discard data")
	}
}
func TestServerOpenIsBounded(t *testing.T) {
	entered := make(chan struct{}, MaxStreams*2)
	unblock := make(chan struct{})
	s := New(func(string) (net.Conn, error) { entered <- struct{}{}; <-unblock; return nil, ErrClosed })
	for i := uint32(1); i <= MaxStreams+8; i++ {
		s.HandleFrame(proto.Frame{Type: proto.TypeOpen, Stream: i, Data: []byte("example.com:443")})
	}
	s.mu.Lock()
	pending := len(s.serverPending)
	s.mu.Unlock()
	if pending > MaxStreams {
		t.Fatalf("unbounded pending dials: %d", pending)
	}
	close(unblock)
	s.Close()
}
