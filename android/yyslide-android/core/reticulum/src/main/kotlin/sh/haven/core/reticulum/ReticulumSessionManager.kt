package sh.haven.core.reticulum

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReticulumSessionManager"

/**
 * Manages active Reticulum/rnsh sessions across the app.
 * Parallel to [sh.haven.core.ssh.SshSessionManager] but without
 * reconnect, SFTP, or session manager (tmux/zellij) logic.
 *
 * Uses the coroutines-first [ReticulumTransport] interface backed by
 * the native rnsh-kt bridge.
 */
@Singleton
class ReticulumSessionManager @Inject constructor(
    private val transport: ReticulumTransport,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val destinationHash: String,
        val reticulumSession: ReticulumSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("rns-session-mgr")
    )

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    /**
     * Register a new session. Returns the generated sessionId.
     */
    fun registerSession(profileId: String, label: String, destinationHash: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                destinationHash = destinationHash,
            ))
        }
        return sessionId
    }

    /**
     * Connect a registered session. Suspend function — call from a
     * coroutine on Dispatchers.IO or similar.
     *
     * Initialises Reticulum if needed, resolves the destination, creates
     * a shell session. Throws on failure.
     */
    suspend fun connectSession(
        sessionId: String,
        configDir: String,
        host: String,
        port: Int,
        ifacNetname: String? = null,
        ifacNetkey: String? = null,
        socketDialer: ((String, Int, Int) -> java.net.Socket)? = null,
    ) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        // Init Reticulum (idempotent — safe to call multiple times)
        Log.w(TAG, "initReticulum: host=$host port=$port ifac=${ifacNetname != null} tunnel=${socketDialer != null}")
        val identityHash = transport.init(configDir, host, port, ifacNetname, ifacNetkey, socketDialer)
        Log.w(TAG, "initReticulum OK, identity=$identityHash")

        // Open shell session (resolves destination + Link + handshake)
        Log.w(TAG, "Opening session to ${session.destinationHash}...")
        val shellSession = transport.openSession(
            destinationHash = session.destinationHash,
        )

        // Store the shell session reference for terminal wiring
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
            ))
        }

        // Stash the shell session so createTerminalSession can use it
        shellSessions[sessionId] = shellSession
        Log.w(TAG, "Session $sessionId connected")
    }

    // Temporary stash for shell sessions between connect and terminal creation
    private val shellSessions = mutableMapOf<String, RnshShellSession>()

    /**
     * Create a [ReticulumSession] for a connected session.
     * Returns the session, or null if not ready.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): ReticulumSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.reticulumSession != null) return null

        val shellSession = shellSessions.remove(sessionId) ?: return null

        val reticulumSession = ReticulumSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            shellSession = shellSession,
            onDataReceived = onDataReceived,
            onDisconnected = { _ ->
                Log.d(TAG, "Session $sessionId disconnected")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(reticulumSession = reticulumSession))
        }

        return reticulumSession
    }

    /**
     * Detach a terminal session without closing the underlying rnsh session.
     */
    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.reticulumSession?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(reticulumSession = null))
        }
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.reticulumSession == null
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
        shellSessions.remove(sessionId)
        scope.launch {
            try {
                session.reticulumSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        scope.launch {
            toRemove.forEach { session ->
                try {
                    session.reticulumSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        shellSessions.clear()
        scope.launch {
            snapshot.forEach { session ->
                try {
                    session.reticulumSession?.close()
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
