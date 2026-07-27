package sh.haven.app.agent

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.AgentAuditEvent
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "McpServer"

/** Max time to wait for the user to answer a consent prompt before returning a
 *  retryable error. An interactive human needs time to notice the prompt,
 *  switch to Haven and tap — 8s was unusable in practice. Held below the
 *  socket read timeout (raised in lockstep below) so the connection isn't
 *  dropped mid-wait. If the MCP client gives up sooner, [AgentConsentManager]
 *  clears the orphaned prompt in a finally block so the sheet never sticks. */
private const val CONSENT_WAIT_MS: Long = 55_000L

/** Backoff between WireGuard (re)bind attempts when no tunnel is up yet
 *  or the listener dropped (roam re-handshake, tunnel released). */
private const val WG_RETRY_MS: Long = 5_000L

/**
 * Minimal Model Context Protocol (MCP) server for Haven.
 *
 * Binds to **127.0.0.1 only** (loopback) so the server is reachable
 * from local processes (any MCP client running in PRoot, a script,
 * a curl test) but not from the LAN. This is the agent transport
 * that implements Haven's "shared viewport" principle:
 * every observable state and action a human has in the UI should be
 * reachable by an agent through the same underlying ViewModels and
 * repositories.
 *
 * ### Protocol
 *
 * Implements the 2025-06-18 **Streamable HTTP** transport. A single
 * `POST /mcp` endpoint accepts one JSON-RPC 2.0 message and responds
 * with a single JSON body. No SSE, no WebSocket.
 *
 * ### Sessions
 *
 * The server is process-stateful per the streamable-HTTP spec:
 * `initialize` mints a UUID, returned in the `Mcp-Session-Id` response
 * header. The client echoes it on subsequent requests so the server
 * can re-resolve the paired client identity without re-prompting.
 * When the server doesn't recognise a presented session id (because
 * the process restarted, lost its in-memory session map, etc.) it
 * responds with HTTP **404** instead of HTTP 200 + JSON-RPC error
 * — the spec-defined signal that tells the client to re-`initialize`
 * automatically. Without this, Claude Code's MCP transport sees a
 * generic JSON-RPC -32001 and gets wedged until the user tears down
 * its session entirely.
 *
 * Every HTTP request must also carry the per-install bearer token written
 * into the private PRoot MCP config. Loopback is shared by Android apps, so
 * binding 127.0.0.1 is not authentication by itself.
 *
 * Supported methods:
 * - `initialize` — protocol handshake, returns server info + tools capability
 * - `notifications/initialized` — acknowledged, no-op
 * - `tools/list` — returns the available tool definitions
 * - `tools/call` — dispatches to a named tool, passing arguments
 *
 * Client identity is bound to a server-issued `Mcp-Session-Id`; a later
 * request is never attributed from a process-global "last client" hint.
 */
@Singleton
class McpServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val rcloneClient: RcloneClient,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val auditRecorder: AgentAuditRecorder,
    private val consentManager: AgentConsentManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
    private val workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val tunnelManager: sh.haven.core.tunnel.TunnelManager,
    private val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    private val portKnocker: sh.haven.core.knock.PortKnocker,
    private val spaSender: sh.haven.core.spa.SpaSender,
    private val connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    private val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
    private val terminalInputQueue: TerminalInputQueue,
    private val prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val usbBroker: sh.haven.core.usb.UsbBroker,
) : Closeable {

    /**
     * Session map for the streamable-HTTP transport. `initialize`
     * mints a UUID and stores [Session] here; subsequent requests
     * that present the same id in `Mcp-Session-Id` resolve to the
     * paired clientName. Entries older than [SESSION_TTL_MS] are
     * purged at next initialize to keep the map bounded.
     *
     * Unbounded growth is unlikely in practice (one entry per
     * client-initialize across the lifetime of the foreground process)
     * but the TTL guards against pathological churn.
     */
    private data class Session(val clientName: String, val createdAt: Long)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val clientSlots = Semaphore(MAX_CONCURRENT_CLIENTS, true)

    private val transportPreferences by lazy {
        context.getSharedPreferences(MCP_TRANSPORT_PREFS, Context.MODE_PRIVATE)
    }

    /** Random bearer credential stored only in yySlide's private app data. */
    val accessToken: String by lazy { getOrCreateAccessToken() }

    /**
     * In-memory mirror of [UserPreferencesRepository.mcpAllowedClients]
     * read once at server start and updated when [handleInitialize]
     * persists a new pairing. Lets every JSON-RPC dispatch check the
     * allowlist without re-collecting the Flow on the hot path. Treat
     * as authoritative within the lifetime of the running server;
     * Settings changes that wipe the allowlist while the server is up
     * are picked up on the next server restart.
     */
    @Volatile
    private var allowedClients: Set<String> = emptySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serializes [start] and [stop] so preference-driven toggles that
     * land close together can never leave the server with a half-torn
     * listener or a zombie accept thread.
     */
    private val lifecycleLock = Any()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    private val _endpointUrl = MutableStateFlow<String?>(null)
    val endpointUrl: StateFlow<String?> = _endpointUrl.asStateFlow()

    /**
     * URL the endpoint is reachable at over the active WireGuard tunnel
     * (`http://<wg-interface-ip>:<port>/mcp`), or null when not bound on a
     * WG tunnel. Stable across the device's WiFi/hotspot roams. (#176)
     */
    private val _wireguardEndpointUrl = MutableStateFlow<String?>(null)
    val wireguardEndpointUrl: StateFlow<String?> = _wireguardEndpointUrl.asStateFlow()

    /** Synthetic profile id under which the MCP listener holds a WG tunnel. */
    private val wireguardProfileId = "mcp-wg-listener"
    private var wireguardJob: Job? = null
    @Volatile private var wireguardListener: sh.haven.core.tunnel.TunneledServerSocket? = null

    /**
     * Standard MCP server registration JSON for the local yySlide endpoint.
     * Null when the server isn't running. Any MCP-aware client can
     * merge this into its own config; the shape is compatible with
     * the common `{ "mcpServers": { "name": { "type", "url" } } }`
     * format most clients accept.
     */
    val mcpServerConfigJson: String?
        get() {
            val url = _endpointUrl.value ?: return null
            return buildMcpServerConfigJson(url, accessToken)
        }

    /** MCP client registration JSON for peers on the active WireGuard tunnel. */
    val wireguardMcpServerConfigJson: String?
        get() {
            val url = _wireguardEndpointUrl.value ?: return null
            return buildMcpServerConfigJson(url, accessToken)
        }

    private val tools = McpTools(
        context = context,
        connectionRepository = connectionRepository,
        portForwardRepository = portForwardRepository,
        sshSessionManager = sshSessionManager,
        sessionManagerRegistry = sessionManagerRegistry,
        rcloneClient = rcloneClient,
        sftpStreamServer = sftpStreamServer,
        hlsStreamServer = hlsStreamServer,
        ffmpegExecutor = ffmpegExecutor,
        preferencesRepository = preferencesRepository,
        terminalFontInstaller = terminalFontInstaller,
        localSessionManager = localSessionManager,
        agentUiCommandBus = agentUiCommandBus,
        transportSelector = transportSelector,
        workspaceRepository = workspaceRepository,
        workspaceLauncher = workspaceLauncher,
        tunnelConfigRepository = tunnelConfigRepository,
        tunnelManager = tunnelManager,
        terminalSessionRegistry = terminalSessionRegistry,
        portKnocker = portKnocker,
        spaSender = spaSender,
        connectionLogRepository = connectionLogRepository,
        servedFileTracker = servedFileTracker,
        syncProfileRepository = syncProfileRepository,
        terminalInputQueue = terminalInputQueue,
        prootInstallLogRepository = prootInstallLogRepository,
        sshKeyRepository = sshKeyRepository,
        totpSecretRepository = totpSecretRepository,
        desktopSessionRegistry = desktopSessionRegistry,
        usbBroker = usbBroker,
        consentManager = consentManager,
        mcpPortProvider = { port },
    )

    /**
     * Start the server on the first free port in [8730..8739] (a small
     * deterministic range so a reconnecting MCP client can find us
     * again after an app restart). Falls back to an OS-assigned port
     * if all preferred ports are busy.
     *
     * Idempotent: if a healthy instance is already running this is a
     * no-op; if a stale instance is detected (flag set but listener
     * socket closed or accept thread dead) it's torn down and
     * re-created. This matters because the Android process can be
     * suspended for long periods and the server's threads may be
     * killed out from under it while [isRunning] still reads `true`.
     */
    fun start() = synchronized(lifecycleLock) {
        if (isHealthy()) return@synchronized
        if (isRunning) {
            Log.w(TAG, "start() found stale instance, tearing down before rebinding")
            stopLocked()
        }
        val ss = bindLoopback()
        serverSocket = ss
        port = ss.localPort
        transportPreferences.edit().putInt(MCP_PORT_KEY, port).apply()
        isRunning = true
        _endpointUrl.value = buildMcpEndpointUrl("127.0.0.1", port)
        // Seed the in-memory allowlist mirror from DataStore. Subsequent
        // pairing approvals append to this on the dispatch thread.
        allowedClients = runBlocking { preferencesRepository.mcpAllowedClients.first() }
        Log.i(TAG, "MCP server listening on ${_endpointUrl.value} (paired clients: ${allowedClients.size})")

        serverThread = thread(name = "mcp-http", isDaemon = true) {
            while (isRunning && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    if (!clientSlots.tryAcquire()) {
                        runCatching { client.use { writeError(it.getOutputStream(), 503, "Service Unavailable") } }
                        continue
                    }
                    thread(name = "mcp-client", isDaemon = true) {
                        try {
                            handleClient(client)
                        } catch (e: Throwable) {
                            // Last-resort guard so one bad request can
                            // never kill a worker thread silently and
                            // leave the peer socket in CLOSE_WAIT.
                            Log.w(TAG, "worker crashed: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        } finally {
                            clientSlots.release()
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed by stop() — expected on shutdown
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                }
            }
            Log.i(TAG, "MCP accept loop exited")
        }

        // Optionally also expose the endpoint on the active WireGuard tunnel.
        if (runBlocking { preferencesRepository.mcpWireguardEnabled.first() }) {
            startWireguardBinderLocked()
        }
    }

    override fun close() = stop()

    /**
     * Enable/disable the WireGuard-exposed listener at runtime (driven by
     * the `mcpWireguardEnabled` preference). No-op when the server isn't
     * running — [start] picks up the current pref value itself.
     */
    fun setWireguardEnabled(enabled: Boolean) = synchronized(lifecycleLock) {
        if (!isRunning) return@synchronized
        if (enabled) startWireguardBinderLocked() else stopWireguardBinderLocked()
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun startWireguardBinderLocked() {
        if (wireguardJob?.isActive == true) return
        val boundPort = port
        wireguardJob = scope.launch {
            while (isActive) {
                // Bind on whichever WireGuard tunnel is currently up; holding
                // it as a dependent keeps it alive while MCP is bound.
                val tunnel = tunnelManager.acquireFirstWireguard(wireguardProfileId)
                if (tunnel == null) {
                    delay(WG_RETRY_MS)
                    continue
                }
                val ln = try {
                    tunnel.listenTcp(boundPort)
                } catch (e: Exception) {
                    Log.w(TAG, "WG listen on $boundPort failed: ${e.message}")
                    tunnelManager.release(wireguardProfileId)
                    delay(WG_RETRY_MS)
                    continue
                }
                if (ln == null) {
                    tunnelManager.release(wireguardProfileId)
                    delay(WG_RETRY_MS)
                    continue
                }
                wireguardListener = ln
                _wireguardEndpointUrl.value = tunnel.localAddress()?.let {
                    buildMcpEndpointUrl(it, boundPort)
                }
                Log.i(TAG, "MCP also listening on WireGuard ${_wireguardEndpointUrl.value ?: ":$boundPort"}")
                try {
                    while (isActive) {
                        val conn = ln.accept()
                        if (!clientSlots.tryAcquire()) {
                            runCatching { writeError(conn.outputStream, 503, "Service Unavailable") }
                            runCatching { conn.close() }
                            continue
                        }
                        scope.launch {
                            try {
                                handleConnection(conn.inputStream, conn.outputStream)
                            } catch (e: Throwable) {
                                Log.w(TAG, "WG worker crashed: ${e.message}")
                            } finally {
                                try { conn.close() } catch (_: Exception) {}
                                clientSlots.release()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // accept() throws when the listener or tunnel closes
                    // (roam re-handshake, profile released the tunnel, or our
                    // own stop closed it) — fall through to re-acquire.
                    Log.i(TAG, "WG accept loop ended: ${e.message}")
                } finally {
                    wireguardListener = null
                    _wireguardEndpointUrl.value = null
                    tunnelManager.release(wireguardProfileId)
                }
                if (isActive) delay(WG_RETRY_MS)
            }
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun stopWireguardBinderLocked() {
        wireguardJob?.cancel()
        wireguardJob = null
        // Close the listener to unblock a pending accept(), then drop our
        // hold on the tunnel.
        try { wireguardListener?.close() } catch (_: Exception) {}
        wireguardListener = null
        _wireguardEndpointUrl.value = null
        scope.launch { tunnelManager.release(wireguardProfileId) }
    }

    fun stop() = synchronized(lifecycleLock) {
        stopLocked()
    }

    /** Must be called while holding [lifecycleLock]. */
    private fun stopLocked() {
        isRunning = false
        stopWireguardBinderLocked()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        port = 0
        _endpointUrl.value = null
    }

    /**
     * True iff the server is running AND the listener socket is still
     * bound AND the accept thread is still alive. Used by [start] to
     * distinguish a healthy instance from a process-suspend zombie.
     */
    private fun isHealthy(): Boolean {
        if (!isRunning) return false
        val ss = serverSocket ?: return false
        if (ss.isClosed) return false
        val t = serverThread ?: return false
        if (!t.isAlive) return false
        return true
    }

    // --- Socket binding ---

    // Off-device reachability is provided by McpTunnelManager's dedicated
    // SSH reverse tunnel. The roaming-proof follow-up is to also bind the
    // listener on the stable wgbridge WireGuard interface address (a
    // `bindWireguard()` sibling to this method, gated by an off-by-default
    // pref + pairing token) so a WG-peer laptop reaches it directly with no
    // reverse forward. Blocked on whether the gVisor netstack exposes TCP
    // listen/accept — tracked in GlassHaven/Haven#176.
    private fun bindLoopback(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        // Try preferred ports first so a client that cached an endpoint
        // across app restarts has a decent chance of finding us again.
        for (p in 8730..8739) {
            try {
                return ServerSocket(p, 10, loopback).apply { reuseAddress = true }
            } catch (_: IOException) {
                // busy, try next
            }
        }
        // All preferred ports busy — let the OS pick one
        return ServerSocket(0, 10, loopback).apply { reuseAddress = true }
    }

    // --- HTTP + JSON-RPC handling ---

    private fun handleClient(socket: Socket) {
        try {
            // Read timeout. Must exceed CONSENT_WAIT_MS so a request blocked on
            // an interactive consent prompt isn't dropped mid-wait.
            socket.soTimeout = 70_000
            socket.use { s -> handleConnection(s.getInputStream(), s.getOutputStream()) }
        } catch (e: Exception) {
            Log.w(TAG, "client handler error: ${e.message}")
        }
    }

    /**
     * Serve one HTTP/JSON-RPC request off a raw stream pair. Shared by the
     * loopback [ServerSocket] path ([handleClient]) and the WireGuard
     * netstack accept loop ([wireguardAcceptLoop]) so both transports run
     * identical request handling + pairing checks (#176).
     */
    internal fun handleConnection(input: InputStream, output: OutputStream) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val requestLine = readHttpLine(buffered, MAX_REQUEST_LINE_BYTES) ?: return
        val parts = requestLine.split(" ")
        if (parts.size != 3 || parts[2] != "HTTP/1.1") {
            writeError(output, 400, "Bad Request")
            return
        }
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        // Parse headers
        var contentLength: Int? = null
        var mcpSessionIdHeader: String? = null
        var authorizationHeader: String? = null
        var headerCount = 0
        while (true) {
            val line = readHttpLine(buffered, MAX_HEADER_LINE_BYTES) ?: break
            if (line.isEmpty()) break
            if (++headerCount > MAX_HEADER_COUNT) {
                writeError(output, 431, "Request Header Fields Too Large")
                return
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (name) {
                    "content-length" -> {
                        val parsed = value.toLongOrNull()
                        if (contentLength != null || parsed == null || parsed < 0L) {
                            writeError(output, 400, "Bad Request")
                            return
                        }
                        if (parsed > MAX_REQUEST_BODY_BYTES) {
                            writeError(output, 413, "Payload Too Large")
                            return
                        }
                        contentLength = parsed.toInt()
                    }
                    "transfer-encoding" -> {
                        // This tiny server accepts fixed-length request bodies
                        // only. Reject ambiguous framing instead of risking an
                        // HTTP request-smuggling interpretation mismatch.
                        writeError(output, 400, "Bad Request")
                        return
                    }
                    "mcp-session-id" -> {
                        if (mcpSessionIdHeader != null) {
                            writeError(output, 400, "Bad Request")
                            return
                        }
                        mcpSessionIdHeader = value.takeIf { it.isNotEmpty() }
                    }
                    "authorization" -> {
                        if (authorizationHeader != null) {
                            writeError(output, 400, "Bad Request")
                            return
                        }
                        authorizationHeader = value
                    }
                }
            }
        }

        if (!isAuthorized(authorizationHeader)) {
            writeError(output, 401, "Unauthorized")
            return
        }

        when {
            method == "POST" && (path == "/mcp" || path == "/") -> {
                val length = contentLength ?: run {
                    writeError(output, 411, "Length Required")
                    return
                }
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = buffered.read(bytes, read, length - read)
                    if (n < 0) {
                        writeError(output, 400, "Bad Request")
                        return
                    }
                    read += n
                }
                val body = bytes.toString(Charsets.UTF_8)
                val outcome = handleJsonRpc(body, mcpSessionIdHeader)
                if (outcome.httpStatus == 404) {
                    // Streamable-HTTP signal: presented session id is
                    // unknown. Empty body — the client treats this as
                    // "session expired, re-initialize".
                    writeJson(output, 404, "", null)
                } else {
                    writeJson(output, outcome.httpStatus, outcome.body, outcome.responseSessionId)
                }
            }
            method == "GET" && (path == "/mcp" || path == "/") -> {
                // SSE channel for server-initiated messages — not supported
                // in v1. Spec allows 405.
                writeError(output, 405, "Method Not Allowed")
            }
            method == "OPTIONS" -> {
                // Do not make a privileged localhost service callable by an
                // arbitrary web origin. Native MCP clients do not need CORS.
                writeError(output, 405, "Method Not Allowed")
            }
            else -> writeError(output, 404, "Not Found")
        }
    }

    /**
     * Test-friendly overload — drives the JSON-RPC layer without a
     * session id, exercising the legacy fallback path. Existing tests
     * call this signature. Production traffic goes through the
     * (body, sessionId) overload via [handleClient].
     */
    internal fun handleJsonRpc(body: String): String =
        handleJsonRpc(body, requestSessionId = null).body

    /**
     * Outcome of a JSON-RPC request as seen by the HTTP layer.
     *
     * - [httpStatus] is normally 200; becomes 404 when an unknown
     *   `Mcp-Session-Id` is presented, so the client per spec
     *   re-initialises automatically.
     * - [body] is the JSON-RPC reply body (may be empty for
     *   notifications or 404s).
     * - [responseSessionId] is set by a successful `initialize`; the
     *   HTTP layer echoes it back to the client in the
     *   `Mcp-Session-Id` response header.
     */
    internal data class JsonRpcOutcome(
        val httpStatus: Int,
        val body: String,
        val responseSessionId: String?,
    )

    /**
     * Parse a JSON-RPC 2.0 request and return the response. Visible
     * to tests in the same module so the consent gate, error mapping,
     * and audit-outcome plumbing can be exercised without sockets.
     *
     * [requestSessionId] is the value of the inbound `Mcp-Session-Id`
     * header, or null when the client doesn't send one (legacy path).
     */
    internal fun handleJsonRpc(body: String, requestSessionId: String?): JsonRpcOutcome {
        if (body.isBlank()) {
            return JsonRpcOutcome(200, jsonRpcError(null, -32700, "Parse error: empty body"), null)
        }
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return JsonRpcOutcome(200, jsonRpcError(null, -32700, "Parse error: ${e.message}"), null)
        }
        val id = req.opt("id") // may be null for notifications
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        // Notifications (no id) get no response per JSON-RPC spec, but we still
        // need to return an empty 200 for the HTTP layer. Return "" for those.
        val isNotification = !req.has("id")

        val requestClient = if (method == "initialize") {
            params.optJSONObject("clientInfo")?.optString("name")?.takeIf { it.isNotBlank() }
        } else {
            requestSessionId?.let { sessions[it]?.clientName }
        }

        val started = System.nanoTime()
        var outcome = AgentAuditEvent.Outcome.OK
        var errorMessage: String? = null
        var resultJson: JSONObject? = null
        var newSessionId: String? = null
        val response = try {
            val result = dispatch(method, params, requestSessionId, requestClient)
            if (result is JSONObject) resultJson = result
            // A successful `initialize` mints a new session id we'll
            // hand back to the client in the response header. The
            // client echoes it on subsequent requests and we resolve
            // it back to a clientName without re-prompting.
            if (method == "initialize" && result is JSONObject) {
                val clientName = requestClient
                if (!clientName.isNullOrBlank()) {
                    purgeExpiredSessions()
                    val sid = UUID.randomUUID().toString()
                    sessions[sid] = Session(clientName, System.currentTimeMillis())
                    newSessionId = sid
                }
            }
            if (isNotification) "" else jsonRpcResult(id, result)
        } catch (e: SessionExpiredError) {
            // Streamable-HTTP signal: the client presented a session
            // id we don't recognise (we restarted; map was cleared).
            // Return 404 with empty body — the spec-defined cue that
            // tells the client to re-`initialize` automatically
            // instead of propagating an opaque -32001 to the user.
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            return JsonRpcOutcome(404, "", null)
        } catch (e: ConsentDeniedError) {
            // User denied via the bottom-sheet prompt, or no foreground
            // activity was available to ask. Audit it as DENIED so the
            // user can tell intent-from-the-agent apart from runtime
            // failure later.
            outcome = AgentAuditEvent.Outcome.DENIED
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "User denied")
        } catch (e: McpError) {
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "Error")
        } catch (e: Exception) {
            Log.e(TAG, "dispatch failed for method=$method", e)
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, -32603, "Internal error: ${e.message}")
        }

        // Record after responding so latency is unaffected. We skip
        // bookkeeping notifications (`notifications/initialized`) —
        // they carry no semantic action and would just clutter the
        // audit view.
        if (method != "notifications/initialized") {
            val toolName = if (method == "tools/call") {
                params.optString("name").takeIf { it.isNotEmpty() }
            } else null
            val rawArgs = if (method == "tools/call") {
                params.optJSONObject("arguments")
            } else if (params.length() > 0) params else null
            // For tools/call, the structured tool output lives under
            // `structuredContent`; the summariser wants that, not the
            // MCP wrapper that holds `content` + `structuredContent`.
            val resultForSummary = if (method == "tools/call") {
                resultJson?.optJSONObject("structuredContent")
            } else resultJson
            val durationMs = (System.nanoTime() - started) / 1_000_000
            auditRecorder.record(
                method = method,
                toolName = toolName,
                rawArgs = rawArgs,
                result = resultForSummary,
                durationMs = durationMs,
                outcome = outcome,
                errorMessage = errorMessage,
                clientHint = requestClient,
            )
        }

        return JsonRpcOutcome(200, response, newSessionId)
    }

    /**
     * Drop session entries older than [SESSION_TTL_MS]. Called from
     * the `initialize` success path so the work stays off the hot
     * dispatch path. The map is small in normal use; this is a
     * belt-and-braces guard against unbounded growth under churn.
     */
    private fun purgeExpiredSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.createdAt < cutoff) it.remove()
        }
    }

    /**
     * Dispatch an MCP method to its handler.
     *
     * [requestSessionId] is the inbound `Mcp-Session-Id` header. When
     * present but unrecognised we throw [SessionExpiredError] so the
     * HTTP layer returns 404 and the client per spec re-initialises.
     */
    private fun dispatch(
        method: String,
        params: JSONObject,
        requestSessionId: String?,
        requestClient: String?,
    ): Any? {
        // Pairing gate: every method except the pair-or-fail path itself
        // requires the calling client to be in the allowlist. `ping` is
        // intentionally allowed unauthenticated so a client can probe
        // for the server's existence without committing to identifying
        // itself. `notifications/initialized` is a spec-required ack
        // sent right after `initialize` and is exempt so a paired client
        // that just finished its handshake doesn't silently fail it.
        if (method != "initialize" && method != "ping") {
            if (requestSessionId == null) {
                throw McpError(-32001, "Missing Mcp-Session-Id; initialize a new authenticated session")
            }
            if (requestClient == null) {
                // The client sent a session id we don't recognise.
                // Almost always means the server restarted while the
                // client cached its id. Returning -32001 here would
                // wedge the client; SessionExpiredError → HTTP 404
                // tells it to re-initialise.
                throw SessionExpiredError()
            }
            if (requestClient !in allowedClients) {
                throw McpError(
                    -32001,
                    "MCP client not paired with yySlide. Send `initialize` first; " +
                        "if the prompt was denied, open yySlide and approve a fresh attempt.",
                )
            }
        }
        return when (method) {
            "initialize" -> handleInitialize(params)
            "notifications/initialized" -> JSONObject() // ack
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolsCall(params, requestClient)
            "ping" -> JSONObject()
            else -> throw McpError(-32601, "Method not found: $method")
        }
    }

    private fun handleInitialize(params: JSONObject): JSONObject {
        val clientProtoVersion = params.optString("protocolVersion", "2025-06-18")
        val clientInfo = params.optJSONObject("clientInfo")
        val clientName = clientInfo?.optString("name")?.takeIf { it.isNotBlank() }
        val clientVersion = clientInfo?.optString("version")?.takeIf { it.isNotBlank() }
        Log.i(TAG, "MCP initialize from name=${clientName ?: "<anonymous>"} v=${clientVersion ?: "?"} protocolVersion=$clientProtoVersion")

        // Pairing gate. An empty / unknown name has no way to pair (the
        // user would tap Allow on "MCP client '' wants to connect" with
        // no idea who's behind it), so refuse outright.
        if (clientName.isNullOrBlank()) {
            throw McpError(
                -32002,
                "MCP initialize must include clientInfo.name. Anonymous clients " +
                    "cannot be paired and are rejected.",
            )
        }
        // Read the authoritative allowlist from DataStore on every
        // initialize — the in-memory `allowedClients` mirror is only
        // populated by pairings that happen during this server's
        // lifetime, so a paired-on-disk client whose name predates this
        // server instance would otherwise be re-prompted on every
        // restart. Refresh the cache here so the dispatch hot path
        // doesn't have to round-trip to DataStore.
        val persistedAllowlist = runBlocking { preferencesRepository.mcpAllowedClients.first() }
        if (clientName in persistedAllowlist) {
            Log.i(TAG, "MCP initialize: '$clientName' in persisted allowlist (size=${persistedAllowlist.size}); skipping pairing prompt")
            if (clientName !in allowedClients) {
                allowedClients = allowedClients + clientName
            }
        } else {
            Log.i(TAG, "MCP initialize: '$clientName' is NEW — queueing pairing prompt (persisted allowlist size=${persistedAllowlist.size})")
            val decision = runBlocking {
                consentManager.requestClientPairing(clientName, clientVersion)
            }
            Log.i(TAG, "MCP initialize: pairing decision for '$clientName' = $decision")
            when (decision) {
                ConsentDecision.ALLOW -> {
                    runBlocking { preferencesRepository.addMcpAllowedClient(clientName) }
                    allowedClients = allowedClients + clientName
                    Log.i(TAG, "MCP client '$clientName' paired with yySlide (allowlist size=${allowedClients.size})")
                }
                ConsentDecision.DENY -> {
                    throw McpError(
                        -32001,
                        "MCP client '$clientName' is not paired with yySlide. " +
                            "The pairing prompt was denied or no yySlide activity was visible. " +
                            "Open yySlide and retry to surface a fresh pairing prompt.",
                    )
                }
            }
        }
        return JSONObject().apply {
            put("protocolVersion", "2025-06-18")
            put("serverInfo", JSONObject().apply {
                put("name", "yyslide-agent")
                put("version", sh.haven.app.BuildConfig.VERSION_NAME)
            })
            put("capabilities", JSONObject().apply {
                // Advertise tools capability only in v1
                put("tools", JSONObject().apply {
                    put("listChanged", false)
                })
            })
            put("instructions",
                "yySlide is a mobile thin-client OS for distributed compute, storage, and presence. " +
                    "Use these tools to inspect the user's saved connections, active sessions, and " +
                    "cloud storage without disturbing the UI they're looking at.")
        }
    }

    private fun handleToolsList(): JSONObject {
        return JSONObject().apply {
            put("tools", JSONArray().apply {
                tools.definitions().forEach { put(it) }
            })
        }
    }

    private fun handleToolsCall(params: JSONObject, clientName: String?): JSONObject {
        val name = params.optString("name", "")
            .ifEmpty { throw McpError(-32602, "Missing tool name") }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        // Pre-consent capability gate. Some tools live behind an
        // additional toggle in Settings on top of the global agent
        // endpoint. Failing here means the user doesn't see a consent
        // prompt for a tool that would then error anyway, and the
        // audit log records the denial cleanly. This covers every tool
        // that returns, streams, views, or stages backend file bytes.
        if (name in FILE_CONTENT_READ_TOOLS) {
            val allowed = runBlocking {
                preferencesRepository.agentAllowFileRead.first()
            }
            if (!allowed) {
                throw McpError(
                    -32011,
                    "agent file read is disabled — enable in Settings → Agent endpoint",
                )
            }
        }
        // queue_terminal_input (and its deprecated alias
        // queue_self_message) is a keystroke-injection capability —
        // gated by a separate power-user toggle on top of the
        // endpoint switch, in the same shape as serve_file's gate
        // above. (#161)
        if (name == "queue_terminal_input" || name == "queue_self_message") {
            val allowed = runBlocking {
                preferencesRepository.agentAllowTerminalInputQueue.first()
            }
            if (!allowed) {
                throw McpError(
                    -32011,
                    "queue_terminal_input is disabled — enable in Settings → Agent endpoint → " +
                        "Allow agents to queue terminal input",
                )
            }
        }

        // Look up consent metadata before invoking the handler. Unknown
        // tools fall through to tools.call() which will throw the right
        // error; treating them as NEVER avoids prompting on a typo.
        val consent = tools.consentFor(name)
        if (consent != null && consent.level != ConsentLevel.NEVER) {
            val summary = try {
                consent.summary(arguments)
            } catch (e: Exception) {
                // A summary builder shouldn't crash the dispatcher; fall
                // back to the tool name so the prompt still renders.
                Log.w(TAG, "summary builder for '$name' threw: ${e.message}")
                name
            }
            // Bound the wait so a prompt the user hasn't answered doesn't block
            // the handler past the socket / client timeout (which surfaced as
            // "operation timed out" / "socket closed"). On timeout, return a
            // retryable error rather than hanging — and never auto-allow.
            val decision = runBlocking {
                withTimeoutOrNull(CONSENT_WAIT_MS) {
                    consentManager.requestConsent(
                        toolName = name,
                        clientHint = clientName,
                        summary = summary,
                        level = consent.level,
                    )
                }
            }
            when (decision) {
                ConsentDecision.DENY -> throw ConsentDeniedError(name)
                null -> throw McpError(
                    -32012,
                    "Consent prompt for '$name' wasn't answered in time — approve it on the device " +
                        "and retry. (Once approved for this session, further calls won't prompt.)",
                )
                else -> { /* ALLOW — proceed */ }
            }
        }
        val content = try {
            runBlocking { tools.call(name, arguments, clientName) }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "tool '$name' threw", e)
            throw McpError(-32603, "Tool failed: ${e.message}")
        }
        // A proxied guest-MCP tool (McpTools aggregation) returns the guest
        // server's own MCP content array under the reserved key __mcpContent.
        // Forward it verbatim so the guest's text/image blocks reach the
        // agent unchanged, instead of being re-serialised as nested JSON text.
        content.optJSONArray("__mcpContent")?.let { passthrough ->
            content.remove("__mcpContent")
            return JSONObject().apply {
                put("content", passthrough)
                if (content.length() > 0) put("structuredContent", content)
            }
        }
        // A tool can return an inline image by setting the reserved keys
        // __imageBase64 + __mimeType (see McpTools.captureDesktop). Lift
        // them into a proper MCP `image` content block so the agent sees
        // the picture directly over the existing channel — no second port,
        // no file download — and strip them from the text echo /
        // structuredContent so the giant base64 blob isn't duplicated.
        val imageB64 = content.opt("__imageBase64") as? String
        val imageMime = content.opt("__mimeType") as? String
        if (imageB64 != null) {
            content.remove("__imageBase64")
            content.remove("__mimeType")
        }
        return JSONObject().apply {
            put("content", JSONArray().apply {
                if (imageB64 != null) {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("data", imageB64)
                        put("mimeType", imageMime ?: "image/png")
                    })
                }
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", content.toString(2))
                })
            })
            put("structuredContent", content)
        }
    }

    // --- JSON-RPC response builders ---

    private fun jsonRpcResult(id: Any?, result: Any?): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id)
        obj.put("result", result ?: JSONObject.NULL)
        return obj.toString()
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
        obj.put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
        return obj.toString()
    }

    // --- HTTP response helpers ---

    private fun writeJson(
        out: OutputStream,
        status: Int,
        body: String,
        sessionId: String? = null,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusLine = when (status) {
            200 -> "200 OK"
            204 -> "204 No Content"
            404 -> "404 Not Found"
            else -> "$status OK"
        }
        val headers = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            if (sessionId != null) append("Mcp-Session-Id: $sessionId\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun writeError(out: OutputStream, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val statusLine = "$status $text"
        val headers = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun getOrCreateAccessToken(): String {
        transportPreferences.getString(MCP_TOKEN_KEY, null)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val generated = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        transportPreferences.edit().putString(MCP_TOKEN_KEY, generated).apply()
        return generated
    }

    private fun isAuthorized(header: String?): Boolean {
        val separator = header?.indexOf(' ') ?: return false
        if (separator <= 0 || !header.substring(0, separator).equals("Bearer", ignoreCase = true)) {
            return false
        }
        val candidate = header.substring(separator + 1)
        if (candidate.isBlank() || candidate.any(Char::isWhitespace)) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            accessToken.toByteArray(Charsets.UTF_8),
        )
    }

    private fun readHttpLine(input: InputStream, maxBytes: Int): String? {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 256))
        var sawAny = false
        while (out.size() <= maxBytes) {
            val b = input.read()
            if (b < 0) return if (sawAny) null else null
            sawAny = true
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            out.write(b)
        }
        throw IOException("HTTP line exceeds $maxBytes bytes")
    }
}

internal fun buildMcpEndpointUrl(host: String, port: Int): String {
    require(host.isNotBlank()) { "MCP endpoint host must not be blank" }
    require(port in 1..65535) { "MCP endpoint port is out of range: $port" }
    val authorityHost = when {
        host.startsWith('[') && host.endsWith(']') -> host
        ':' in host -> "[$host]"
        else -> host
    }
    return "http://$authorityHost:$port/mcp"
}

internal fun buildMcpServerConfigJson(endpointUrl: String, accessToken: String): String = """
    {
      "mcpServers": {
        "yyslide": {
          "type": "http",
          "url": "$endpointUrl",
          "headers": {
            "Authorization": "Bearer $accessToken"
          }
        }
      }
    }
""".trimIndent()

/** Session entries older than this are evicted on the next initialize. */
private const val SESSION_TTL_MS: Long = 24L * 60L * 60L * 1000L
private const val MAX_REQUEST_BODY_BYTES: Long = 1L shl 20
private const val MAX_REQUEST_LINE_BYTES = 4 * 1024
private const val MAX_HEADER_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_COUNT = 64
private const val MAX_CONCURRENT_CLIENTS = 8
private const val MCP_TRANSPORT_PREFS = "yyslide_mcp_transport"
private const val MCP_TOKEN_KEY = "access_token"
private const val MCP_PORT_KEY = "port"
private val FILE_CONTENT_READ_TOOLS = setOf(
    "serve_file",
    "stream_sftp_file",
    "install_apk_from_backend",
    "view_file",
    "read_guest_file",
)

/** Lightweight error type carrying a JSON-RPC error code. */
open class McpError(val code: Int, message: String) : RuntimeException(message)

/**
 * Raised by the dispatcher when the user denies a consent prompt (or no
 * foreground activity is available to render one). Audit is recorded
 * with [AgentAuditEvent.Outcome.DENIED] so the dashboard can distinguish
 * "the agent tried and we said no" from "the agent tried and it broke."
 */
class ConsentDeniedError(toolName: String) :
    McpError(-32000, "User denied: $toolName")

/**
 * Raised by [McpServer.dispatch] when a non-initialize request
 * presents an `Mcp-Session-Id` the server doesn't recognise. The HTTP
 * layer maps this to **404**, which is the streamable-HTTP-spec signal
 * that tells the client to re-`initialize` and retry — fixing the
 * "won't reconnect after Haven restart without dropping the Claude
 * Code session" wedge.
 *
 * The error message is informational only; clients react to the 404
 * status, not the body.
 */
class SessionExpiredError :
    McpError(-32001, "MCP session expired; re-initialize")
