package sh.haven.core.reticulum

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Coroutines-first interface for Reticulum/rnsh transport.
 *
 * Backed by [NativeReticulumTransport] (rnsh-kt + reticulum-kt).
 * Collapses the session lifecycle into a single [openSession] call
 * that resolves the destination, establishes the Link, performs the
 * version handshake, and returns a [RnshShellSession].
 */
interface ReticulumTransport {

    /**
     * Initialise Reticulum. Call before any other method.
     *
     * @param configDir Writable directory for RNS config/identity storage
     * @param host Shared-instance or gateway host
     * @param port Shared-instance or gateway TCP port
     * @param ifacNetname IFAC network name for gateway isolation (optional)
     * @param ifacNetkey IFAC passphrase for gateway isolation (optional)
     * @param socketDialer Optional `(host, port, timeoutMs) -> Socket`
     *   used by the gateway TCP interface in place of a direct kernel
     *   dial. Routes Reticulum's TCP transport through a userspace
     *   tunnel (WireGuard / Tailscale) or SOCKS/HTTP proxy when the
     *   connection profile selects one (#149). Ignored in shared-
     *   instance mode where the local Sideband daemon owns the socket.
     * @return Haven's RNS identity hash (hex)
     */
    suspend fun init(
        configDir: String,
        host: String = "127.0.0.1",
        port: Int = 37428,
        ifacNetname: String? = null,
        ifacNetkey: String? = null,
        socketDialer: ((String, Int, Int) -> java.net.Socket)? = null,
    ): String

    /** Whether Reticulum has been initialised. */
    val isInitialised: Boolean

    /**
     * Open a shell session to an rnsh destination.
     * Resolves the destination, establishes a Link, performs the version
     * handshake, and requests command execution.
     *
     * @param destinationHash Hex-encoded rnsh destination hash
     * @param rows Terminal rows
     * @param cols Terminal columns
     * @return A live shell session
     * @throws Exception if resolution, Link, or handshake fails
     */
    suspend fun openSession(
        destinationHash: String,
        rows: Int = 24,
        cols: Int = 80,
    ): RnshShellSession

    /** Discovered rnsh destinations, sorted by hop count. */
    val discoveredDestinations: StateFlow<List<DiscoveredDestination>>

    /**
     * Request a path to a destination. Non-blocking — returns true if
     * path is already known, false if a request was sent.
     */
    suspend fun requestPath(destinationHashHex: String): Boolean

    /** Probe for Sideband's shared instance. Safe to call repeatedly. */
    suspend fun probeSideband(configDir: String): Boolean

    /** Close all sessions and shut down. */
    suspend fun closeAll()
}

/**
 * A live rnsh shell session.
 */
interface RnshShellSession : AutoCloseable {
    val sessionId: String

    /** Stdout and stderr data as received from the remote shell. */
    val output: Flow<ByteArray>

    /** Completes when the remote command exits. Value is the exit code. */
    val exitCode: CompletableDeferred<Int>

    /** Whether the session is still connected. */
    val isConnected: Boolean

    /** Send keyboard input (stdin) to the remote shell. */
    suspend fun sendInput(data: ByteArray)

    /** Send a window resize to the remote PTY. */
    suspend fun resize(rows: Int, cols: Int)

    /** Close the session. */
    override fun close()
}

/**
 * An rnsh destination discovered via announce.
 */
data class DiscoveredDestination(
    val hash: String,
    val hops: Int,
    val lastSeen: Long = System.currentTimeMillis(),
)
