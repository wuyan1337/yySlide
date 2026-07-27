package sh.haven.app.reticulum

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.interfaces.tcp.TCPClientInterface
import network.reticulum.interfaces.toRef
import network.reticulum.transport.RichAnnounceHandler
import network.reticulum.transport.Transport
import sh.haven.core.reticulum.DiscoveredDestination
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.reticulum.RnshShellSession
import tech.torlando.rnsh.session.InitiatorSession
import tech.torlando.rnsh.session.ShellSession
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NativeReticulumTransport"

/**
 * Native Kotlin implementation of [ReticulumTransport] backed by
 * reticulum-kt + rnsh-kt.
 *
 * Supports two init modes:
 * - Sideband shared-instance client (localhost:37428)
 * - Direct TCP gateway to a remote host
 */
@Singleton
class NativeReticulumTransport @Inject constructor() : ReticulumTransport {

    @Volatile
    private var initialised = false

    override val isInitialised: Boolean get() = initialised

    private val _discovered = MutableStateFlow<List<DiscoveredDestination>>(emptyList())
    override val discoveredDestinations: StateFlow<List<DiscoveredDestination>> =
        _discovered.asStateFlow()

    private var clientIdentity: Identity? = null

    /** Announce handler that collects rnsh destinations into the StateFlow. */
    private val rnshAnnounceHandler = object : RichAnnounceHandler {
        override fun handleAnnounceWithContext(
            destinationHash: ByteArray,
            announcedIdentity: Identity,
            appData: ByteArray?,
            hops: Int,
            receivingInterfaceName: String?,
            matchedAspect: String?,
        ): Boolean {
            val hash = destinationHash.joinToString("") { "%02x".format(it) }
            Log.d(TAG, "rnsh announce: $hash (${hops} hops, via $receivingInterfaceName)")

            val dest = DiscoveredDestination(
                hash = hash,
                hops = hops,
            )

            _discovered.value = (_discovered.value
                .filter { it.hash != hash } + dest)
                .sortedBy { it.hops }

            return true
        }
    }

    override suspend fun init(
        configDir: String,
        host: String,
        port: Int,
        ifacNetname: String?,
        ifacNetkey: String?,
        socketDialer: ((String, Int, Int) -> java.net.Socket)?,
    ): String = withContext(Dispatchers.IO) {
        if (initialised) {
            return@withContext clientIdentity?.hexHash ?: "already-initialised"
        }

        Log.d(TAG, "init: configDir=$configDir, host=$host, port=$port")
        File(configDir).mkdirs()

        val isSideband = host in listOf("127.0.0.1", "localhost", "::1") && port == 37428

        if (isSideband) {
            // Sideband shared-instance client mode
            Reticulum.setLocalClientFactory { p, h ->
                LocalClientInterface("Sideband", tcpPort = p, tcpHost = h)
            }
            Reticulum.start(
                configDir = configDir,
                connectToSharedInstance = true,
                sharedInstancePort = port,
            )
        } else {
            // Direct TCP gateway mode
            Reticulum.start(configDir = configDir)

            val tcpClient = TCPClientInterface(
                name = "Gateway $host:$port",
                targetHost = host,
                targetPort = port,
                ifacNetname = ifacNetname,
                ifacNetkey = ifacNetkey,
                socketDialer = socketDialer,
            )
            Reticulum.getInstance().addInterface(tcpClient)
            Transport.registerInterface(tcpClient.toRef())
            tcpClient.start()

            // Wait for TCP connection
            val deadline = System.currentTimeMillis() + 10_000
            while (!tcpClient.online.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            if (!tcpClient.online.get()) {
                Log.w(TAG, "TCP connection to $host:$port not established within 10s")
            }
        }

        clientIdentity = Identity.create()

        // Register rnsh announce handler for destination discovery
        Transport.registerAnnounceHandler(
            handler = rnshAnnounceHandler,
            aspectFilter = "rnsh",
        )
        Log.d(TAG, "Registered rnsh announce handler")

        initialised = true
        val hexHash = clientIdentity?.hexHash ?: ""
        Log.d(TAG, "init complete, identity=$hexHash")
        hexHash
    }

    override suspend fun openSession(
        destinationHash: String,
        rows: Int,
        cols: Int,
    ): RnshShellSession = withContext(Dispatchers.IO) {
        check(initialised) { "Reticulum not initialised" }

        val destHash = hexToBytes(destinationHash)

        // Wait for path
        Log.d(TAG, "Requesting path to $destinationHash...")
        Transport.requestPath(destHash)
        val deadline = System.currentTimeMillis() + 20_000
        while (!Transport.hasPath(destHash)) {
            if (System.currentTimeMillis() > deadline) {
                throw RuntimeException(
                    "Could not resolve destination $destinationHash within 20s"
                )
            }
            Thread.sleep(250)
        }
        Log.d(TAG, "Path found for $destinationHash")

        // Create and execute rnsh session
        val session = InitiatorSession(
            destinationHash = destHash,
            clientIdentity = clientIdentity,
        )

        val shell = session.execute(rows = rows, cols = cols)
        Log.d(TAG, "Session opened to $destinationHash")

        NativeShellSession(
            sessionId = UUID.randomUUID().toString(),
            shell = shell,
        )
    }

    override suspend fun requestPath(destinationHashHex: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!initialised) return@withContext false
            val destHash = hexToBytes(destinationHashHex)
            if (Transport.hasPath(destHash)) {
                true
            } else {
                Transport.requestPath(destHash)
                false
            }
        }

    override suspend fun probeSideband(configDir: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Reticulum.isSharedInstanceRunning()
            } catch (e: Exception) {
                Log.e(TAG, "probeSideband failed", e)
                false
            }
        }

    override suspend fun closeAll() = withContext(Dispatchers.IO) {
        try {
            Reticulum.stop()
        } catch (e: Exception) {
            Log.e(TAG, "closeAll failed", e)
        }
        initialised = false
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Shell session backed by rnsh-kt's native [ShellSession].
 */
private class NativeShellSession(
    override val sessionId: String,
    private val shell: ShellSession,
) : RnshShellSession {

    override val output: Flow<ByteArray> = shell.output

    override val exitCode: CompletableDeferred<Int> = shell.exitCode

    override val isConnected: Boolean
        get() = !exitCode.isCompleted

    override suspend fun sendInput(data: ByteArray) {
        shell.sendInput(data)
    }

    override suspend fun resize(rows: Int, cols: Int) {
        shell.resize(rows, cols)
    }

    override fun close() {
        shell.close()
    }
}
