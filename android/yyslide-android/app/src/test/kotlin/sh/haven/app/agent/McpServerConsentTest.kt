package sh.haven.app.agent

import android.content.Context
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentConsentManager
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

/**
 * Drive [McpServer.handleJsonRpc] directly (no socket) and pin the
 * dispatcher's consent gate behaviour:
 *
 *  - non-NEVER tool with no foreground activity → DENY → JSON-RPC error
 *    code -32000, audit outcome [AgentAuditEvent.Outcome.DENIED]
 *  - non-NEVER tool with foreground active but user denies → same
 *  - non-NEVER tool with timeout → same (DENY by default after 60s; we
 *    use a tiny timeout via foreground=false to short-circuit, since
 *    runBlocking in tests with a 60s real-clock timeout would slow CI)
 *  - NEVER tool always runs and audits as OK
 *  - unknown method → -32601
 *
 * Consent ALLOW is covered indirectly via [AgentConsentManagerTest] for
 * the manager itself; here we focus on the JSON-RPC layer's mapping
 * from consent decision → response code → audit outcome.
 */
class McpServerConsentTest {

    /**
     * The client name every test in this suite uses for its `initialize`
     * step. Pre-populated in the prefs mock so the pairing gate
     * short-circuits and tests can focus on the per-tool consent flow.
     */
    private val testClientName = "test-host"

    private fun newServer(
        consentManager: AgentConsentManager = AgentConsentManager(),
        auditRecorder: AgentAuditRecorder = mockk(relaxed = true),
        pairedClients: Set<String> = setOf(testClientName),
    ): Pair<McpServer, AgentAuditRecorder> {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        // McpServer.handleInitialize reads this as the authoritative
        // allowlist; a paired-on-disk client skips the pairing prompt.
        every { prefs.mcpAllowedClients } returns flowOf(pairedClients)
        coEvery { prefs.addMcpAllowedClient(any()) } returns Unit

        val server = McpServer(
            context = mockk<Context>(relaxed = true),
            connectionRepository = mockk<ConnectionRepository>(relaxed = true),
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = auditRecorder,
            consentManager = consentManager,
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
            workspaceRepository = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true),
            workspaceLauncher = mockk<sh.haven.app.workspace.WorkspaceLauncher>(relaxed = true),
            tunnelConfigRepository = mockk<sh.haven.core.data.repository.TunnelConfigRepository>(relaxed = true),
            tunnelManager = mockk<sh.haven.core.tunnel.TunnelManager>(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk<sh.haven.core.knock.PortKnocker>(relaxed = true),
            spaSender = mockk<sh.haven.core.spa.SpaSender>(relaxed = true),
            connectionLogRepository = mockk<sh.haven.core.data.repository.ConnectionLogRepository>(relaxed = true),
            servedFileTracker = mockk<sh.haven.core.data.agent.ServedFileTracker>(relaxed = true),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = mockk<TerminalInputQueue>(relaxed = true),
            prootInstallLogRepository = mockk<sh.haven.core.data.repository.ProotInstallLogRepository>(relaxed = true),
            sshKeyRepository = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true),
            totpSecretRepository = mockk<sh.haven.core.data.repository.TotpSecretRepository>(relaxed = true),
            desktopSessionRegistry = mockk<sh.haven.core.data.desktop.DesktopSessionRegistry>(relaxed = true),
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
        )
        return server to auditRecorder
    }

    /**
     * Send a spec-shaped `initialize` request so the dispatch-time
     * pairing gate sees the test client as paired. Most tests start
     * with this; the pairing-gate tests below send their own variant
     * to exercise the unpaired path.
     */
    private fun initBody(clientName: String = testClientName): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 0)
            .put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("clientInfo", JSONObject()
                    .put("name", clientName)
                    .put("version", "1.0")))
            .toString()

    private fun McpServer.pair(clientName: String = testClientName): String =
        handleJsonRpc(initBody(clientName), requestSessionId = null).responseSessionId
            ?: error("initialize did not return a session id")

    private fun McpServer.httpExchange(request: ByteArray): String {
        val output = java.io.ByteArrayOutputStream()
        handleConnection(java.io.ByteArrayInputStream(request), output)
        return output.toString(Charsets.UTF_8.name())
    }

    private fun McpServer.httpRequest(
        body: String,
        authorization: String? = "Bearer $accessToken",
        contentLength: String = body.toByteArray(Charsets.UTF_8).size.toString(),
        method: String = "POST",
        extraHeaders: List<String> = emptyList(),
    ): ByteArray {
        val headers = buildString {
            append("$method /mcp HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            authorization?.let { append("Authorization: $it\r\n") }
            append("Content-Length: $contentLength\r\n")
            extraHeaders.forEach { append(it).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        return headers + body.toByteArray(Charsets.UTF_8)
    }

    private fun toolsCallBody(name: String, args: JSONObject): String {
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject()
                .put("name", name)
                .put("arguments", args))
            .toString()
    }

    @Test
    fun `non-NEVER tool with no foreground returns -32000 and audits DENIED`() {
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        // Default AgentConsentManager has foregroundActive=false, so any
        // non-NEVER call must immediately fail closed.
        val (server, _) = newServer(
            consentManager = AgentConsentManager(),
            auditRecorder = auditRecorder,
        )
        // Pair first — the pairing gate would otherwise return -32001
        // before the per-tool consent gate gets a chance to run. Drop
        // the audit records left behind by `initialize` so the verify
        // block below only sees the tools/call event.
        val sessionId = server.pair()
        clearMocks(auditRecorder, recordedCalls = true, answers = false)

        val response = server.handleJsonRpc(
            toolsCallBody(
                "disconnect_profile",
                JSONObject().put("profileId", "p1"),
            ),
            requestSessionId = sessionId,
        ).body

        val obj = JSONObject(response)
        val error = obj.optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32000, error.optInt("code"))
        assertTrue(
            "error message must mention denial, got: ${error.optString("message")}",
            error.optString("message").contains("denied", ignoreCase = true),
        )

        // Audit row must record DENIED outcome — that's the only way the
        // user can later distinguish "the agent was blocked" from "the
        // tool ran and failed."
        val outcomeSlot = slot<AgentAuditEvent.Outcome>()
        verify {
            auditRecorder.record(
                method = any(),
                toolName = any(),
                rawArgs = any(),
                result = any(),
                durationMs = any(),
                outcome = capture(outcomeSlot),
                errorMessage = any(),
                clientHint = any(),
            )
        }
        assertEquals(AgentAuditEvent.Outcome.DENIED, outcomeSlot.captured)
    }

    @Test
    fun `EVERY_CALL tool with foreground+user-deny returns -32000 and audits DENIED`() {
        val consentManager = AgentConsentManager().apply { setForegroundActive(true) }
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        val (server, _) = newServer(consentManager = consentManager, auditRecorder = auditRecorder)
        val sessionId = server.pair()
        clearMocks(auditRecorder, recordedCalls = true, answers = false)

        // Spawn the call on a background thread so we can race the
        // "user taps Deny" response against the dispatcher's blocking
        // wait inside requestConsent.
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.handleJsonRpc(
                toolsCallBody(
                    "delete_sftp_file",
                    JSONObject()
                        .put("profileId", "p1")
                        .put("path", "/var/log/x"),
                ),
                requestSessionId = sessionId,
            ).body
        }

        // Wait until the prompt actually appears, then deny it.
        val deadline = System.currentTimeMillis() + 5_000
        var pending = consentManager.pending.value
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            pending = consentManager.pending.value
        }
        assertFalse("dispatcher never queued a consent request", pending.isEmpty())
        kotlinx.coroutines.runBlocking {
            consentManager.respond(
                pending.first().id,
                sh.haven.core.data.agent.ConsentDecision.DENY,
            )
        }

        val response = responseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val obj = JSONObject(response)
        val error = obj.optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32000, error.optInt("code"))

        val outcomeSlot = slot<AgentAuditEvent.Outcome>()
        verify {
            auditRecorder.record(
                method = any(),
                toolName = any(),
                rawArgs = any(),
                result = any(),
                durationMs = any(),
                outcome = capture(outcomeSlot),
                errorMessage = any(),
                clientHint = any(),
            )
        }
        assertEquals(AgentAuditEvent.Outcome.DENIED, outcomeSlot.captured)
    }

    @Test
    fun `unknown method returns -32601`() {
        val (server, _) = newServer()
        val sessionId = server.pair()
        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 7)
                .put("method", "nonsense/method")
                .toString(),
            requestSessionId = sessionId,
        ).body
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32601, error.optInt("code"))
    }

    @Test
    fun `empty body returns -32700 parse error`() {
        val (server, _) = newServer()
        val response = server.handleJsonRpc("")
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32700, error.optInt("code"))
    }

    @Test
    fun `notifications skip the audit log`() {
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        val (server, _) = newServer(auditRecorder = auditRecorder)

        // notifications/initialized has no id and is acked silently —
        // recording it would just clutter the dashboard with bookkeeping
        // events the user never initiated.
        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .toString(),
        )
        // Notifications get an empty body back at the JSON-RPC layer.
        assertEquals("", response)
        verify(exactly = 0) {
            auditRecorder.record(
                method = "notifications/initialized",
                toolName = any(),
                rawArgs = any(),
                result = any(),
                durationMs = any(),
                outcome = any(),
                errorMessage = any(),
                clientHint = any(),
            )
        }
    }

    @Test
    fun `tools_list works without consent prompts`() {
        val consentManager = AgentConsentManager() // foreground=false on purpose
        val (server, _) = newServer(consentManager = consentManager)
        // Pair first so the dispatch-time pairing gate doesn't block
        // the listing — once paired, tools/list itself never prompts.
        val sessionId = server.pair()

        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .toString(),
            requestSessionId = sessionId,
        ).body
        val obj = JSONObject(response)
        assertNull(
            "tools/list must not be gated by foreground; got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        val tools = obj.getJSONObject("result").getJSONArray("tools")
        assertTrue("tools/list should advertise > 0 tools", tools.length() > 0)
    }

    // --- Pairing gate ---

    @Test
    fun `initialize from a paired client returns success without prompting`() {
        val consentManager = AgentConsentManager() // foreground=false — would deny pairing if it ran
        val (server, _) = newServer(consentManager = consentManager)

        val response = server.handleJsonRpc(initBody())
        val obj = JSONObject(response)
        assertNull(
            "paired client should initialize cleanly, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        val result = obj.getJSONObject("result")
        assertEquals("yyslide-agent", result.getJSONObject("serverInfo").getString("name"))
        // No prompt should have queued on the consent manager.
        assertTrue(consentManager.pending.value.isEmpty())
    }

    @Test
    fun `initialize from an unpaired client without foreground returns -32001`() {
        val consentManager = AgentConsentManager() // foreground=false — pairing fails closed
        val (server, _) = newServer(
            consentManager = consentManager,
            pairedClients = emptySet(),
        )

        val response = server.handleJsonRpc(initBody("rogue-client"))
        val obj = JSONObject(response)
        val error = obj.optJSONObject("error")
            ?: error("expected error response, got: $response")
        assertEquals(-32001, error.optInt("code"))
        assertTrue(
            "message should mention pairing, got: ${error.optString("message")}",
            error.optString("message").contains("pair", ignoreCase = true),
        )
    }

    @Test
    fun `initialize with missing clientInfo name returns -32002`() {
        val (server, _) = newServer()

        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 0)
                .put("method", "initialize")
                .put("params", JSONObject().put("protocolVersion", "2025-06-18"))
                .toString(),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32002, error.optInt("code"))
    }

    @Test
    fun `tools_list before initialize returns -32001`() {
        val (server, _) = newServer()

        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "tools/list")
                .toString(),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `tools_call before initialize returns -32001`() {
        val (server, _) = newServer()

        val response = server.handleJsonRpc(
            toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")),
        )
        val error = JSONObject(response).optJSONObject("error")
            ?: error("expected error, got: $response")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `ping is reachable before pairing`() {
        // Lets a client probe the server without identifying itself.
        // Stays cheap so it can't be used to enumerate state.
        val (server, _) = newServer()

        val response = server.handleJsonRpc(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 9)
                .put("method", "ping")
                .toString(),
        )
        val obj = JSONObject(response)
        assertNull(
            "ping should succeed pre-pairing, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
    }

    @Test
    fun `initialize with new clientName triggers pairing - ALLOW persists`() {
        val consentManager = AgentConsentManager().apply { setForegroundActive(true) }
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        // Start with an empty allowlist — a fresh install scenario.
        every { prefs.mcpAllowedClients } returns flowOf(emptySet())
        coEvery { prefs.addMcpAllowedClient(any()) } returns Unit
        val auditRecorder = mockk<AgentAuditRecorder>(relaxed = true)
        val server = McpServer(
            context = mockk<Context>(relaxed = true),
            connectionRepository = mockk<ConnectionRepository>(relaxed = true),
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = auditRecorder,
            consentManager = consentManager,
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
            workspaceRepository = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true),
            workspaceLauncher = mockk<sh.haven.app.workspace.WorkspaceLauncher>(relaxed = true),
            tunnelConfigRepository = mockk<sh.haven.core.data.repository.TunnelConfigRepository>(relaxed = true),
            tunnelManager = mockk<sh.haven.core.tunnel.TunnelManager>(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk<sh.haven.core.knock.PortKnocker>(relaxed = true),
            spaSender = mockk<sh.haven.core.spa.SpaSender>(relaxed = true),
            connectionLogRepository = mockk<sh.haven.core.data.repository.ConnectionLogRepository>(relaxed = true),
            servedFileTracker = mockk<sh.haven.core.data.agent.ServedFileTracker>(relaxed = true),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = mockk<TerminalInputQueue>(relaxed = true),
            prootInstallLogRepository = mockk<sh.haven.core.data.repository.ProotInstallLogRepository>(relaxed = true),
            sshKeyRepository = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true),
            totpSecretRepository = mockk<sh.haven.core.data.repository.TotpSecretRepository>(relaxed = true),
            desktopSessionRegistry = mockk<sh.haven.core.data.desktop.DesktopSessionRegistry>(relaxed = true),
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
        )

        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            server.handleJsonRpc(initBody("fresh-client"))
        }

        // Wait for the pairing prompt to queue, then ALLOW it.
        val deadline = System.currentTimeMillis() + 5_000
        var pending = consentManager.pending.value
        while (pending.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            pending = consentManager.pending.value
        }
        assertFalse("pairing prompt never queued", pending.isEmpty())
        val req = pending.first()
        assertEquals(AgentConsentManager.PAIRING_TOOL_NAME, req.toolName)
        kotlinx.coroutines.runBlocking {
            consentManager.respond(req.id, sh.haven.core.data.agent.ConsentDecision.ALLOW)
        }

        val response = responseFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val obj = JSONObject(response)
        assertNull(
            "initialize should succeed after pairing ALLOW, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        // And the persisted allowlist should have been written.
        kotlinx.coroutines.runBlocking {
            io.mockk.coVerify { prefs.addMcpAllowedClient("fresh-client") }
        }
    }

    // --- Mcp-Session-Id streamable-HTTP session flow ---
    //
    // The pre-fix behaviour was: handleClient parsed the request body
    // and called dispatch with the global `lastClientHint`. On Haven
    // restart `lastClientHint` resets to null, but Claude Code's
    // cached transport keeps sending tools/* calls without re-doing
    // `initialize`. Every call hit dispatch's "not paired" gate,
    // surfaced -32001 to the user, and stayed wedged until they
    // dropped the whole Claude Code session (which forced the
    // transport to re-init).
    //
    // The fix wires `Mcp-Session-Id` per the 2025-06-18 streamable-
    // HTTP spec. These tests pin the new behaviour:
    //  - successful `initialize` returns a UUID in responseSessionId
    //  - a request carrying that UUID resolves clientName from the
    //    session map (not from the global lastClientHint)
    //  - a request carrying an unknown UUID returns httpStatus=404
    //    so the client per spec re-initialises automatically
    //  - a request without any Mcp-Session-Id header fails closed

    @Test
    fun `initialize returns Mcp-Session-Id in JsonRpcOutcome`() {
        val (server, _) = newServer()
        val outcome = server.handleJsonRpc(initBody(), requestSessionId = null)
        assertEquals(200, outcome.httpStatus)
        assertEquals(
            "initialize should mint a session id",
            true,
            !outcome.responseSessionId.isNullOrBlank(),
        )
    }

    @Test
    fun `request with valid session id resolves without falling back to lastClientHint`() {
        val (server, _) = newServer()
        val initOutcome = server.handleJsonRpc(initBody(), requestSessionId = null)
        val sid = initOutcome.responseSessionId
            ?: error("initialize did not return a session id")

        // Spec-shaped tools/list call carrying the session id. Use
        // tools/list (no extra consent gate) to keep the test focused
        // on the session resolution path.
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(toolsList, requestSessionId = sid)
        assertEquals(200, outcome.httpStatus)
        val obj = JSONObject(outcome.body)
        assertNull(
            "tools/list with a valid session id should succeed, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
    }

    @Test
    fun `unknown session id returns 404 to trigger client re-initialize`() {
        val (server, _) = newServer()
        // Don't initialize first — the session map starts empty, so
        // any presented id is unknown.
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 11)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(
            toolsList,
            requestSessionId = "stale-id-from-a-previous-server-instance",
        )
        assertEquals(
            "unknown session id must produce HTTP 404 so the client re-initialises",
            404,
            outcome.httpStatus,
        )
        assertEquals("404 body should be empty per spec hint", "", outcome.body)
        assertNull("no new session id minted on a 404", outcome.responseSessionId)
    }

    @Test
    fun `request without session id is rejected`() {
        val (server, _) = newServer()
        server.handleJsonRpc(initBody(), requestSessionId = null)
        val toolsList = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 13)
            .put("method", "tools/list")
            .toString()
        val outcome = server.handleJsonRpc(toolsList, requestSessionId = null)
        assertEquals(200, outcome.httpStatus)
        val obj = JSONObject(outcome.body)
        val error = obj.optJSONObject("error")
            ?: error("expected missing-session error, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
        assertTrue(error.optString("message").contains("Mcp-Session-Id"))
    }

    // --- Raw HTTP transport hardening ---

    @Test
    fun `raw HTTP rejects missing and incorrect bearer credentials`() {
        val (server, _) = newServer()
        val body = initBody()

        val missing = server.httpExchange(server.httpRequest(body, authorization = null))
        assertTrue(missing.startsWith("HTTP/1.1 401 Unauthorized\r\n"))

        val wrong = server.httpExchange(
            server.httpRequest(body, authorization = "Bearer definitely-not-the-token"),
        )
        assertTrue(wrong.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
    }

    @Test
    fun `raw HTTP rejects invalid and excessive content lengths`() {
        val (server, _) = newServer()
        val body = initBody()

        val negative = server.httpExchange(server.httpRequest(body, contentLength = "-1"))
        assertTrue(negative.startsWith("HTTP/1.1 400 Bad Request\r\n"))

        val excessive = server.httpExchange(server.httpRequest(body, contentLength = "1048577"))
        assertTrue(excessive.startsWith("HTTP/1.1 413 Payload Too Large\r\n"))
    }

    @Test
    fun `raw HTTP content length counts UTF-8 bytes`() {
        val (server, _) = newServer(pairedClients = setOf("测试-host"))
        val body = initBody(clientName = "测试-host")
        assertTrue(body.toByteArray(Charsets.UTF_8).size > body.length)

        val response = server.httpExchange(server.httpRequest(body))
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(response.contains("Mcp-Session-Id:"))
    }

    @Test
    fun `raw HTTP never enables wildcard CORS`() {
        val (server, _) = newServer()
        val postResponse = server.httpExchange(server.httpRequest(initBody()))
        assertFalse(postResponse.contains("Access-Control-Allow-Origin", ignoreCase = true))

        val optionsResponse = server.httpExchange(
            server.httpRequest("", method = "OPTIONS", contentLength = "0"),
        )
        assertTrue(optionsResponse.startsWith("HTTP/1.1 405 Method Not Allowed\r\n"))
        assertFalse(optionsResponse.contains("Access-Control-Allow-Origin", ignoreCase = true))
    }

    @Test
    fun `raw HTTP rejects ambiguous request framing`() {
        val (server, _) = newServer()
        val body = initBody()

        val duplicateLength = server.httpExchange(
            server.httpRequest(body, extraHeaders = listOf("Content-Length: 0")),
        )
        assertTrue(duplicateLength.startsWith("HTTP/1.1 400 Bad Request\r\n"))

        val chunked = server.httpExchange(
            server.httpRequest(body, extraHeaders = listOf("Transfer-Encoding: chunked")),
        )
        assertTrue(chunked.startsWith("HTTP/1.1 400 Bad Request\r\n"))
    }
}
