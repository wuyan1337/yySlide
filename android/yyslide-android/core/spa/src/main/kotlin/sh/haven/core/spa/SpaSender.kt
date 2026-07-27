package sh.haven.core.spa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an SPA send. [error] is null on success; the packet length is bytes put on the wire. */
data class SpaResult(
    val packetLen: Int,
    val totalDurationMs: Long,
    val error: Throwable? = null,
) {
    val ok: Boolean get() = error == null
}

/**
 * Builds and sends a single fwknop SPA packet to a host, just before the real
 * connect. The sibling of [sh.haven.core.knock.PortKnocker]: failures are
 * returned in [SpaResult.error] rather than thrown, so the caller decides
 * whether to abort the connect.
 */
interface SpaSender {
    suspend fun send(host: String, config: SpaConfig): SpaResult
}

@Singleton
class DefaultSpaSender @Inject constructor() : SpaSender {

    /** Give fwknopd a moment to install the firewall rule before we connect. */
    private val postSendSettleMs = 1_000L

    override suspend fun send(host: String, config: SpaConfig): SpaResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                val resolvedIp =
                    if (config.allowMode == SpaConfig.AllowMode.RESOLVE) resolvePublicIp() else null
                val packet = FwknopPacket.build(config, resolvedIp = resolvedIp)
                val bytes = packet.toByteArray(Charsets.US_ASCII)
                val addr = InetAddress.getByName(host)
                sendUdp(addr, config.spaPort, bytes)
                delay(postSendSettleMs)
                SpaResult(bytes.size, System.currentTimeMillis() - started)
            } catch (t: Throwable) {
                SpaResult(0, System.currentTimeMillis() - started, t)
            }
        }

    private fun sendUdp(addr: InetAddress, port: Int, payload: ByteArray) {
        val socket = DatagramSocket()
        try {
            socket.send(DatagramPacket(payload, payload.size, addr, port))
        } finally {
            try { socket.close() } catch (_: IOException) { /* ignore */ }
        }
    }

    /** Resolve our public egress IP (like `fwknop -R`). Throws if it can't be determined. */
    private fun resolvePublicIp(): String {
        val conn = (URL(RESOLVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            require(body.isNotEmpty()) { "empty response from $RESOLVE_URL" }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val RESOLVE_URL = "https://api.ipify.org"
    }
}
