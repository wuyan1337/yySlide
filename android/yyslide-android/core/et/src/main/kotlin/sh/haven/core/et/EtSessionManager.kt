package sh.haven.core.et

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EtSessionManager"

/**
 * Manages active Eternal Terminal sessions across the app.
 * Same pattern as MoshSessionManager: simple connect/disconnect lifecycle.
 *
 * ET bootstraps over SSH then maintains a persistent TCP connection
 * (default port 2022).
 */
@Singleton
class EtSessionManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val serverHost: String = "",
        val etPort: Int = 2022,
        val clientId: String = "",
        val passkey: String = "",
        val etSession: EtSession? = null,
        /** Shell command to run after ET connects (e.g. session manager attach). */
        val initialCommand: String? = null,
        /** SSH client kept alive from bootstrap for SFTP access (opaque Closeable). */
        val sshClient: Closeable? = null,
        /** Buffer for capturing transport logs when verbose logging is enabled. */
        val verboseBuffer: ConcurrentLinkedQueue<String>? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "et-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    /**
     * Register a new session. Returns the generated sessionId.
     */
    fun registerSession(profileId: String, label: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    /**
     * Connect a registered session.
     * Stores connection parameters; the actual transport starts when
     * [createTerminalSession] is called.
     */
    fun connectSession(
        sessionId: String,
        serverHost: String,
        etPort: Int,
        clientId: String,
        passkey: String,
        sshClient: Closeable? = null,
        verboseBuffer: ConcurrentLinkedQueue<String>? = null,
    ) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        Log.d(TAG, "Connecting ET session: $serverHost:$etPort clientId=${clientId.take(6)}... (ssh kept alive: ${sshClient != null})")

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                serverHost = serverHost,
                etPort = etPort,
                clientId = clientId,
                passkey = passkey,
                sshClient = sshClient,
                verboseBuffer = verboseBuffer,
            ))
        }
    }

    /**
     * Get the SSH client for a connected ET profile (for SFTP access).
     * Returns the opaque Closeable — caller must cast to SshClient.
     */
    fun getSshClientForProfile(profileId: String): Closeable? {
        return _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            .firstOrNull { it.sshClient != null }
            ?.sshClient
    }

    /**
     * Create an [EtSession] for a connected session.
     * The transport starts immediately and terminal output flows via [onDataReceived].
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): EtSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.etSession != null) return null
        if (session.serverHost.isEmpty()) return null

        val etSession = EtSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            serverHost = session.serverHost,
            etPort = session.etPort,
            clientId = session.clientId,
            passkey = session.passkey,
            onDataReceived = onDataReceived,
            onDisconnected = { _ ->
                // ET has no built-in reconnect like Mosh's UDP roaming —
                // a TCP drop is terminal at the transport layer. App-
                // level reconnect would re-establish ET with the stored
                // (serverHost, etPort, clientId, passkey) without re-
                // running the SSH bootstrap (ET server keeps state
                // keyed by clientId), but it needs an EtSession.reconnect()
                // method that swaps the underlying transport without
                // breaking the terminal callback wiring — analogous to
                // SshSessionManager's TerminalSession.reconnect path.
                // Not yet implemented; the per-profile autoReconnect flag
                // from #150 has no effect on ET until that lands.
                // Track in #150 phase notes.
                Log.d(TAG, "ET session $sessionId disconnected — TCP drop terminal; user must reconnect manually")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
            verboseBuffer = session.verboseBuffer,
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(etSession = etSession))
        }

        return etSession
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.etSession == null &&
            session.serverHost.isNotEmpty() &&
            session.clientId.isNotEmpty()
    }

    /**
     * Detach a terminal session without killing the ET transport.
     */
    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.etSession?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(etSession = null))
        }
    }

    fun setInitialCommand(sessionId: String, command: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(initialCommand = command))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute {
            try {
                session.sshClient?.close()
                session.etSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.sshClient?.close()
                    session.etSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { session ->
                try {
                    session.sshClient?.close()
                    session.etSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.status }
        if (statuses.isEmpty()) return null
        return when {
            SessionState.Status.CONNECTED in statuses -> SessionState.Status.CONNECTED
            SessionState.Status.CONNECTING in statuses -> SessionState.Status.CONNECTING
            SessionState.Status.ERROR in statuses -> SessionState.Status.ERROR
            else -> SessionState.Status.DISCONNECTED
        }
    }
}
