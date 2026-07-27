package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.terminal.ScrollbackRing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalSessionManager"

/**
 * Manages active local terminal sessions.
 * Follows the same lifecycle pattern as MoshSessionManager.
 */
@Singleton
class LocalSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val prootManager: ProotManager,
    val desktopManager: DesktopManager,
    val guestServiceManager: GuestServiceManager,
    private val preferences: UserPreferencesRepository,
) {

    /**
     * Latest user-configured session manager (tmux/zellij/screen/byobu/none).
     * Cached from the preferences flow on a private scope so [buildCommand]
     * can read it synchronously. Defaults to NONE until the first emission.
     *
     * Why this matters: when Haven is killed (process crash, force-stop,
     * Android lifecycle), every PRoot child dies — including any agent
     * the user was running inside the local session. Wrapping the shell
     * in a tmux/zellij/screen session means the next time Haven launches
     * the same profile, `tmux new-session -A` re-attaches if the server
     * survived (it does in some lifecycles) or starts cleanly otherwise,
     * preserving scrollback and process state across restarts.
     */
    @Volatile
    private var sessionManager: UserPreferencesRepository.SessionManager =
        UserPreferencesRepository.SessionManager.NONE

    private val prefScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        prefScope.launch {
            preferences.sessionManager.collect { sessionManager = it }
        }
    }

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val localSession: LocalSession? = null,
        val useAndroidShell: Boolean = false,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    fun registerSession(profileId: String, label: String, useAndroidShell: Boolean = false): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                useAndroidShell = useAndroidShell,
            ))
        }
        return sessionId
    }

    /**
     * Mark a session as connected. The actual process starts when
     * [createTerminalSession] is called.
     */
    fun connectSession(sessionId: String) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = SessionState.Status.CONNECTED))
        }
    }

    /**
     * Returns the busybox sh argv for this session, optionally wrapped
     * in the user's chosen session manager. The wrapper uses
     * `command -v <bin>` so that picking tmux/zellij/etc without the
     * binary installed in the rootfs falls back to a plain login shell
     * instead of leaving the user with a dead PTY. The wrapper `exec`s
     * the session-manager binary so signals (SIGWINCH, SIGHUP) reach
     * tmux directly, not through an intermediate sh.
     *
     * When [plain] is true the user's `sessionManager` preference is
     * ignored entirely and a bare login shell is execed — needed for
     * agent-driven test sessions that depend on Haven's own scrollback
     * ring being filled (which a multiplexer's DECSTBM-based status bar
     * defeats).
     */
    private fun sessionManagerShellArgs(sessionName: String, plain: Boolean = false): Array<String> {
        // Use /bin/sh rather than /bin/busybox: Alpine's /bin/sh is a
        // symlink to busybox, Debian's is a symlink to dash, both
        // accept -l / -c with the same semantics. Hardcoding busybox
        // breaks any non-Alpine distro added under issue #162.
        if (plain) return arrayOf("/bin/sh", "-l")
        val mgr = sessionManager
        val template = mgr.command
        if (template == null) {
            return arrayOf("/bin/sh", "-l")
        }
        val sanitizedName = sessionName.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val cmd = template(sanitizedName)
        // First word of the command is the binary we test for. tmux,
        // zellij, screen, byobu — all single-word executables.
        val bin = cmd.substringBefore(' ')
        // Wrap in `command -v` so missing binaries don't break the
        // session. Login shell first so PATH/profile are set up.
        val wrapped = "if command -v $bin >/dev/null 2>&1; then exec $cmd; else exec /bin/sh -l; fi"
        return arrayOf("/bin/sh", "-l", "-c", wrapped)
    }

    /**
     * Build the shell command for a local session.
     * Uses proot if a rootfs is installed, otherwise falls back to /system/bin/sh.
     *
     * When [plain] is true the session-manager wrapper (tmux/zellij/screen/byobu)
     * is skipped and a bare login shell is execed — see
     * [sessionManagerShellArgs].
     */
    fun buildCommand(useAndroidShell: Boolean = false, plain: Boolean = false): Triple<String, Array<String>, Array<String>> {
        val prootBinary = prootManager.prootBinary

        return if (!useAndroidShell && prootBinary != null && prootManager.isRootfsInstalled) {
            // PRoot with the active distro's rootfs
            val rootfsDir = prootManager.activeRootfsDir

            // Ensure resolv.conf exists (Android doesn't have /etc/resolv.conf)
            val resolvConf = java.io.File(rootfsDir, "etc/resolv.conf")
            if (!resolvConf.exists() || resolvConf.length() == 0L) {
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
            val cmd = prootBinary
            // Append a session-manager wrapper if the user picked one
            // (tmux/zellij/screen/byobu). The wrapper falls back to a
            // plain login shell if the binary isn't installed in the
            // rootfs, so picking tmux without `apk add tmux` degrades
            // to today's behaviour rather than failing the session.
            val shellArgs: Array<String> = sessionManagerShellArgs("haven-local", plain = plain)
            val args = arrayOf(
                prootBinary,
                "-0",                    // fake root
                "--link2symlink",        // fix link() for X11 lock files
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/storage",
                "-b", "${context.cacheDir.absolutePath}:/tmp",
                "-w", "/root",
            ) + shellArgs
            val env = arrayOf(
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "SHELL=/bin/sh",
                "PROOT_TMP_DIR=${context.cacheDir.absolutePath}",
                "PROOT_LOADER=${java.io.File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so").absolutePath}",
            )
            Triple(cmd, args, env)
        } else {
            // Fallback: plain Android shell
            val cmd = "/system/bin/sh"
            val args = arrayOf(cmd, "-l")
            val env = arrayOf(
                "HOME=${context.filesDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PATH=/system/bin:/vendor/bin",
                "SHELL=/system/bin/sh",
                "TMPDIR=${context.cacheDir.absolutePath}",
            )
            Triple(cmd, args, env)
        }
    }

    /**
     * Create a [LocalSession] for a connected session.
     * The PTY process starts immediately and output flows via [onDataReceived].
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
        rows: Int = 24,
        cols: Int = 80,
        plain: Boolean = false,
    ): LocalSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.localSession != null) return null

        val (cmd, args, env) = buildCommand(session.useAndroidShell, plain = plain)

        val localSession = LocalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            command = cmd,
            args = args,
            env = env,
            onDataReceived = onDataReceived,
            onExited = { exitCode ->
                Log.d(TAG, "Session $sessionId process exited: $exitCode")
                // Mark DISCONNECTED and drop the dead LocalSession in one
                // update so sendInput / getActiveSession stop routing to a
                // closed fd (which would swallow writes and report a false
                // success). The agentScrollback ring is left intact so the
                // final output stays readable after the process exits.
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    existing.localSession?.close()
                    map + (sessionId to existing.copy(
                        status = SessionState.Status.DISCONNECTED,
                        localSession = null,
                    ))
                }
            },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(localSession = localSession))
        }

        return localSession
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.localSession == null
    }

    /**
     * The live [LocalSession] for [sessionId], or null if none is attached.
     * Distinct from [isReadyForTerminal]: this returns the session that's
     * *already* attached (e.g. an agent-side headless shell started by
     * `open_local_shell`), letting the UI tab adopt it instead of forking
     * a second PTY for the same session.
     */
    fun getActiveSession(sessionId: String): LocalSession? =
        _sessions.value[sessionId]?.localSession

    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.localSession?.close()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(localSession = null))
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
        agentScrollback.remove(sessionId)
        ioExecutor.execute {
            try {
                session.localSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    // --- Agent transport entry points -----------------------------------
    //
    // The MCP open_local_shell tool wants to (a) start a real PTY without
    // a UI tab on top of it and (b) read/write that PTY through stable
    // sessionId-keyed APIs that mirror the SSH side. The trio below
    // (agent scrollback ring + headless start + sendInput) provides that;
    // each piece is intentionally narrow so a future first-class "shell
    // session that renders nowhere" mode can subsume them cleanly.

    private val agentScrollback = ConcurrentHashMap<String, ScrollbackRing>()

    /**
     * Soft cap on the agent-scope mirror of recent stdout for headless
     * local shells. Matches [SshSessionManager]'s cap so behaviour is
     * uniform across transports.
     */
    private val agentScrollbackBytes = 256 * 1024

    /**
     * Spin up the PTY for [sessionId] without an attached UI surface,
     * mirroring stdout into [agentScrollback] so
     * [readAgentScrollback] can return what the agent would see.
     * Idempotent — if a [LocalSession] already exists for [sessionId]
     * (e.g. the user opened a terminal tab for it), this is a no-op.
     *
     * [extraOnData], when non-null, is invoked for every chunk of PTY
     * output alongside the agent scrollback ring. Lets a caller
     * (notably the MCP `open_local_shell` handler) tee the stream
     * into a [TerminalEmulator] without losing the byte-ring that
     * `read_terminal_scrollback` consumes.
     */
    fun startHeadlessShell(
        sessionId: String,
        extraOnData: ((ByteArray, Int, Int) -> Unit)? = null,
        plain: Boolean = false,
    ) {
        val session = _sessions.value[sessionId] ?: return
        if (session.localSession != null) return
        val ring = agentScrollback.computeIfAbsent(sessionId) {
            ScrollbackRing(agentScrollbackBytes)
        }
        val onData: (ByteArray, Int, Int) -> Unit = { data, off, len ->
            ring.append(data, off, len)
            extraOnData?.invoke(data, off, len)
        }
        val ls = createTerminalSession(sessionId, onData, plain = plain) ?: return
        // Default to a sensible PTY size; the UI will resize once a tab
        // attaches. 80x24 keeps line wrapping predictable for an agent
        // that's about to do `printf` glyph tests.
        ls.start(rows = 24, cols = 80)
    }

    /**
     * Send [text] as UTF-8 to the PTY for [sessionId]. Throws when no
     * session exists or no [LocalSession] is attached — the agent
     * transport surfaces those as JSON-RPC errors.
     */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No local session: $sessionId")
        val localSession = session.localSession
            ?: throw IllegalStateException(
                "Local session $sessionId has ended — open_local_shell again")
        // The reader thread may have hit EOF (process exited) a beat before
        // onExited cleared the reference; reject loudly rather than writing
        // into a closed fd that swallows the bytes and reports false success.
        if (!localSession.isAlive()) {
            throw IllegalStateException(
                "Local session $sessionId has ended — open_local_shell again")
        }
        localSession.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Suspend until the agent scrollback ring for [sessionId] has received
     * its first bytes (the shell printed its prompt/banner) or [timeoutMs]
     * elapses. Returns true if any output arrived. Lets `open_local_shell`
     * hand back a session that's immediately usable instead of racing the
     * asynchronously-started PTY reader thread.
     */
    suspend fun awaitFirstOutput(sessionId: String, timeoutMs: Long = 1500L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun hasOutput() = (agentScrollback[sessionId]?.totalBytesAppended ?: 0L) > 0L
        while (System.currentTimeMillis() < deadline) {
            if (hasOutput()) return true
            // A shell that exits instantly (e.g. a broken env) never prints a
            // prompt — stop waiting once it's no longer connected.
            val status = _sessions.value[sessionId]?.status
            if (status != null && status != SessionState.Status.CONNECTED) break
            delay(40)
        }
        return hasOutput()
    }

    /**
     * Read the most recent [maxBytes] of stdout from the agent-scope
     * ring for [sessionId], or null if no ring exists yet (no headless
     * or UI tab has run on this session).
     */
    fun readAgentScrollback(sessionId: String, maxBytes: Int): ByteArray? {
        val ring = agentScrollback[sessionId] ?: return null
        val full = ring.snapshot()
        return if (full.size <= maxBytes) full
        else full.copyOfRange(full.size - maxBytes, full.size)
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.localSession?.close()
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
                    session.localSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    /**
     * Send a VNC start command to an active PRoot session's PTY.
     */
    fun startVncInSession(profileId: String) {
        val session = _sessions.value.values
            .find { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?: return
        session.localSession?.sendInput(
            "vncserver :1 2>&1 &\n".toByteArray()
        )
        Log.d(TAG, "Sent VNC start command to session ${session.sessionId}")
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }
}
