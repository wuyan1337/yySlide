package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "McpTunnelManager"

/**
 * Brings up a dedicated, headless SSH reverse tunnel that forwards the
 * device-local MCP HTTP listener (`127.0.0.1:<port>`) back to the
 * laptop running the MCP client, so the endpoint stays reachable as the
 * device's WiFi/hotspot address roams and across app restarts.
 *
 * This is decoupled from the user's interactive "near" terminal session
 * (which dies on every app restart and didn't reliably re-establish its
 * `-R`). The tunnel:
 *  - rides its own [SshSessionManager] session marked `headless` (no
 *    shell channel ever opened — see [SshSessionManager.registerSession]),
 *  - uses an unlimited auto-reconnect policy that also fires on network
 *    change, so it self-heals after roams and restarts,
 *  - installs the `-R` forward as a **critical** port forward, so a
 *    stale server-side bind surfaces as a failed (re)connect that retries
 *    rather than a silently forward-less "connected" session
 *    (`ExitOnForwardFailure` semantics — see
 *    [SshSessionManager.applyPortForwards]).
 *
 * Lifecycle is bound to the MCP endpoint toggle in `HavenApp`: [start]
 * on enable, [stop] on disable.
 *
 * Limitation: a headless tunnel can only authenticate non-interactively.
 * Endpoint profiles that require a biometric/passphrase unlock or a FIDO2
 * hardware tap are skipped (logged), since there's no UI to drive the
 * prompt from the foreground service.
 */
@Singleton
class McpTunnelManager @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val sshSessionManager: SshSessionManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val guestServiceManager: GuestServiceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var job: Job? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var currentPort: Int = 0

    /**
     * Establish (or re-establish) the reverse tunnel forwarding
     * `127.0.0.1:[mcpPort]` on the laptop back to the device. Idempotent:
     * a no-op if already running for the same port; restarts if the port
     * changed (e.g. the MCP server fell back from 8730 to 8731).
     */
    fun start(mcpPort: Int) = synchronized(lock) {
        if (job?.isActive == true && currentPort == mcpPort) return
        stopLocked()
        currentPort = mcpPort
        job = scope.launch {
            // Connect phase: retry with backoff until the tunnel + critical
            // forward are up (or the config is unusable).
            var delayMs = INITIAL_RETRY_MS
            var established = false
            while (isActive && !established) {
                when (establish(mcpPort)) {
                    Outcome.ESTABLISHED -> established = true
                    Outcome.FATAL -> return@launch // misconfig — retrying won't help
                    Outcome.RETRY -> {
                        delay(delayMs)
                        delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
                    }
                }
            }
            // Watchdog phase: a headless session has no shell channel, so the
            // usual channel-exit reconnect trigger never fires for it. Network
            // roams are caught by NetworkMonitor, but a silent keepalive
            // timeout / server-side close would otherwise go unnoticed. Poll
            // the session and kick a reconnect when the transport has dropped.
            //
            // TCP-liveness (client.isConnected) is necessary but not sufficient:
            // a *wedged* reverse forward — the endpoint sshd still LISTENs on the
            // bind port but its forwarded channel is stuck (send-q backed up after
            // a roam/restart) — leaves the session looking CONNECTED while MCP
            // clients hang with no response. Keepalive can't see it (the transport
            // is up). So additionally probe the forward end-to-end from the
            // endpoint and force a reconnect (which rebinds, self-healing any stale
            // holder) when it stops responding.
            val sid = sessionId ?: return@launch
            var probeFails = 0
            while (isActive) {
                delay(HEALTH_CHECK_MS)
                val s = sshSessionManager.getSession(sid) ?: return@launch
                when (s.status) {
                    SshSessionManager.SessionState.Status.CONNECTED -> {
                        if (!s.client.isConnected) {
                            Log.i(TAG, "MCP tunnel transport dropped — requesting reconnect")
                            sshSessionManager.updateStatus(
                                sid, SshSessionManager.SessionState.Status.DISCONNECTED,
                            )
                            sshSessionManager.requestReconnect(sid)
                            probeFails = 0
                        } else when (probeForward(s.client, mcpPort)) {
                            Probe.ALIVE -> probeFails = 0
                            Probe.SKIP -> { /* endpoint has no curl — can't probe */ }
                            Probe.DEAD -> {
                                probeFails++
                                Log.w(
                                    TAG,
                                    "MCP forward liveness probe failed " +
                                        "($probeFails/$PROBE_FAIL_THRESHOLD) on :$mcpPort",
                                )
                                if (probeFails >= PROBE_FAIL_THRESHOLD) {
                                    Log.w(TAG, "MCP forward wedged — forcing reconnect")
                                    probeFails = 0
                                    sshSessionManager.updateStatus(
                                        sid, SshSessionManager.SessionState.Status.DISCONNECTED,
                                    )
                                    sshSessionManager.requestReconnect(sid)
                                }
                            }
                        }
                    }
                    SshSessionManager.SessionState.Status.DISCONNECTED,
                    SshSessionManager.SessionState.Status.ERROR -> {
                        sshSessionManager.requestReconnect(sid)
                        probeFails = 0
                    }
                    else -> { /* CONNECTING / RECONNECTING — in progress */ }
                }
            }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /**
     * Multiplex any RUNNING guest-service ports that aren't already forwarded
     * onto the live tunnel session, without a reconnect. Called when a guest
     * service starts after the tunnel is already up. Additive only: a stopped
     * service's forward lingers (harmless — it just refuses connections) until
     * the next reconnect rebuilds the set from scratch in [establish].
     */
    fun refreshForwards() = synchronized(lock) {
        val sid = sessionId ?: return
        val s = sshSessionManager.getSession(sid) ?: return
        if (s.status != SshSessionManager.SessionState.Status.CONNECTED) return
        val already = s.activeForwards
            .filter { it.type == SshSessionManager.PortForwardType.REMOTE }
            .map { it.bindPort }
            .toSet()
        val toAdd = guestServiceManager.runningPorts().distinct()
            .filter { it != currentPort && it !in already }
            .map { p ->
                SshSessionManager.PortForwardInfo(
                    ruleId = "guest-mcp-$p",
                    type = SshSessionManager.PortForwardType.REMOTE,
                    bindAddress = "127.0.0.1",
                    bindPort = p,
                    targetHost = "127.0.0.1",
                    targetPort = p,
                    critical = false,
                )
            }
        if (toAdd.isNotEmpty()) {
            sshSessionManager.applyPortForwards(sid, toAdd)
            Log.i(TAG, "Multiplexed guest ports onto MCP tunnel: ${toAdd.map { it.bindPort }}")
        }
    }

    private fun stopLocked() {
        job?.cancel()
        job = null
        sessionId?.let { sid ->
            // Best-effort: release the server-side `-R` binds now, so a clean
            // re-enable / restart doesn't hit a stale bind. (A server only
            // reaps a dead client's forward on its own ClientAliveInterval, so
            // without this an immediate re-enable races the reaper. A hard
            // process kill skips this path and still relies on that reaper.)
            runCatching {
                val s = sshSessionManager.getSession(sid)
                val client = s?.client
                if (client != null && client.isConnected) {
                    s.activeForwards
                        .filter { it.type == SshSessionManager.PortForwardType.REMOTE }
                        .forEach { fwd -> runCatching { client.delPortForwardingR(fwd.bindPort) } }
                }
            }
            sshSessionManager.removeSession(sid)
        }
        sessionId = null
        currentPort = 0
    }

    private enum class Outcome { ESTABLISHED, RETRY, FATAL }

    private suspend fun establish(mcpPort: Int): Outcome {
        val profileId = preferencesRepository.mcpTunnelEndpointProfileId.first()
        if (profileId.isNullOrBlank()) {
            Log.i(TAG, "No MCP tunnel endpoint configured — endpoint is on-device only")
            return Outcome.FATAL
        }
        val profile = connectionRepository.getById(profileId)
        if (profile == null) {
            Log.w(TAG, "MCP tunnel endpoint profile $profileId not found")
            return Outcome.FATAL
        }
        if (!profile.isSsh) {
            Log.w(TAG, "MCP tunnel endpoint profile ${profile.label} is not SSH — ignoring")
            return Outcome.FATAL
        }
        val auth = resolveHeadlessAuth(profile)
        if (auth == null) {
            Log.w(
                TAG,
                "MCP tunnel endpoint ${profile.label} has no non-interactive auth " +
                    "(needs an unencrypted key or a stored password) — cannot tunnel headlessly",
            )
            connectionLogRepository.logEvent(
                profile.id, ConnectionLog.Status.FAILED,
                details = "MCP tunnel: no non-interactive auth available",
            )
            return Outcome.FATAL
        }

        val config = ConnectionConfig(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = auth,
            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
            addressFamily = ConnectionConfig.AddressFamily.valueOf(profile.addressFamilyEnum.name),
            // Unlimited self-healing: a headless tunnel that holds the MCP
            // forward should never give up while the endpoint is enabled.
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(
                autoReconnect = true,
                maxAttempts = 0,
                onNetworkChange = true,
            ),
        )

        val client = SshClient()
        val sid = sshSessionManager.registerSession(
            profileId = profile.id,
            label = "MCP tunnel: ${profile.label}",
            client = client,
            headless = true,
        )
        sessionId = sid
        return try {
            val hostKeyEntry = client.connect(config)
            // Silent TOFU — same policy the reconnect path uses: accept a
            // new host, abort on a changed key.
            when (val r = hostKeyVerifier.verify(hostKeyEntry)) {
                is HostKeyResult.Trusted -> { /* ok */ }
                is HostKeyResult.NewHost -> hostKeyVerifier.accept(r.entry)
                is HostKeyResult.KeyChanged -> {
                    Log.w(TAG, "Host key changed for ${profile.label} — refusing MCP tunnel")
                    cleanup(sid)
                    return Outcome.FATAL
                }
            }
            sshSessionManager.storeConnectionConfig(sid, config, SessionManager.NONE)
            sshSessionManager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)

            // The critical forward: Haven's own MCP endpoint. A bind failure
            // here fails the whole (re)connect and retries.
            val forwards = mutableListOf(
                SshSessionManager.PortForwardInfo(
                    ruleId = "mcp-reverse-tunnel",
                    type = SshSessionManager.PortForwardType.REMOTE,
                    bindAddress = "127.0.0.1",
                    bindPort = mcpPort,
                    targetHost = "127.0.0.1",
                    targetPort = mcpPort,
                    critical = true,
                    selfHealOnBindFailure = true,
                ),
            )
            // Multiplex any running guest MCP servers (e.g. a KiCad MCP) onto
            // the same headless session as **non-critical** forwards: a guest
            // crash or stale guest bind must never tear down Haven's own
            // tunnel. They ride the same activeForwards re-apply on reconnect,
            // so they inherit the tunnel's roam/restart durability for free.
            guestServiceManager.runningPorts().distinct()
                .filter { it != mcpPort }
                .forEach { p ->
                    forwards.add(
                        SshSessionManager.PortForwardInfo(
                            ruleId = "guest-mcp-$p",
                            type = SshSessionManager.PortForwardType.REMOTE,
                            bindAddress = "127.0.0.1",
                            bindPort = p,
                            targetHost = "127.0.0.1",
                            targetPort = p,
                            critical = false,
                            selfHealOnBindFailure = true,
                        ),
                    )
                }
            if (!sshSessionManager.applyPortForwards(sid, forwards)) {
                Log.w(TAG, "MCP -R $mcpPort failed to bind on ${profile.label} (stale bind?) — retrying")
                cleanup(sid)
                return Outcome.RETRY
            }
            val guestPorts = forwards.drop(1).map { it.bindPort }
            Log.i(
                TAG,
                "MCP reverse tunnel up: -R $mcpPort" +
                    (if (guestPorts.isNotEmpty()) " (+guest ${guestPorts.joinToString(",")})" else "") +
                    " via ${profile.label}",
            )
            connectionLogRepository.logEvent(
                profile.id, ConnectionLog.Status.CONNECTED,
                details = "MCP reverse tunnel (-R $mcpPort)",
            )
            Outcome.ESTABLISHED
        } catch (e: Exception) {
            Log.w(TAG, "MCP tunnel connect to ${profile.label} failed: ${e.message}")
            cleanup(sid)
            Outcome.RETRY
        }
    }

    private fun cleanup(sid: String) {
        sshSessionManager.removeSession(sid)
        if (sessionId == sid) sessionId = null
    }

    /**
     * Resolve a non-interactive [ConnectionConfig.AuthMethod] for the
     * endpoint, or null if the profile would require a UI prompt
     * (encrypted/biometric key, FIDO2 hardware key, or no credential at
     * all). FIDO2 (`sk-`) and encrypted keys are excluded deliberately.
     */
    private suspend fun resolveHeadlessAuth(profile: ConnectionProfile): ConnectionConfig.AuthMethod? {
        val keyId = profile.keyId
        if (keyId != null) {
            val key = sshKeyRepository.getById(keyId) ?: return null
            if (key.keyType.startsWith("sk-") || key.isEncrypted) return null
            val keyBytes = sshKeyRepository.getDecryptedKeyBytes(keyId) ?: return null
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = SshKeyExporter.toPem(keyBytes, key.keyType),
                certificateBytes = certBytes,
            )
        }
        // No explicit key — try every stored unencrypted, non-FIDO key.
        val usableKeys = sshKeyRepository.getAllDecrypted()
            .filter { !it.isEncrypted && !it.keyType.startsWith("sk-") }
        if (usableKeys.isNotEmpty()) {
            return ConnectionConfig.AuthMethod.PrivateKeys(
                keys = usableKeys.map { it.label to SshKeyExporter.toPem(it.privateKeyBytes, it.keyType) },
            )
        }
        val password = profile.sshPassword
        if (!password.isNullOrEmpty()) {
            return ConnectionConfig.AuthMethod.Password(password)
        }
        return null
    }

    /**
     * Probe the reverse forward end-to-end: ask the endpoint to HTTP-GET the
     * forwarded MCP port on its own loopback. A healthy `-R` round-trips back to
     * the device's MCP server and returns a status code fast (the server answers
     * 405/406 to a bare GET — any response proves the channel works); a wedged
     * forward hangs until the 4 s curl timeout, and a wedged *exec channel*
     * (transport itself stuck) is caught by [PROBE_TIMEOUT_MS] — both surface as
     * DEAD. SKIP when the endpoint lacks curl (we can't probe, so don't act).
     */
    private suspend fun probeForward(client: SshClient, port: Int): Probe {
        val cmd =
            "if command -v curl >/dev/null 2>&1; then " +
                "code=\$(curl -s -o /dev/null -m 4 -w '%{http_code}' " +
                "http://127.0.0.1:$port/mcp 2>/dev/null); rc=\$?; " +
                "echo \"P:\${code:-000}:\$rc\"; else echo P:NOCURL:127; fi"
        val stdout = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { client.execCommand(cmd).stdout }.getOrNull()
        } ?: return Probe.DEAD
        return interpretProbeLine(stdout)
    }

    private companion object {
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
        const val HEALTH_CHECK_MS = 15_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_FAIL_THRESHOLD = 2
    }
}

/** Result of a reverse-forward liveness probe (see [McpTunnelManager.probeForward]). */
internal enum class Probe { ALIVE, DEAD, SKIP }

/**
 * Interpret the endpoint probe's stdout (`P:<httpcode>:<rc>`, or `P:NOCURL:127`
 * when the endpoint has no curl). ALIVE iff the forward round-tripped to an HTTP
 * status (any 3-digit code other than 000); SKIP when we couldn't probe; DEAD
 * otherwise (curl timeout / connection refused → wedged or dead forward). Pure +
 * `internal` so the watchdog's decision logic is unit-testable.
 */
internal fun interpretProbeLine(stdout: String): Probe {
    val line = stdout.lineSequence().map { it.trim() }
        .firstOrNull { it.startsWith("P:") } ?: return Probe.SKIP
    val code = line.removePrefix("P:").substringBefore(":")
    if (code == "NOCURL") return Probe.SKIP
    return if (code.length == 3 && code.all { it.isDigit() } && code != "000") {
        Probe.ALIVE
    } else {
        Probe.DEAD
    }
}
