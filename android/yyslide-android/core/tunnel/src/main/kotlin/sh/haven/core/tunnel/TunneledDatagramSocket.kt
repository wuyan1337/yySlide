package sh.haven.core.tunnel

/**
 * A UDP socket routed through a [Tunnel]. Mirrors the subset of
 * [java.net.DatagramSocket] that Mosh (and any other unconnected-UDP
 * transport) actually uses: send-to / receive-from / close, with a
 * per-call read timeout.
 *
 * Implementations are unconnected — every [send] carries an explicit
 * destination, and [receive] surfaces the source of each datagram.
 * Mosh's "client roams, server identifies by SSP nonce" model maps
 * directly onto this; if a future caller wants connected semantics, it
 * can pin the destination at the wrapper level rather than down here.
 *
 * Not thread-safe by contract. Callers that send and receive from
 * different threads (Mosh's send/receive coroutines) must coordinate
 * externally — `MoshConnection` already does this via its `socketLock`.
 */
interface TunneledDatagramSocket {
    /**
     * Send [data] to `host:port` through the tunnel. host must be a
     * literal IP — the bridge layer rejects hostnames so DNS lookups
     * can't happen on the per-packet path. Mosh always knows the
     * server IP from the SSH bootstrap's MOSH CONNECT line, so this
     * is never a constraint in practice.
     */
    fun send(data: ByteArray, host: String, port: Int)

    /**
     * Block until a datagram arrives or [timeoutMs] expires.
     *
     * Returns `null` on timeout — the same convention
     * [java.net.DatagramSocket.receive] communicates via
     * [java.net.SocketTimeoutException], translated to null here so the
     * Mosh receive loop doesn't have to catch in two places. `timeoutMs`
     * `<= 0` blocks indefinitely.
     *
     * On a successful receive, the caller-supplied [buf] is populated
     * from index 0 with up to [ReceivedPacket.length] bytes. If the
     * datagram is larger than [buf] it is truncated — same as
     * [java.net.DatagramSocket] with an under-sized [java.net.DatagramPacket].
     *
     * Throws on transport failure (socket closed, tunnel torn down).
     */
    fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket?

    /** Close the socket. Idempotent. */
    fun close()
}

/**
 * Result of a successful [TunneledDatagramSocket.receive]. Mirrors the
 * fields of [java.net.DatagramPacket] that callers actually read.
 */
data class ReceivedPacket(
    val length: Int,
    val srcHost: String,
    val srcPort: Int,
)
