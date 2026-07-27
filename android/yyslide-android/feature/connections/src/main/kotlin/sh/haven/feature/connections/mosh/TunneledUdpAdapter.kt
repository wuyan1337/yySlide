package sh.haven.feature.connections.mosh

import sh.haven.core.tunnel.TunneledDatagramSocket
import sh.haven.mosh.network.UdpReceivedPacket
import sh.haven.mosh.network.UdpSocketAdapter

/**
 * Bridges [TunneledDatagramSocket] (core/tunnel) onto
 * [UdpSocketAdapter] (mosh-kotlin) so [sh.haven.mosh.network.MoshConnection]
 * can route packets through a WireGuard or Tailscale tunnel without
 * mosh-kotlin gaining a tunnel dependency. Fix for #164.
 *
 * Lives in feature/connections rather than core/mosh because it's the
 * single integration point — putting it in core/mosh would create a
 * `core:mosh → core:tunnel → core:ssh → core:mosh` cycle (core/ssh
 * already depends on core/mosh for the session registry).
 */
class TunneledUdpAdapter(
    private val underlying: TunneledDatagramSocket,
) : UdpSocketAdapter {

    override fun send(data: ByteArray, host: String, port: Int) {
        underlying.send(data, host, port)
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): UdpReceivedPacket? {
        val received = underlying.receive(buf, timeoutMs) ?: return null
        return UdpReceivedPacket(
            length = received.length,
            srcHost = received.srcHost,
            srcPort = received.srcPort,
        )
    }

    override fun close() {
        underlying.close()
    }
}
