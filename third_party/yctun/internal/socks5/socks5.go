// socks5: минимальный SOCKS5-сервер (no-auth, только CONNECT) для relayc.
package socks5

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"time"
)

type Opener func(ctx context.Context, addr string) (net.Conn, error)

type Server struct {
	Listen string
	Open   Opener
}

func (s *Server) Run(ctx context.Context) error {
	ln, err := net.Listen("tcp", s.Listen)
	if err != nil {
		return err
	}
	log.Printf("socks5: слушаю %s", s.Listen)
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return nil
			default:
				return err
			}
		}
		go s.handle(conn)
	}
}

func (s *Server) handle(conn net.Conn) {
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(30 * time.Second))
	if err := s.negotiate(conn); err != nil {
		return
	}
	addr, err := s.readRequest(conn)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	remote, err := s.Open(ctx, addr)
	cancel()
	if err != nil {
		replyErr(conn, 0x05)
		log.Printf("socks5: connect %s: %v", addr, err)
		return
	}
	defer remote.Close()
	conn.SetDeadline(time.Time{})
	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}
	pump(conn, remote)
}

func (s *Server) negotiate(conn net.Conn) error {
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return err
	}
	if hdr[0] != 0x05 {
		return fmt.Errorf("bad version %d", hdr[0])
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return err
	}
	noAuth := false
	for _, m := range methods {
		if m == 0x00 {
			noAuth = true
			break
		}
	}
	if !noAuth {
		conn.Write([]byte{0x05, 0xFF})
		return fmt.Errorf("no acceptable auth")
	}
	_, err := conn.Write([]byte{0x05, 0x00})
	return err
}

func (s *Server) readRequest(conn net.Conn) (string, error) {
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return "", err
	}
	if hdr[1] != 0x01 { // только CONNECT
		replyErr(conn, 0x07)
		return "", fmt.Errorf("cmd %d unsupported", hdr[1])
	}
	var host string
	switch hdr[3] {
	case 0x01: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 0x03: // домен
		var l [1]byte
		if _, err := io.ReadFull(conn, l[:]); err != nil {
			return "", err
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", err
		}
		host = string(b)
	case 0x04: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(conn, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	default:
		replyErr(conn, 0x08)
		return "", fmt.Errorf("bad atyp %d", hdr[3])
	}
	var port [2]byte
	if _, err := io.ReadFull(conn, port[:]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(port[:])))), nil
}

func replyErr(conn net.Conn, code byte) {
	conn.Write([]byte{0x05, code, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
}

func pump(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if tc, ok := dst.(*net.TCPConn); ok {
			tc.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	a.Close()
	b.Close()
	<-done
}
