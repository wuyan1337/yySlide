package wgbridge

import (
	"fmt"
	"strings"
	"testing"
)

const validConfig = `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.9.0.2/32
DNS = 1.1.1.1, 9.9.9.9

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = 203.0.113.5:51820
AllowedIPs = 0.0.0.0/0, ::/0
PersistentKeepalive = 25
`

func TestParseConfigExtractsInterfaceAndPeer(t *testing.T) {
	p, err := parseConfig(validConfig)
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if len(p.addresses) != 1 || p.addresses[0].String() != "10.9.0.2" {
		t.Errorf("wrong addresses: %v", p.addresses)
	}
	if len(p.dns) != 2 {
		t.Errorf("wrong DNS count: %v", p.dns)
	}
	if !strings.Contains(p.uapi, "private_key=") {
		t.Errorf("uapi missing private_key: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "endpoint=203.0.113.5:51820") {
		t.Errorf("uapi missing endpoint: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "persistent_keepalive_interval=25") {
		t.Errorf("uapi missing keepalive: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "allowed_ip=0.0.0.0/0") {
		t.Errorf("uapi missing allowed_ip v4: %s", p.uapi)
	}
	if !strings.Contains(p.uapi, "allowed_ip=::/0") {
		t.Errorf("uapi missing allowed_ip v6: %s", p.uapi)
	}
}

func TestParseConfigRejectsMissingSection(t *testing.T) {
	cases := map[string]string{
		"missing interface": `[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = x:1`,
		"missing peer": `[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.1/32`,
		"missing private key": `[Interface]
Address = 10.0.0.1/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = x:1`,
	}
	for name, cfg := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := parseConfig(cfg); err == nil {
				t.Fatalf("expected error")
			}
		})
	}
}

func TestParseConfigUnknownKeysIgnored(t *testing.T) {
	// wg-quick configs may carry directives that aren't relevant to the
	// userspace netstack (PostUp, Table, MTU…). Don't choke on them.
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32
PostUp = iptables -A FORWARD -i %i -j ACCEPT
Table = off

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = 203.0.113.5:51820
AllowedIPs = 10.0.0.0/24
SomethingFutureWireGuardAdds = yes
`
	if _, err := parseConfig(cfg); err != nil {
		t.Fatalf("should ignore unknown keys, got %v", err)
	}
}

func TestParseConfigSkipsHostnameDns(t *testing.T) {
	// wg-quick commonly has DNS = fritz.box on home setups. Our
	// userspace netstack can't resolve names at tunnel-start time.
	// Skip unparseable entries rather than reject the whole config.
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32
DNS = fritz.box, 1.1.1.1, not-an-ip.local, 9.9.9.9

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = 203.0.113.5:51820
AllowedIPs = 0.0.0.0/0
`
	p, err := parseConfig(cfg)
	if err != nil {
		t.Fatalf("expected success with hostname DNS, got %v", err)
	}
	if len(p.dns) != 2 {
		t.Errorf("expected 2 IP DNS entries (fritz.box + not-an-ip.local dropped), got %d: %v", len(p.dns), p.dns)
	}
	wantDns := []string{"1.1.1.1", "9.9.9.9"}
	for i, want := range wantDns {
		if i >= len(p.dns) || p.dns[i].String() != want {
			t.Errorf("dns[%d] = %v, want %v", i, p.dns[i], want)
		}
	}
}

func TestParseConfigResolvesEndpointHostname(t *testing.T) {
	// wireguard-go's UAPI requires an IP-form endpoint; wg-quick accepts
	// hostnames and resolves them via the system resolver before passing
	// to `wg`. Mirror that — dynamic-DNS WG servers (myfritz.net etc.)
	// are common and we can't ship a tunnel that rejects them.
	//
	// Stub the resolver so this test doesn't hit the network. Picks an
	// IPv4 first when multiple returned — many home routers publish both
	// but IPv6 reachability is flaky in practice.
	saved := endpointResolver
	t.Cleanup(func() { endpointResolver = saved })
	endpointResolver = func(host string) ([]string, error) {
		if host == "dyndns.example.org" {
			return []string{"2001:db8::1", "93.184.216.34"}, nil
		}
		return nil, fmt.Errorf("unexpected lookup of %q", host)
	}

	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = dyndns.example.org:51820
AllowedIPs = 0.0.0.0/0
`
	p, err := parseConfig(cfg)
	if err != nil {
		t.Fatalf("expected success with hostname endpoint, got %v", err)
	}
	want := "endpoint=93.184.216.34:51820"
	if !strings.Contains(p.uapi, want) {
		t.Errorf("expected uapi to contain %q (IPv4 preferred), got %s", want, p.uapi)
	}
	// Also verify the IPv6 wasn't picked.
	if strings.Contains(p.uapi, "endpoint=[2001:db8::1]") {
		t.Error("picked IPv6 when IPv4 was available")
	}
}

func TestParseConfigPassesThroughIPEndpoint(t *testing.T) {
	// No resolver call when the endpoint is already an IP.
	saved := endpointResolver
	t.Cleanup(func() { endpointResolver = saved })
	endpointResolver = func(host string) ([]string, error) {
		t.Fatalf("resolver should not be called for literal IPs, was called with %q", host)
		return nil, nil
	}
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = 203.0.113.5:51820
AllowedIPs = 0.0.0.0/0
`
	p, err := parseConfig(cfg)
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if !strings.Contains(p.uapi, "endpoint=203.0.113.5:51820") {
		t.Errorf("literal IP endpoint should pass through verbatim: %s", p.uapi)
	}
}

func TestParseConfigRejectsUnresolvableEndpoint(t *testing.T) {
	saved := endpointResolver
	t.Cleanup(func() { endpointResolver = saved })
	endpointResolver = func(host string) ([]string, error) {
		return nil, fmt.Errorf("nxdomain")
	}
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = nonexistent.example:51820
AllowedIPs = 0.0.0.0/0
`
	_, err := parseConfig(cfg)
	if err == nil {
		t.Fatal("expected error on unresolvable endpoint")
	}
	if !strings.Contains(err.Error(), "nonexistent.example") {
		t.Errorf("error should mention the hostname: %v", err)
	}
}

func TestParseConfigMultiplePeers(t *testing.T) {
	cfg := `
[Interface]
PrivateKey = cEJxGEJjYIL8XHiCnVAhYQGzNuSrt7ZbQXGj4Bgn7nY=
Address = 10.0.0.2/32

[Peer]
PublicKey = aV0TdsXGYmZBh2RM0V+g3xNkZwFxVzS3a0VqL2EXfDc=
Endpoint = 203.0.113.10:51820
AllowedIPs = 10.0.0.0/24

[Peer]
PublicKey = M09vVwU4oVBI28YlYcRm7e+jZ3+xmJ9EqmR1V6lQlgk=
Endpoint = 203.0.113.11:51820
AllowedIPs = 10.1.0.0/24
`
	p, err := parseConfig(cfg)
	if err != nil {
		t.Fatalf("expected success, got %v", err)
	}
	if !strings.Contains(p.uapi, "endpoint=203.0.113.10:51820") ||
		!strings.Contains(p.uapi, "endpoint=203.0.113.11:51820") {
		t.Errorf("uapi missing one of the peer endpoints: %s", p.uapi)
	}
}
