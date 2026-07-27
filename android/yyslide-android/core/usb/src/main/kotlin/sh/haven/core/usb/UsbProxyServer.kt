package sh.haven.core.usb

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Serves the [UsbProxyProtocol] over an abstract-namespace `LocalSocket` so a
 * guest shim inside the proot can drive a brokered USB device.
 *
 * The socket lives in the abstract namespace (`\0haven-usb`), which is part of
 * the network namespace. proot does not isolate the network namespace, so the
 * proot tracee shares it with the Android app and can `connect()` to the same
 * abstract name — that reachability is the Slice-2 gate. (If it ever fails on
 * some device, the fallback is a filesystem-path socket under the cacheDir
 * `/tmp` bind; the protocol is transport-agnostic.)
 *
 * One server instance proxies one opened device, identified by [deviceName].
 * Every accepted connection issues requests against that device through
 * [UsbBroker]; concurrent guest opens (e.g. udev probe + the app) all multiplex
 * onto the same cached [android.hardware.usb.UsbDeviceConnection].
 */
@Singleton
class UsbProxyServer @Inject constructor(
    private val broker: UsbBroker,
) {
    @Volatile private var serverSocket: LocalServerSocket? = null
    @Volatile private var deviceName: String? = null
    @Volatile private var boundSocketName: String = ""
    @Volatile private var sessionToken: ByteArray = ByteArray(0)
    private var acceptThread: Thread? = null
    private val clientSlots = Semaphore(MAX_CLIENTS, true)
    private val activeClients = Collections.synchronizedSet(mutableSetOf<LocalSocket>())

    /** True while a proxy socket is bound. */
    val isRunning: Boolean get() = serverSocket != null

    /** The abstract socket name a guest connects to. */
    val socketName: String get() = boundSocketName

    /** Per-start bearer token; hand only to the explicitly attached guest. */
    val authToken: String
        get() = sessionToken.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Bind the proxy for [deviceName] (which must already be opened via
     * [UsbBroker.openDevice]). Idempotent for the same device; rebinds for a
     * different one. Returns the abstract socket name to hand the guest.
     */
    @Synchronized
    fun start(deviceName: String): String {
        if (serverSocket != null && this.deviceName == deviceName) return boundSocketName
        stop()
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val name = "$SOCKET_NAME_PREFIX-${random.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val socket = LocalServerSocket(name)
        serverSocket = socket
        this.deviceName = deviceName
        boundSocketName = name
        sessionToken = token
        acceptThread = thread(name = "haven-usb-proxy", isDaemon = true) {
            Log.i(TAG, "USB proxy listening on a randomized abstract socket for $deviceName")
            while (serverSocket === socket) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (serverSocket === socket) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                if (!clientSlots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                activeClients += client
                thread(name = "haven-usb-conn", isDaemon = true) {
                    try {
                        serve(client, deviceName, token)
                    } finally {
                        activeClients -= client
                        clientSlots.release()
                    }
                }
            }
        }
        return name
    }

    @Synchronized
    fun stop() {
        serverSocket?.let { runCatching { it.close() } }
        synchronized(activeClients) { activeClients.toList() }.forEach { runCatching { it.close() } }
        activeClients.clear()
        serverSocket = null
        deviceName = null
        boundSocketName = ""
        sessionToken.fill(0)
        sessionToken = ByteArray(0)
        acceptThread = null
    }

    private fun serve(client: LocalSocket, device: String, expectedToken: ByteArray) {
        client.use { sock ->
            sock.soTimeout = CLIENT_TIMEOUT_MS
            val input = DataInputStream(sock.inputStream)
            val output = DataOutputStream(sock.outputStream)
            val auth = runCatching { UsbProxyProtocol.readRequest(input) }.getOrNull()
            if (auth !is UsbProxyProtocol.Request.Auth ||
                !MessageDigest.isEqual(auth.token, expectedToken)
            ) {
                Log.w(TAG, "Rejected unauthenticated USB proxy client")
                return
            }
            while (true) {
                val req = try {
                    UsbProxyProtocol.readRequest(input)
                } catch (e: Exception) {
                    Log.w(TAG, "read request failed: ${e.message}")
                    break
                } ?: break
                val resp = handle(device, req)
                if (resp == null) break // CLOSE
                try {
                    output.write(UsbProxyProtocol.encodeResponse(resp))
                    output.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "write response failed: ${e.message}")
                    break
                }
            }
        }
    }

    /** @return the response, or null for CLOSE (ends the connection). */
    private fun handle(device: String, req: UsbProxyProtocol.Request): UsbProxyProtocol.Response? =
        try {
            when (req) {
                is UsbProxyProtocol.Request.Auth -> UsbProxyProtocol.Response(-1, ByteArray(0))
                UsbProxyProtocol.Request.GetDescriptors -> {
                    val raw = broker.rawDescriptors(device)
                    UsbProxyProtocol.Response(raw.size, raw)
                }
                is UsbProxyProtocol.Request.Control -> {
                    val r = broker.controlTransfer(
                        device, req.requestType, req.request, req.value, req.index,
                        req.data, req.length, req.timeoutMs,
                    )
                    UsbProxyProtocol.Response(r.bytesTransferred, r.data)
                }
                is UsbProxyProtocol.Request.Bulk -> {
                    val r = broker.bulkTransfer(device, req.endpoint, req.data, req.length, req.timeoutMs)
                    UsbProxyProtocol.Response(r.bytesTransferred, r.data)
                }
                UsbProxyProtocol.Request.Close -> null
            }
        } catch (e: Exception) {
            // Surface as a negative status the shim maps to errno; -1 is generic.
            Log.w(TAG, "request $req failed: ${e.message}")
            UsbProxyProtocol.Response(-1, ByteArray(0))
        }

    companion object {
        private const val TAG = "UsbProxyServer"
        private const val SOCKET_NAME_PREFIX: String = "yyslide-usb"
        private const val MAX_CLIENTS = 4
        private const val CLIENT_TIMEOUT_MS = 30_000
    }
}
