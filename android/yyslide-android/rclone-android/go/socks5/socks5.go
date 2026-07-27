// Package socks5 is the authenticated localhost SOCKS5 server shared by
// wgbridge and tsbridge. It intentionally implements only RFC 1928 CONNECT
// plus RFC 1929 username/password authentication.
package socks5

import (
	"crypto/subtle"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

// Dialer establishes the tunneled upstream connection after a CONNECT
// request has passed authentication and destination policy checks.
type Dialer func(host string, port int) (net.Conn, error)

// Config controls the security and resource limits of a SOCKS listener.
type Config struct {
	Username         string
	Password         string
	MaxConnections   int
	HandshakeTimeout time.Duration
	IdleTimeout      time.Duration
}

type server struct {
	config Config
	dialer Dialer
	slots  chan struct{}
	mu     sync.Mutex
	active map[net.Conn]struct{}
}

// Serve accepts authenticated SOCKS5 connections until ln is closed. It
// closes every active client when the listener exits, so tunnel teardown does
// not leave localhost sockets blocked indefinitely.
func Serve(ln net.Listener, config Config, dialer Dialer) {
	config = normalizedConfig(config)
	if err := validateCredentials(config.Username, config.Password); err != nil {
		_ = ln.Close()
		return
	}
	s := &server{
		config: config,
		dialer: dialer,
		slots:  make(chan struct{}, config.MaxConnections),
		active: make(map[net.Conn]struct{}),
	}
	defer s.closeActive()

	for {
		client, err := ln.Accept()
		if err != nil {
			return
		}
		select {
		case s.slots <- struct{}{}:
			s.track(client)
			go func() {
				defer func() {
					s.untrack(client)
					<-s.slots
				}()
				s.handle(client)
			}()
		default:
			// Refuse excess clients before allocating a worker or reading an
			// attacker-controlled handshake.
			_ = client.Close()
		}
	}
}

func normalizedConfig(config Config) Config {
	if config.MaxConnections <= 0 {
		config.MaxConnections = 8
	}
	if config.HandshakeTimeout <= 0 {
		config.HandshakeTimeout = 10 * time.Second
	}
	if config.IdleTimeout <= 0 {
		config.IdleTimeout = 5 * time.Minute
	}
	return config
}

func validateCredentials(username, password string) error {
	if len(username) == 0 || len(username) > 255 {
		return errors.New("socks5: username must be 1..255 bytes")
	}
	if len(password) == 0 || len(password) > 255 {
		return errors.New("socks5: password must be 1..255 bytes")
	}
	return nil
}

func (s *server) track(c net.Conn) {
	s.mu.Lock()
	s.active[c] = struct{}{}
	s.mu.Unlock()
}

func (s *server) untrack(c net.Conn) {
	_ = c.Close()
	s.mu.Lock()
	delete(s.active, c)
	s.mu.Unlock()
}

func (s *server) closeActive() {
	s.mu.Lock()
	clients := make([]net.Conn, 0, len(s.active))
	for c := range s.active {
		clients = append(clients, c)
	}
	s.mu.Unlock()
	for _, c := range clients {
		_ = c.Close()
	}
}

func (s *server) handle(client net.Conn) {
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(s.config.HandshakeTimeout))
	if err := negotiate(client); err != nil {
		return
	}
	if err := authenticate(client, s.config.Username, s.config.Password); err != nil {
		return
	}
	host, port, err := readConnect(client)
	if err != nil {
		var requestErr *connectRequestError
		if errors.As(err, &requestErr) {
			_ = writeReply(client, requestErr.reply)
		} else {
			_ = writeReply(client, repGeneralFailure)
		}
		return
	}
	if err := validateTarget(host); err != nil {
		_ = writeReply(client, repConnectionNotAllowed)
		return
	}

	upstream, err := s.dialer(host, port)
	if err != nil {
		_ = writeReply(client, repConnectionRefused)
		return
	}
	defer upstream.Close()
	// Re-check the resolved peer to stop DNS rebinding from turning a
	// harmless-looking hostname into loopback, link-local, or metadata access.
	if err := validateRemote(upstream.RemoteAddr()); err != nil {
		_ = writeReply(client, repConnectionNotAllowed)
		return
	}
	if err := writeReply(client, repSuccess); err != nil {
		return
	}
	pipeWithIdleTimeout(client, upstream, s.config.IdleTimeout)
}

// SOCKS5 reply codes (RFC 1928 section 6).
const (
	repSuccess              = 0x00
	repGeneralFailure       = 0x01
	repConnectionNotAllowed = 0x02
	repConnectionRefused    = 0x05
	repCommandNotSupported  = 0x07
	repAddressNotSupported  = 0x08
)

// negotiate requires RFC 1929 username/password authentication. Offering
// no-auth is never sufficient, even though the listener is loopback-only.
func negotiate(c net.Conn) error {
	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		return err
	}
	if hdr[0] != 0x05 || hdr[1] == 0 {
		return errors.New("socks5: invalid method negotiation")
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	for _, method := range methods {
		if method == 0x02 {
			_, err := c.Write([]byte{0x05, 0x02})
			return err
		}
	}
	_, _ = c.Write([]byte{0x05, 0xFF})
	return errors.New("socks5: username/password authentication required")
}

func authenticate(c net.Conn, expectedUsername, expectedPassword string) error {
	var header [2]byte
	if _, err := io.ReadFull(c, header[:]); err != nil {
		return err
	}
	if header[0] != 0x01 || header[1] == 0 {
		_, _ = c.Write([]byte{0x01, 0x01})
		return errors.New("socks5: invalid RFC1929 request")
	}
	username := make([]byte, int(header[1]))
	if _, err := io.ReadFull(c, username); err != nil {
		return err
	}
	var passwordLength [1]byte
	if _, err := io.ReadFull(c, passwordLength[:]); err != nil {
		return err
	}
	if passwordLength[0] == 0 {
		_, _ = c.Write([]byte{0x01, 0x01})
		return errors.New("socks5: empty password")
	}
	password := make([]byte, int(passwordLength[0]))
	if _, err := io.ReadFull(c, password); err != nil {
		return err
	}
	usernameOK := subtle.ConstantTimeCompare(username, []byte(expectedUsername))
	passwordOK := subtle.ConstantTimeCompare(password, []byte(expectedPassword))
	if usernameOK&passwordOK != 1 {
		_, _ = c.Write([]byte{0x01, 0x01})
		return errors.New("socks5: authentication failed")
	}
	_, err := c.Write([]byte{0x01, 0x00})
	return err
}

type connectRequestError struct {
	reply byte
	msg   string
}

func (e *connectRequestError) Error() string { return e.msg }

// readConnect parses a CONNECT request and returns the caller-supplied target.
func readConnect(c net.Conn) (string, int, error) {
	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil {
		return "", 0, err
	}
	if req[0] != 0x05 || req[2] != 0x00 {
		return "", 0, &connectRequestError{repGeneralFailure, "socks5: malformed request"}
	}
	if req[1] != 0x01 {
		return "", 0, &connectRequestError{repCommandNotSupported, "socks5: only CONNECT is supported"}
	}
	var host string
	switch req[3] {
	case 0x01:
		var ip [4]byte
		if _, err := io.ReadFull(c, ip[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(ip[:]).String()
	case 0x03:
		var length [1]byte
		if _, err := io.ReadFull(c, length[:]); err != nil {
			return "", 0, err
		}
		if length[0] == 0 {
			return "", 0, &connectRequestError{repAddressNotSupported, "socks5: empty domain"}
		}
		name := make([]byte, int(length[0]))
		if _, err := io.ReadFull(c, name); err != nil {
			return "", 0, err
		}
		host = string(name)
	case 0x04:
		var ip [16]byte
		if _, err := io.ReadFull(c, ip[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(ip[:]).String()
	default:
		return "", 0, &connectRequestError{repAddressNotSupported, "socks5: unsupported address type"}
	}
	var encodedPort [2]byte
	if _, err := io.ReadFull(c, encodedPort[:]); err != nil {
		return "", 0, err
	}
	port := int(binary.BigEndian.Uint16(encodedPort[:]))
	if port == 0 {
		return "", 0, &connectRequestError{repConnectionNotAllowed, "socks5: port zero is not allowed"}
	}
	return host, port, nil
}

var blockedHostnames = map[string]struct{}{
	"localhost":                  {},
	"metadata":                   {},
	"metadata.google":            {},
	"metadata.google.internal":   {},
	"metadata.azure.internal":    {},
	"instance-data.ec2.internal": {},
}

func validateTarget(host string) error {
	normalized := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(host)), ".")
	if normalized == "" || strings.ContainsRune(normalized, '\x00') {
		return errors.New("socks5: empty or invalid target")
	}
	if _, blocked := blockedHostnames[normalized]; blocked || strings.HasSuffix(normalized, ".localhost") {
		return fmt.Errorf("socks5: target %q is not allowed", host)
	}
	if ip := net.ParseIP(strings.Trim(normalized, "[]")); ip != nil {
		return validateIP(ip)
	}
	return nil
}

func validateRemote(addr net.Addr) error {
	if addr == nil {
		return errors.New("socks5: upstream has no remote address")
	}
	if tcpAddr, ok := addr.(*net.TCPAddr); ok {
		return validateIP(tcpAddr.IP)
	}
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		return fmt.Errorf("socks5: cannot validate upstream address %q", addr.String())
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	if ip == nil {
		return fmt.Errorf("socks5: upstream address %q is not an IP", addr.String())
	}
	return validateIP(ip)
}

func validateIP(ip net.IP) error {
	if ip == nil || ip.IsUnspecified() || ip.IsLoopback() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsMulticast() {
		return fmt.Errorf("socks5: target IP %v is not allowed", ip)
	}
	// Alibaba Cloud's metadata endpoint is not in the IPv4 link-local range.
	if ip.Equal(net.ParseIP("100.100.100.200")) {
		return errors.New("socks5: metadata target is not allowed")
	}
	return nil
}

// writeReply uses an all-zero bind address; CONNECT clients do not rely on it.
func writeReply(c net.Conn, status byte) error {
	_, err := c.Write([]byte{0x05, status, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

type idleDeadlinePair struct {
	mu      sync.Mutex
	a       net.Conn
	b       net.Conn
	timeout time.Duration
}

func (p *idleDeadlinePair) touch() {
	deadline := time.Now().Add(p.timeout)
	p.mu.Lock()
	_ = p.a.SetDeadline(deadline)
	_ = p.b.SetDeadline(deadline)
	p.mu.Unlock()
}

func pipeWithIdleTimeout(a, b net.Conn, timeout time.Duration) {
	pair := &idleDeadlinePair{a: a, b: b, timeout: timeout}
	pair.touch()
	done := make(chan struct{}, 2)
	copyDirection := func(dst, src net.Conn) {
		buf := make([]byte, 32*1024)
		for {
			pair.touch()
			n, readErr := src.Read(buf)
			if n > 0 {
				pair.touch()
				if _, writeErr := dst.Write(buf[:n]); writeErr != nil {
					break
				}
			}
			if readErr != nil {
				break
			}
		}
		done <- struct{}{}
	}
	go copyDirection(a, b)
	go copyDirection(b, a)
	<-done
}
