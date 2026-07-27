package socks5

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

const (
	testUsername = "yyslide"
	testPassword = "unit-test-secret"
)

func startEchoServer(t *testing.T) (port int, close func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen echo: %v", err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}()
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, func() { _ = ln.Close() }
}

func startSocks5(t *testing.T, dialer Dialer) (port int, close func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen socks5: %v", err)
	}
	go Serve(ln, Config{
		Username:         testUsername,
		Password:         testPassword,
		MaxConnections:   4,
		HandshakeTimeout: 500 * time.Millisecond,
		IdleTimeout:      2 * time.Second,
	}, dialer)
	return ln.Addr().(*net.TCPAddr).Port, func() { _ = ln.Close() }
}

type publicRemoteConn struct {
	net.Conn
	port int
}

func (c publicRemoteConn) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.ParseIP("203.0.113.10"), Port: c.port}
}

func authenticatedClient(t *testing.T, proxyPort int, username, password string) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatalf("dial socks5: %v", err)
	}
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := c.Write([]byte{0x05, 0x01, 0x02}); err != nil {
		t.Fatalf("write method negotiation: %v", err)
	}
	methodReply := make([]byte, 2)
	if _, err := io.ReadFull(c, methodReply); err != nil {
		t.Fatalf("read method reply: %v", err)
	}
	if !bytes.Equal(methodReply, []byte{0x05, 0x02}) {
		t.Fatalf("expected RFC1929 method, got %x", methodReply)
	}
	auth := []byte{0x01, byte(len(username))}
	auth = append(auth, []byte(username)...)
	auth = append(auth, byte(len(password)))
	auth = append(auth, []byte(password)...)
	if _, err := c.Write(auth); err != nil {
		t.Fatalf("write auth: %v", err)
	}
	authReply := make([]byte, 2)
	if _, err := io.ReadFull(c, authReply); err != nil {
		t.Fatalf("read auth reply: %v", err)
	}
	if !bytes.Equal(authReply, []byte{0x01, 0x00}) {
		t.Fatalf("authentication failed: %x", authReply)
	}
	return c
}

func connectRequest(t *testing.T, c net.Conn, atyp byte, host string, port int) byte {
	t.Helper()
	var req bytes.Buffer
	req.Write([]byte{0x05, 0x01, 0x00, atyp})
	switch atyp {
	case 0x01:
		ip := net.ParseIP(host).To4()
		if ip == nil {
			t.Fatalf("not an IPv4 address: %q", host)
		}
		req.Write(ip)
	case 0x03:
		req.WriteByte(byte(len(host)))
		req.WriteString(host)
	case 0x04:
		ip := net.ParseIP(host).To16()
		if ip == nil {
			t.Fatalf("not an IPv6 address: %q", host)
		}
		req.Write(ip)
	default:
		t.Fatalf("unsupported ATYP %x", atyp)
	}
	_ = binary.Write(&req, binary.BigEndian, uint16(port))
	if _, err := c.Write(req.Bytes()); err != nil {
		t.Fatalf("write CONNECT: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatalf("read CONNECT reply: %v", err)
	}
	return reply[1]
}

func socks5Connect(t *testing.T, proxyPort int, atyp byte, host string, port int) net.Conn {
	t.Helper()
	c := authenticatedClient(t, proxyPort, testUsername, testPassword)
	if reply := connectRequest(t, c, atyp, host, port); reply != repSuccess {
		c.Close()
		t.Fatalf("CONNECT failed, REP=0x%02x", reply)
	}
	_ = c.SetDeadline(time.Time{})
	return c
}

func TestEndToEndDomainAndIPv4(t *testing.T) {
	echoPort, closeEcho := startEchoServer(t)
	defer closeEcho()
	dialer := func(_ string, port int) (net.Conn, error) {
		conn, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(echoPort)))
		if err != nil {
			return nil, err
		}
		return publicRemoteConn{Conn: conn, port: port}, nil
	}
	proxyPort, closeSocks := startSocks5(t, dialer)
	defer closeSocks()

	for _, target := range []struct {
		atyp byte
		host string
	}{{0x03, "echo.example"}, {0x01, "203.0.113.10"}} {
		c := socks5Connect(t, proxyPort, target.atyp, target.host, echoPort)
		_ = c.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := c.Write([]byte("ping")); err != nil {
			t.Fatalf("write: %v", err)
		}
		buf := make([]byte, 4)
		if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "ping" {
			t.Fatalf("echo failed: %q, %v", string(buf), err)
		}
		_ = c.Close()
	}
}

func TestRequiresRFC1929AndRejectsWrongCredentials(t *testing.T) {
	proxyPort, closeSocks := startSocks5(t, func(string, int) (net.Conn, error) { return nil, io.EOF })
	defer closeSocks()

	noAuth, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer noAuth.Close()
	_ = noAuth.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = noAuth.Write([]byte{0x05, 0x01, 0x00})
	reply := make([]byte, 2)
	if _, err := io.ReadFull(noAuth, reply); err != nil || !bytes.Equal(reply, []byte{0x05, 0xFF}) {
		t.Fatalf("no-auth should be rejected, got %x (%v)", reply, err)
	}

	wrong, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer wrong.Close()
	_ = wrong.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = wrong.Write([]byte{0x05, 0x01, 0x02})
	_, _ = io.ReadFull(wrong, reply)
	auth := []byte{0x01, 8}
	auth = append(auth, []byte("attacker")...)
	auth = append(auth, 5)
	auth = append(auth, []byte("wrong")...)
	_, _ = wrong.Write(auth)
	if _, err := io.ReadFull(wrong, reply); err != nil || !bytes.Equal(reply, []byte{0x01, 0x01}) {
		t.Fatalf("wrong credentials should be rejected, got %x (%v)", reply, err)
	}
}

func TestBlocksLoopbackLinkLocalAndMetadataTargets(t *testing.T) {
	var dialCount atomic.Int32
	proxyPort, closeSocks := startSocks5(t, func(string, int) (net.Conn, error) {
		dialCount.Add(1)
		return nil, io.EOF
	})
	defer closeSocks()

	blocked := []struct {
		atyp byte
		host string
	}{{0x01, "127.0.0.1"}, {0x01, "169.254.169.254"}, {0x01, "100.100.100.200"}, {0x03, "metadata.google.internal"}, {0x03, "foo.localhost"}}
	for _, target := range blocked {
		c := authenticatedClient(t, proxyPort, testUsername, testPassword)
		if reply := connectRequest(t, c, target.atyp, target.host, 80); reply != repConnectionNotAllowed {
			t.Errorf("%s: expected not-allowed reply, got 0x%02x", target.host, reply)
		}
		_ = c.Close()
	}
	if got := dialCount.Load(); got != 0 {
		t.Fatalf("blocked targets reached dialer %d times", got)
	}
}

func TestRejectsDNSRebindingAfterDial(t *testing.T) {
	proxyPort, closeSocks := startSocks5(t, func(string, int) (net.Conn, error) {
		client, peer := net.Pipe()
		go peer.Close()
		return client, nil // pipeAddr cannot be validated: fail closed.
	})
	defer closeSocks()
	c := authenticatedClient(t, proxyPort, testUsername, testPassword)
	defer c.Close()
	if reply := connectRequest(t, c, 0x03, "looks-safe.example", 443); reply != repConnectionNotAllowed {
		t.Fatalf("expected rebinding protection, got 0x%02x", reply)
	}
}

func TestListenerCloseTearsDownActiveClients(t *testing.T) {
	echoPort, closeEcho := startEchoServer(t)
	defer closeEcho()
	dialer := func(_ string, port int) (net.Conn, error) {
		conn, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(echoPort)))
		if err != nil {
			return nil, err
		}
		return publicRemoteConn{Conn: conn, port: port}, nil
	}
	proxyPort, closeSocks := startSocks5(t, dialer)
	c := socks5Connect(t, proxyPort, 0x03, "echo.example", echoPort)
	closeSocks()
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	if _, err := c.Read(buf); err == nil {
		t.Fatal("active client remained open after listener shutdown")
	}
	_ = c.Close()
}

func TestRejectsNonSocks5(t *testing.T) {
	proxyPort, closeSocks := startSocks5(t, func(string, int) (net.Conn, error) { return nil, io.EOF })
	defer closeSocks()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(2 * time.Second))
	_, _ = c.Write([]byte{0x04, 0x01})
	buf := make([]byte, 1)
	if _, err := io.ReadFull(c, buf); err == nil {
		t.Fatalf("expected close on non-SOCKS5 greeting, got %x", buf)
	}
}
