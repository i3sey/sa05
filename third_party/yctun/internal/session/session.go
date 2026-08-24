// session: мультиплексирование TCP-потоков поверх ненадёжно-упорядоченного
// канала. Каждый Stream = одно TCP-соединение (клиент: SOCKS-клиент,
// сервер: исходящий dial). Ретрансмит — по вызову Resend() после
// переподключения даунлинк-канала; дедупликация — по per-stream seq.
package session

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"

	"yctun/internal/proto"
)

const (
	MaxRecvBuf = 2 << 20 // окно приёма (рекламируемое)
	MaxSendBuf = 4 << 20 // предел локальной буферизации на отправку
	DataChunk  = 8 << 10 // макс. payload DATA-фрейма
)

const (
	stOpening = 1
	stOpen    = 2
	stClosed  = 3
)

var ErrClosed = errors.New("stream closed")

type pending struct {
	seq  uint32
	data []byte
}

type Stream struct {
	id    uint32
	sess  *Session
	mu    sync.Mutex
	cond  *sync.Cond
	state int
	err   error

	// отправка
	pend     []pending
	nextSeq  uint32
	inFlight int64
	peerWin  int64

	// приём
	recvBuf      []byte
	reorder      map[uint32][]byte
	reorderBytes int64
	recvNext     uint32
	finRecv      bool
	finSent      bool
}

func newStream(sess *Session, id uint32) *Stream {
	s := &Stream{id: id, sess: sess, state: stOpening, reorder: map[uint32][]byte{}}
	s.cond = sync.NewCond(&s.mu)
	return s
}

func (s *Stream) closeLocked(err error) {
	if s.state == stClosed {
		return
	}
	s.state = stClosed
	s.err = err
	s.cond.Broadcast()
}

// Write — net.Conn-совместимая запись (блокируется при заполнении окна).
func (s *Stream) Write(b []byte) (int, error) {
	if err := s.write(b, s.sess.outCh); err != nil {
		return 0, err
	}
	return len(b), nil
}

// Read — net.Conn-совместимое чтение; EOF при FIN/закрытии.
func (s *Stream) Read(b []byte) (int, error) {
	n, eof := s.read(b)
	if n > 0 {
		return n, nil
	}
	if eof {
		return 0, io.EOF
	}
	return 0, nil
}

func (s *Stream) Close() error {
	s.mu.Lock()
	if s.state != stClosed && !s.finSent {
		s.finSent = true
		s.mu.Unlock()
		s.sess.outCh <- proto.Frame{Stream: s.id, Type: proto.TypeFin}
	} else {
		s.mu.Unlock()
	}
	return nil
}

// Блокируется при превышении окна пира или локального буфера (backpressure).
func (s *Stream) write(data []byte, out chan<- proto.Frame) error {
	for len(data) > 0 {
		n := len(data)
		if n > DataChunk {
			n = DataChunk
		}
		s.mu.Lock()
		for s.state != stClosed && (s.peerWin <= 0 || s.inFlight >= s.peerWin || int64(len(s.pend))*DataChunk >= MaxSendBuf) {
			s.cond.Wait()
		}
		if s.state == stClosed {
			s.mu.Unlock()
			return s.errOrClosed()
		}
		chunk := append([]byte(nil), data[:n]...)
		seq := s.nextSeq
		s.nextSeq++
		s.pend = append(s.pend, pending{seq: seq, data: chunk})
		s.inFlight += int64(len(chunk))
		win := s.freeWinLocked()
		s.mu.Unlock()
		out <- proto.Frame{Stream: s.id, Type: proto.TypeData, Seq: seq, Win: win, Data: chunk}
		data = data[n:]
	}
	return nil
}

func (s *Stream) errOrClosed() error {
	if s.err != nil {
		return s.err
	}
	return ErrClosed
}

func (s *Stream) freeWinLocked() uint32 {
	free := int64(MaxRecvBuf) - int64(len(s.recvBuf)) - s.reorderBytes
	if free < 0 {
		free = 0
	}
	if free > MaxRecvBuf {
		free = MaxRecvBuf
	}
	return uint32(free)
}

// deliverData — входящий DATA: реордеринг, доставка, (вызов из HandleFrame).
func (s *Stream) deliverData(seq uint32, data []byte) {
	s.mu.Lock()
	if seq < s.recvNext {
		s.mu.Unlock() // дубль — ACK всё равно уйдёт
		return
	}
	if seq > s.recvNext {
		if _, ok := s.reorder[seq]; !ok {
			s.reorder[seq] = data
			s.reorderBytes += int64(len(data))
		}
	} else {
		s.recvBuf = append(s.recvBuf, data...)
		s.recvNext++
		for {
			d, ok := s.reorder[s.recvNext]
			if !ok {
				break
			}
			s.recvBuf = append(s.recvBuf, d...)
			s.reorderBytes -= int64(len(d))
			delete(s.reorder, s.recvNext)
			s.recvNext++
		}
		s.cond.Broadcast()
	}
	s.mu.Unlock()
}

// ackSeq/ackWin — что вернуть пиру в ACK.
func (s *Stream) ackState() (uint32, uint32) {
	s.mu.Lock()
	defer s.mu.Unlock()
	seq := uint32(0)
	if s.recvNext > 0 {
		seq = s.recvNext - 1
	}
	return seq, s.freeWinLocked()
}

// applyAck — обработка ACK от пира.
func (s *Stream) applyAck(seq uint32, win uint32) {
	s.mu.Lock()
	kept := s.pend[:0]
	for _, p := range s.pend {
		if p.seq > seq {
			kept = append(kept, p)
		} else {
			s.inFlight -= int64(len(p.data))
		}
	}
	s.pend = kept
	s.peerWin = int64(win)
	s.cond.Broadcast()
	s.mu.Unlock()
}

// resend возвращает копию неподтверждённых DATA-фреймов (для ретрансмита).
func (s *Stream) resend() []proto.Frame {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []proto.Frame
	for _, p := range s.pend {
		out = append(out, proto.Frame{Stream: s.id, Type: proto.TypeData, Seq: p.seq, Win: s.freeWinLocked(), Data: p.data})
	}
	return out
}

// read — данные в порядке (для записи в TCP-сокет). Блокируется.
// Возвращает (n>0, eof=false) или (0, eof=true) при FIN/закрытии.
func (s *Stream) read(b []byte) (int, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for len(s.recvBuf) == 0 && !s.finRecv && s.state != stClosed {
		s.cond.Wait()
	}
	if len(s.recvBuf) > 0 {
		n := copy(b, s.recvBuf)
		s.recvBuf = s.recvBuf[n:]
		return n, false
	}
	return 0, true
}

// markFinRecv — FIN от пира.
func (s *Stream) markFinRecv() {
	s.mu.Lock()
	s.finRecv = true
	s.cond.Broadcast()
	s.mu.Unlock()
}

// sendFin — отправить FIN (один раз).
func (s *Stream) sendFin(out chan<- proto.Frame) {
	s.mu.Lock()
	if s.finSent || s.state == stClosed {
		s.mu.Unlock()
		return
	}
	s.finSent = true
	s.mu.Unlock()
	out <- proto.Frame{Stream: s.id, Type: proto.TypeFin}
}

// ---------- Session ----------

type Session struct {
	mu      sync.Mutex
	streams map[uint32]*Stream
	nextID  uint32
	outCh   chan proto.Frame
	onOpen  func(addr string) (net.Conn, error) // только сервер
	opening map[uint32]chan error               // только клиент
	closed  bool
}

type OpenResult struct {
	Stream *Stream
	Err    error
}

func New(onOpen func(string) (net.Conn, error)) *Session {
	return &Session{
		streams: map[uint32]*Stream{},
		outCh:   make(chan proto.Frame, 4096),
		onOpen:  onOpen,
		opening: map[uint32]chan error{},
	}
}

func (s *Session) Out() <-chan proto.Frame { return s.outCh }

func (s *Session) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	for id, st := range s.streams {
		st.closeLocked(ErrClosed)
		delete(s.streams, id)
	}
	s.mu.Unlock()
}

// Open — клиентская сторона: открыть стрим к addr (host:port).
func (s *Session) Open(ctx context.Context, addr string) (*Stream, error) {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil, ErrClosed
	}
	id := s.nextID + 1
	s.nextID = id
	st := newStream(s, id)
	s.streams[id] = st
	ch := make(chan error, 1)
	s.opening[id] = ch
	s.mu.Unlock()

	s.outCh <- proto.Frame{Stream: id, Type: proto.TypeOpen, Data: []byte(addr)}

	select {
	case err := <-ch:
		if err != nil {
			s.remove(id)
			return nil, err
		}
		return st, nil
	case <-ctx.Done():
		s.remove(id)
		s.outCh <- proto.Frame{Stream: id, Type: proto.TypeRst}
		return nil, ctx.Err()
	}
}

func (s *Session) remove(id uint32) {
	s.mu.Lock()
	delete(s.streams, id)
	delete(s.opening, id)
	s.mu.Unlock()
}

// HandleFrame — входящий от пира фрейм (вызывается транспортным слоем).
func (s *Session) HandleFrame(f proto.Frame) {
	switch f.Type {
	case proto.TypeOpen:
		if s.onOpen == nil {
			s.outCh <- proto.Frame{Stream: f.Stream, Type: proto.TypeRst}
			return
		}
		// не блокируем транспорт: dial в горутине
		go func() {
			addr := string(f.Data)
			conn, err := s.onOpen(addr)
			if err != nil {
				s.outCh <- proto.Frame{Stream: f.Stream, Type: proto.TypeOpenErr, Data: []byte(err.Error())}
				s.remove(f.Stream)
				return
			}
			st := newStream(s, f.Stream)
			st.peerWin = MaxRecvBuf
			s.mu.Lock()
			s.streams[f.Stream] = st
			s.mu.Unlock()
			s.outCh <- proto.Frame{Stream: f.Stream, Type: proto.TypeOpenOK, Win: uint32(MaxRecvBuf)}
			s.runPumps(st, conn)
		}()

	case proto.TypeOpenOK:
		s.mu.Lock()
		st := s.streams[f.Stream]
		ch := s.opening[f.Stream]
		if st != nil {
			st.mu.Lock()
			st.state = stOpen
			if f.Win > 0 {
				st.peerWin = int64(f.Win)
			} else {
				st.peerWin = MaxRecvBuf
			}
			st.cond.Broadcast()
			st.mu.Unlock()
		}
		s.mu.Unlock()
		if ch != nil {
			ch <- nil
		}

	case proto.TypeOpenErr:
		s.mu.Lock()
		st := s.streams[f.Stream]
		ch := s.opening[f.Stream]
		if st != nil {
			st.closeLocked(fmt.Errorf("open failed: %s", string(f.Data)))
		}
		s.mu.Unlock()
		if ch != nil {
			ch <- fmt.Errorf("open failed: %s", string(f.Data))
		}

	case proto.TypeData:
		s.mu.Lock()
		st := s.streams[f.Stream]
		s.mu.Unlock()
		if st == nil {
			s.outCh <- proto.Frame{Stream: f.Stream, Type: proto.TypeRst}
			return
		}
		st.deliverData(f.Seq, f.Data)
		ackSeq, ackWin := st.ackState()
		s.outCh <- proto.Frame{Stream: f.Stream, Type: proto.TypeAck, Seq: ackSeq, Win: ackWin}

	case proto.TypeAck:
		s.mu.Lock()
		st := s.streams[f.Stream]
		s.mu.Unlock()
		if st != nil {
			st.applyAck(f.Seq, f.Win)
		}

	case proto.TypeFin:
		s.mu.Lock()
		st := s.streams[f.Stream]
		s.mu.Unlock()
		if st != nil {
			st.markFinRecv()
		}

	case proto.TypeRst:
		s.mu.Lock()
		st := s.streams[f.Stream]
		s.mu.Unlock()
		if st != nil {
			st.mu.Lock()
			st.closeLocked(ErrClosed)
			st.mu.Unlock()
		}
	}
}

// Resend — переслать все неподтверждённые DATA (после обрыва даунлинка).
func (s *Session) Resend() {
	s.mu.Lock()
	streams := make([]*Stream, 0, len(s.streams))
	for _, st := range s.streams {
		streams = append(streams, st)
	}
	s.mu.Unlock()
	for _, st := range streams {
		for _, f := range st.resend() {
			s.outCh <- f
		}
	}
}

// runPumps — два насоса на стрим (обе стороны одинаковы).
func (s *Session) runPumps(st *Stream, conn net.Conn) {
	// app -> сеть
	go func() {
		buf := make([]byte, 32<<10)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				if werr := st.write(buf[:n], s.outCh); werr != nil {
					conn.Close()
					return
				}
			}
			if err != nil {
				st.sendFin(s.outCh)
				conn.Close()
				return
			}
		}
	}()
	// сеть -> app
	go func() {
		buf := make([]byte, 32<<10)
		for {
			n, eof := st.read(buf)
			if n > 0 {
				if _, err := conn.Write(buf[:n]); err != nil {
					conn.Close()
					return
				}
			}
			if eof {
				conn.Close()
				return
			}
		}
	}()
}

// NumStreams — серверная гигиена: закрыть стримы без активности.
func (s *Session) NumStreams() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.streams)
}
