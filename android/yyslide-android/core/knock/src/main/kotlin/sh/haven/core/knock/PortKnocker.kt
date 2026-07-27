package sh.haven.core.knock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a knock attempt. Carries the list of steps actually sent and timing. */
data class KnockResult(
    val sentSteps: Int,
    val totalDurationMs: Long,
    val error: Throwable? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * Sends a port-knock sequence to a host. Implementations open and immediately
 * close raw TCP/UDP sockets to each port in order — the SYN (TCP) or datagram
 * (UDP) is what the remote firewall watches for; we don't expect a response.
 *
 * Failures during knocking are returned in [KnockResult.error] rather than
 * thrown, so the caller can decide whether to abort the real connect or
 * continue regardless.
 */
interface PortKnocker {
    suspend fun knock(host: String, sequence: KnockSequence): KnockResult
}

@Singleton
class DefaultPortKnocker @Inject constructor() : PortKnocker {

    /** Per-knock connect attempt — short enough that we don't block the UI on a stale host. */
    private val perKnockTimeoutMs = 250

    /** Wait after the last packet so the firewall has time to install its rule. */
    private val postKnockSettleMs = 200L

    override suspend fun knock(host: String, sequence: KnockSequence): KnockResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            var sent = 0
            try {
                val addr = InetAddress.getByName(host)
                for ((index, step) in sequence.steps.withIndex()) {
                    sendOne(addr, step)
                    sent++
                    if (index < sequence.steps.lastIndex && sequence.interKnockDelayMs > 0) {
                        delay(sequence.interKnockDelayMs.toLong())
                    }
                }
                delay(postKnockSettleMs)
                KnockResult(sent, System.currentTimeMillis() - started)
            } catch (t: Throwable) {
                KnockResult(sent, System.currentTimeMillis() - started, t)
            }
        }

    private fun sendOne(addr: InetAddress, step: KnockStep) {
        when (step.protocol) {
            KnockStep.Protocol.TCP -> sendTcp(addr, step.port)
            KnockStep.Protocol.UDP -> sendUdp(addr, step.port)
        }
    }

    private fun sendTcp(addr: InetAddress, port: Int) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(addr, port), perKnockTimeoutMs)
        } catch (_: SocketTimeoutException) {
            // Expected: knockd doesn't accept the connection, the SYN is what matters.
        } catch (_: ConnectException) {
            // Expected: port is closed, the SYN reached the firewall.
        } catch (_: IOException) {
            // Other I/O errors (e.g. ECONNRESET) — also fine, the packet went out.
        } finally {
            try { socket.close() } catch (_: IOException) { /* ignore */ }
        }
    }

    private fun sendUdp(addr: InetAddress, port: Int) {
        val socket = DatagramSocket()
        try {
            // A single zero byte is enough for knockd to register the packet.
            val payload = byteArrayOf(0)
            socket.send(DatagramPacket(payload, payload.size, addr, port))
        } finally {
            socket.close()
        }
    }
}
