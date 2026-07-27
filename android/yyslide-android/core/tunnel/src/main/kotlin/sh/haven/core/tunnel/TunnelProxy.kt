package sh.haven.core.tunnel

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * JSch [Proxy] adapter over a [Tunnel]. JSch hands us `(host, port)` at
 * connect time; we dial through the tunnel and surface the resulting
 * streams. `getSocket()` returns null — our connections are not backed by
 * a kernel socket (see [TunneledConnection]), and [Proxy] contract allows
 * null there.
 *
 * Mirrors the shape of [sh.haven.core.ssh.ProxyJump] — the existing JSch
 * proxy for jump-host tunneling. Same lifecycle (`connect` → stream I/O →
 * `close`), just a different source of bytes.
 */
class TunnelProxy(private val tunnel: Tunnel) : Proxy {

    private var connection: TunneledConnection? = null

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
        // Tunnels need more headroom than a direct TCP SYN — peer session
        // setup (WireGuard handshake, magicsock NAT punch, DERP fallback,
        // MagicDNS lookups) can burn 1-2 s before application traffic
        // flows. JSch's default 10 s connect timeout often isn't enough
        // on first dial to a cold peer. Clamp to at least 30 s.
        val effectiveTimeout = maxOf(timeout, TUNNEL_MIN_DIAL_TIMEOUT_MS)
        connection = tunnel.dial(host, port, effectiveTimeout)
    }

    override fun getInputStream(): InputStream =
        connection?.inputStream ?: error("TunnelProxy.connect() not called")

    override fun getOutputStream(): OutputStream =
        connection?.outputStream ?: error("TunnelProxy.connect() not called")

    override fun getSocket(): Socket? = null

    override fun close() {
        connection?.close()
        connection = null
    }

    private companion object {
        const val TUNNEL_MIN_DIAL_TIMEOUT_MS = 30_000
    }
}
