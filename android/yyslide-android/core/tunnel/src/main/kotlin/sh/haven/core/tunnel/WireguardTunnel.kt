package sh.haven.core.tunnel

import sh.haven.rclone.binding.wgbridge.Conn as NativeConn
import sh.haven.rclone.binding.wgbridge.Listener as NativeListener
import sh.haven.rclone.binding.wgbridge.TunnelHandle as NativeHandle
import sh.haven.rclone.binding.wgbridge.UDPConn as NativeUDPConn
import sh.haven.rclone.binding.wgbridge.Wgbridge
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * [Tunnel] implementation backed by wireguard-go + gVisor netstack via
 * the gomobile-bound `wgbridge` package (co-located in rclone-android so
 * both gomobile bindings ship in a single `libgojni.so` — see
 * rclone-android/go/wgbridge/).
 *
 * Holds one live native tunnel handle — created up-front from the user's
 * wg-quick config text, torn down on [close]. Dials are safe to call
 * concurrently; the native layer serialises as needed.
 */
class WireguardTunnel internal constructor(configText: String) : Tunnel {

    private val native: NativeHandle = try {
        Wgbridge.startTunnel(configText)
    } catch (e: Exception) {
        throw IOException("Failed to start WireGuard tunnel: ${e.message}", e)
    }

    @Volatile
    private var socksCached: SocksEndpoint? = null

    override fun dial(host: String, port: Int, timeoutMs: Int): TunneledConnection {
        val conn = try {
            native.dial(host, port.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            throw IOException("WireGuard dial $host:$port failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    override fun listenUdp(): TunneledDatagramSocket? {
        val udp = try {
            native.listenUDP()
        } catch (e: Exception) {
            throw IOException("WireGuard listenUDP failed: ${e.message}", e)
        }
        return NativeTunneledDatagramSocket(udp)
    }

    override fun socksAddress(): SocksEndpoint? {
        socksCached?.let { return it }
        return synchronized(this) {
            socksCached?.let { return@synchronized it }
            val (username, password) = newSocksCredentials()
            val port = try {
                native.startSocksListener(username, password).toInt()
            } catch (e: Exception) {
                throw IOException("WireGuard SOCKS5 listener failed: ${e.message}", e)
            }
            SocksEndpoint(
                address = InetSocketAddress("127.0.0.1", port),
                username = username,
                password = password,
            ).also { socksCached = it }
        }
    }

    override fun listenTcp(port: Int): TunneledServerSocket? {
        val ln = try {
            native.listenTCP(port.toLong())
        } catch (e: Exception) {
            throw IOException("WireGuard listenTCP $port failed: ${e.message}", e)
        }
        return NativeTunneledServerSocket(ln)
    }

    override fun localAddress(): String? = native.bindAddr().takeIf { it.isNotBlank() }

    override fun close() {
        try {
            native.close()
        } catch (_: Throwable) {
            // Best-effort teardown.
        }
    }
}

/** Wraps a native [NativeListener] as [TunneledServerSocket]. */
private class NativeTunneledServerSocket(
    private val listener: NativeListener,
) : TunneledServerSocket {

    override fun accept(): TunneledConnection {
        val conn = try {
            listener.accept()
        } catch (e: Exception) {
            throw IOException("WireGuard accept failed: ${e.message}", e)
        }
        return NativeTunneledConnection(conn)
    }

    override fun close() {
        try {
            listener.close()
        } catch (_: Throwable) {
            // Already-closed listeners raise here; ignore.
        }
    }
}

/**
 * Wraps a native [NativeConn] as [TunneledConnection]. The Go side
 * returns fresh byte slices from `read(size)` because gomobile copies
 * `[]byte` arguments across the JNI boundary — the caller's buffer
 * wouldn't be updated otherwise. The extra copy is immaterial for SSH
 * traffic (small frames, network round-trip dominates).
 */
private class NativeTunneledConnection(
    private val conn: NativeConn,
) : TunneledConnection {

    override val inputStream: InputStream = object : InputStream() {
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n == -1) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            val bytes = try {
                conn.read(len.toLong())
            } catch (_: Exception) {
                eof = true
                return -1
            }
            if (bytes == null || bytes.isEmpty()) {
                eof = true
                return -1
            }
            val n = minOf(bytes.size, len)
            System.arraycopy(bytes, 0, b, off, n)
            return n
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            try {
                conn.write(slice)
            } catch (e: Exception) {
                throw IOException("WireGuard write failed: ${e.message}", e)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Throwable) {
            // Already-closed connections raise here; ignore.
        }
    }
}

/**
 * Wraps a native [NativeUDPConn] as [TunneledDatagramSocket]. Identical
 * shape to [NativeTunneledConnection] but for UDP — gomobile returns a
 * fresh `[]byte` slice per receive, so we copy into the caller's buffer
 * here rather than trying to share storage across JNI.
 *
 * Timeouts: the Go side translates `timeoutMs <= 0` to "no deadline"
 * and `timeoutMs > 0` to a one-shot read deadline. When the deadline
 * fires, gomobile surfaces the underlying `net.OpError` here as a Java
 * exception whose message contains "i/o timeout" — we recognise that
 * and return null to match [TunneledDatagramSocket]'s timeout contract.
 */
private class NativeTunneledDatagramSocket(
    private val udp: NativeUDPConn,
) : TunneledDatagramSocket {

    override fun send(data: ByteArray, host: String, port: Int) {
        try {
            udp.writeTo(data, host, port.toLong())
        } catch (e: Exception) {
            throw IOException("WireGuard UDP writeTo $host:$port failed: ${e.message}", e)
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): ReceivedPacket? {
        val result = try {
            udp.readFrom(buf.size.toLong(), timeoutMs.toLong())
        } catch (e: Exception) {
            if (isTimeoutException(e)) return null
            throw IOException("WireGuard UDP readFrom failed: ${e.message}", e)
        }
        if (result == null) return null
        val bytes = result.data ?: return null
        val n = minOf(bytes.size, buf.size)
        System.arraycopy(bytes, 0, buf, 0, n)
        return ReceivedPacket(
            length = n,
            srcHost = result.fromHost,
            srcPort = result.fromPort.toInt(),
        )
    }

    override fun close() {
        try {
            udp.close()
        } catch (_: Throwable) { /* idempotent */ }
    }

    private fun isTimeoutException(e: Throwable): Boolean {
        if (e is InterruptedIOException) return true
        val msg = e.message ?: return false
        return msg.contains("i/o timeout", ignoreCase = true) ||
            msg.contains("deadline exceeded", ignoreCase = true)
    }
}
