package sh.haven.core.ssh

import com.jcraft.jsch.Proxy

/**
 * Haven-internal opaque handle representing the proxy / tunnel chain a
 * connection should route through.
 *
 * Constructed by `sh.haven.core.tunnel.TunnelResolver` (Tailscale, WireGuard,
 * Cloudflare Access, legacy SOCKS5/SOCKS4/HTTP). Consumed by [SshClient] —
 * which unwraps the underlying JSch `Proxy` internally — so callers in
 * feature- and app-modules can pass a proxy through without importing
 * `com.jcraft.jsch.Proxy` themselves.
 *
 * Will gain an alternate constructor / shape in the JSch → sshlib swap so
 * the underlying type becomes sshlib's `TransportFactory` instead of JSch's
 * `Proxy`. Keeping the public surface opaque now means callers only need
 * to be touched once: at the construction site (TunnelResolver).
 */
class HavenProxy(internal val jschProxy: Proxy)
