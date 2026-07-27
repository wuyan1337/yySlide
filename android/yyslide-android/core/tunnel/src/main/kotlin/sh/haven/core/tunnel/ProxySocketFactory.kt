package sh.haven.core.tunnel

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * javax.net.SocketFactory that dials through a SOCKS5 / SOCKS4 / HTTP
 * proxy (the legacy `proxyType`/`proxyHost`/`proxyPort` columns on
 * ConnectionProfile). Backed by the JDK's built-in [Proxy] support —
 * `Socket(java.net.Proxy)` performs the SOCKS or HTTP-CONNECT handshake
 * transparently when [Socket.connect] is called.
 *
 * Hostnames are passed unresolved to the proxy so DNS lookups happen on
 * the proxy side, not locally — important for Tor (.onion addresses
 * have no public DNS) and for any setup where the proxy can resolve
 * names the local resolver can't.
 *
 * Used by [TunnelResolver] when the profile has proxy fields set but no
 * `tunnelConfigId` — the SSH path already had this routing for years
 * via [TunnelResolver.jschProxy]; this factory brings VNC, SMB, and
 * other non-JSch transports to the same place (#149).
 */
class ProxySocketFactory(
    private val proxyType: String,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val connectTimeoutMs: Int = 30_000,
) : SocketFactory() {

    override fun createSocket(host: String, port: Int): Socket {
        val proxyAddress = InetSocketAddress(proxyHost, proxyPort)
        val javaProxy = when (proxyType.uppercase()) {
            "SOCKS5", "SOCKS4" -> Proxy(Proxy.Type.SOCKS, proxyAddress)
            "HTTP" -> Proxy(Proxy.Type.HTTP, proxyAddress)
            else -> throw IOException("Unsupported proxy type: $proxyType")
        }
        val socket = Socket(javaProxy)
        // Unresolved address so the proxy resolves the hostname remotely.
        socket.connect(InetSocketAddress.createUnresolved(host, port), connectTimeoutMs)
        return socket
    }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: java.net.InetAddress?,
        localPort: Int,
    ): Socket = createSocket(host, port)

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: host.toString(), port)

    override fun createSocket(
        address: java.net.InetAddress,
        port: Int,
        localAddress: java.net.InetAddress?,
        localPort: Int,
    ): Socket = createSocket(address, port)
}
