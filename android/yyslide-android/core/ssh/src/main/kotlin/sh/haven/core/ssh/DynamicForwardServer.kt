package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "DynamicForward"

/**
 * SOCKS5 proxy server that tunnels each accepted connection through an SSH
 * `direct-tcpip` channel. This is Haven's equivalent of OpenSSH `ssh -D`.
 *
 * Supports:
 *   - SOCKS5 (VER=5) with no authentication (METHOD=0)
 *   - CONNECT command only (no BIND, no UDP associate)
 *   - IPv4 (ATYP=1), domain name (ATYP=3), IPv6 (ATYP=4) destinations
 *
 * Not supported:
 *   - SOCKS4 / SOCKS4a (clients must use SOCKS5)
 *   - Username/password authentication
 *
 * Usage:
 *   val server = DynamicForwardServer(session, "127.0.0.1", 1080)
 *   val boundPort = server.start()
 *   // ... later ...
 *   server.close()
 */
class DynamicForwardServer(
    private val session: Session,
    private val bindAddress: String,
    private val requestedPort: Int,
) : Closeable {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /** The port the server is actually bound to (may differ from requested if 0). */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /**
     * Start the server. Blocks briefly to bind, then returns the bound port.
     * Accept loop runs on a daemon thread.
     */
    fun start(): Int {
        val ss = ServerSocket()
        // SO_REUSEADDR so restart after crash doesn't hit TIME_WAIT
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, requestedPort))
        serverSocket = ss
        running = true
        Log.i(TAG, "SOCKS5 server listening on $bindAddress:${ss.localPort}")

        thread(name = "socks5-accept-${ss.localPort}", isDaemon = true) {
            while (running) {
                try {
                    val client = ss.accept()
                    thread(name = "socks5-client", isDaemon = true) {
                        handleClient(client)
                    }
                } catch (_: IOException) {
                    // Socket closed — expected on shutdown
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Accept failed: ${e.message}")
                    break
                }
            }
            Log.i(TAG, "SOCKS5 accept loop exited on port ${ss.localPort}")
        }
        return ss.localPort
    }

    override fun close() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            // --- Greeting: [VER, NMETHODS, METHODS...] ---
            val ver = input.readUnsignedByte()
            if (ver != 5) {
                Log.w(TAG, "Unsupported SOCKS version: $ver")
                client.close()
                return
            }
            val nmethods = input.readUnsignedByte()
            val methods = ByteArray(nmethods)
            input.readFully(methods)
            // Respond: [VER=5, METHOD=0 (no auth)]
            // We don't support auth, so just pick NO_AUTH if offered
            val noAuthOffered = methods.any { it.toInt() == 0x00 }
            if (!noAuthOffered) {
                output.write(byteArrayOf(0x05, 0xFF.toByte())) // NO_ACCEPTABLE_METHODS
                output.flush()
                client.close()
                return
            }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // --- Request: [VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT] ---
            val ver2 = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val atyp = input.readUnsignedByte()
            if (ver2 != 5 || cmd != 1) {
                // Only CONNECT supported
                writeReply(output, 0x07) // command not supported
                client.close()
                return
            }

            val destHost: String = when (atyp) {
                0x01 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: "0.0.0.0"
                }
                0x03 -> { // Domain name
                    val len = input.readUnsignedByte()
                    val name = ByteArray(len)
                    input.readFully(name)
                    String(name, Charsets.US_ASCII)
                }
                0x04 -> { // IPv6
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress ?: "::"
                }
                else -> {
                    writeReply(output, 0x08) // address type not supported
                    client.close()
                    return
                }
            }
            val destPort = input.readUnsignedShort()
            Log.d(TAG, "SOCKS5 CONNECT $destHost:$destPort")

            // --- Open direct-tcpip channel through the SSH session ---
            val channel: ChannelDirectTCPIP = try {
                session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            } catch (e: Exception) {
                Log.w(TAG, "openChannel failed: ${e.message}")
                writeReply(output, 0x01) // general SOCKS server failure
                client.close()
                return
            }
            channel.setHost(destHost)
            channel.setPort(destPort)
            // Pipe streams through the channel
            val channelIn = channel.inputStream
            val channelOut = channel.outputStream

            try {
                channel.connect(15000)
            } catch (e: Exception) {
                Log.w(TAG, "channel connect failed for $destHost:$destPort: ${e.message}")
                val code = when {
                    e.message?.contains("refused", ignoreCase = true) == true -> 0x05
                    e.message?.contains("unreachable", ignoreCase = true) == true -> 0x03
                    else -> 0x01
                }
                writeReply(output, code)
                try { channel.disconnect() } catch (_: Exception) {}
                client.close()
                return
            }

            // Success reply
            writeReply(output, 0x00)

            // Clear timeout now that the tunnel is established — long-lived
            // connections like SSH-in-SSH or IMAP IDLE will sit idle for hours
            client.soTimeout = 0

            // Bridge streams bidirectionally
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val upstream = thread(name = "socks5-upstream", isDaemon = true) {
                try {
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = clientIn.read(buf)
                        if (n < 0) break
                        channelOut.write(buf, 0, n)
                        channelOut.flush()
                    }
                } catch (_: Exception) { /* bridge torn down */ }
                try { channel.disconnect() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
            // Downstream in current thread so the client connection lives
            // until the SSH channel closes
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = channelIn.read(buf)
                    if (n < 0) break
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                }
            } catch (_: Exception) { /* bridge torn down */ }
            try { channel.disconnect() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            upstream.join(1000)
        } catch (e: Exception) {
            Log.w(TAG, "Client handler error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Write a SOCKS5 reply with the given REP code and a 0.0.0.0:0 BND. */
    private fun writeReply(output: DataOutputStream, rep: Int) {
        // [VER=5, REP, RSV=0, ATYP=1, BND.ADDR=0.0.0.0, BND.PORT=0]
        output.write(byteArrayOf(
            0x05, rep.toByte(), 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
        ))
        output.flush()
    }
}
