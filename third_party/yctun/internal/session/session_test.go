package session

import (
	"net"
	"testing"
	"time"

	"yctun/internal/proto"
)

func TestWindowUpdateSentOnRead(t *testing.T) {
	sess := New(nil)
	defer sess.Close()
	st := newStream(sess, 1)
	st.lastAckWin = 0 // simulate zero window previously advertised

	// Deliver some data
	st.deliverData(0, []byte("data payload"))
	// Drain outCh
	select {
	case <-sess.Out():
	default:
	}

	// Read data
	buf := make([]byte, 64)
	n, err := st.Read(buf)
	if err != nil || n == 0 {
		t.Fatalf("read failed: n=%d err=%v", n, err)
	}

	// An ACK window update frame should have been sent to outCh
	select {
	case f := <-sess.Out():
		if f.Type != proto.TypeAck {
			t.Fatalf("expected TypeAck frame, got %d", f.Type)
		}
		if f.Win < MaxRecvBuf/2 {
			t.Fatalf("expected updated window, got %d", f.Win)
		}
	default:
		t.Fatal("expected window update frame in outCh, got none")
	}
}

func TestPumpsCleanExitOnStreamClose(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()

	sess := New(nil)
	defer sess.Close()

	st := newStream(sess, 42)
	sess.streams[42] = st

	sess.runPumps(st, client)

	// Close the remote pipe to simulate connection end
	client.Close()

	// Wait briefly for pumps to finish and remove stream
	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		sess.mu.Lock()
		_, exists := sess.streams[42]
		sess.mu.Unlock()
		if !exists {
			return // successfully cleaned up!
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("stream was not cleaned up after pump exit")
}

func TestOutOfOrderFirstFrameDoesNotAckMissingZero(t *testing.T) {
	sess := New(nil)
	defer sess.Close()
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
