package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer

/**
 * Pin the consent metadata + summary builders for every registered MCP
 * tool. The audit log relies on consent-gating decisions made *before*
 * the handler runs, so a regression here (e.g. dropping a level back to
 * NEVER on a destructive tool, or breaking the summary template) would
 * silently weaken the wheel-stays-with-the-user invariant from
 * VISION.md §85.
 *
 * We construct a real [McpTools] with relaxed mocks so the registry is
 * the actual production registry — same code path the dispatcher
 * consults via `consentFor(name)`.
 */
class McpToolsConsentTest {

    private fun newTools(
        localSessionManager: LocalSessionManager = mockk(relaxed = true),
    ): McpTools {
        val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
        // profileLabel(...) hits this; return a stable label so the
        // summary templates that interpolate it are predictable.
        coEvery { connectionRepository.getById(any()) } returns
            ConnectionProfile(
                id = "p1",
                label = "test-host",
                host = "10.0.0.1",
                username = "u",
            )
        return McpTools(
            context = mockk<Context>(relaxed = true),
            connectionRepository = connectionRepository,
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = localSessionManager,
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
            servedFileTracker = sh.haven.core.data.agent.ServedFileTracker(),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = mockk<TerminalInputQueue>(relaxed = true),
            prootInstallLogRepository = mockk<sh.haven.core.data.repository.ProotInstallLogRepository>(relaxed = true),
            sshKeyRepository = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true),
            totpSecretRepository = mockk<sh.haven.core.data.repository.TotpSecretRepository>(relaxed = true),
            desktopSessionRegistry = mockk<sh.haven.core.data.desktop.DesktopSessionRegistry>(relaxed = true),
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
        )
    }

    @Test
    fun `read-only and tap-equivalent tools are NEVER`() {
        val tools = newTools()
        for (name in listOf(
            "get_app_info",
            "list_connections",
            "list_sessions",
            "list_rclone_remotes",
            // Unified backend-agnostic listing (#127). The two
            // per-backend tools below are deprecated aliases pointing
            // at this one.
            "list_directory",
            "list_rclone_directory",
            "list_sftp_directory",
            "read_terminal_scrollback",
            // Navigation + dialog-staging: open a screen at a path or
            // stage a dialog with prefilled args. The destructive action
            // (transcode, in the convert case) still requires the user
            // to tap Convert in the dialog. Tap-equivalent, no consent.
            "navigate_sftp_browser",
            "focus_terminal_session",
            "open_convert_dialog_with_args",
            // Listing the user's saved workspaces is read-only and
            // surfaces no secrets — no consent.
            "list_workspaces",
            // Enumerating attached USB devices is read-only; descriptor
            // strings stay hidden until permission is separately granted.
            "list_usb_devices",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, c.level)
        }
    }

    @Test
    fun `connection lifecycle tools are ONCE_PER_SESSION`() {
        val tools = newTools()
        for (name in listOf(
            "disconnect_profile",
            "add_port_forward",
            "remove_port_forward",
            // compose_workspace can launch many sessions in one go;
            // gate it once per (client, tool) so the user grants the
            // bundle and isn't re-prompted per-item underneath.
            "compose_workspace",
            // send_terminal_input types into a live terminal; gated once per
            // (client, tool) so an agent isn't re-prompted on every keystroke
            // batch. Shipped behaviour since the queue_terminal_input split;
            // this assertion was masked while the suite didn't compile.
            "send_terminal_input",
            // request_usb_permission pops the system grant dialog + opens
            // the device; gate once per (client, tool) so the agent isn't
            // re-prompted each time it reopens the same device.
            "request_usb_permission",
            // usb_attach_to_guest opens the device + exposes it to the guest;
            // a session-scoped grant matches the permission grant above.
            "usb_attach_to_guest",
            // test_spa sends a single SPA packet; one grant per (client, tool)
            // mirrors test_port_knock.
            "test_spa",
            // Opens/reuses a PRoot session and may persist its profile.
            "open_local_shell",
            // Stops a running service/ffmpeg process, but a session grant is
            // sufficient for iterative service/media management.
            "stop_guest_service",
            "stop_stream",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals(
                "$name should re-prompt only once per (client, tool)",
                ConsentLevel.ONCE_PER_SESSION,
                c.level,
            )
        }
    }

    @Test
    fun `destructive write tools are EVERY_CALL`() {
        val tools = newTools()
        for (name in listOf(
            // Unified backend-agnostic write/delete (#127).
            "upload_file",
            "delete_file",
            // Deprecated SSH-only originals — kept for backward compat,
            // still EVERY_CALL.
            "upload_file_to_sftp",
            "delete_sftp_file",
            "convert_file",
            "read_clipboard",
            "view_file",
            "read_guest_file",
            "set_terminal_font_from_url",
            "install_apk_from_url",
            "install_apk_from_backend",
            "stream_sftp_file",
            "play_file",
            "unregister_guest_service",
            "enable_wireless_adb",
            // Raw USB I/O to a device — re-confirm every transfer.
            "usb_control_transfer",
            "usb_bulk_transfer",
            // set_spa writes SPA key material onto a profile.
            "set_spa",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals(
                "$name must prompt on every call",
                ConsentLevel.EVERY_CALL,
                c.level,
            )
        }
    }

    @Test
    fun `deprecated tool descriptions point at the unified replacement`() {
        // Pin the deprecation breadcrumbs so a future renamer doesn't
        // silently drop the migration hint. Agents that still use the
        // old tool names should be nudged toward the new ones via the
        // tools/list response.
        val tools = newTools()
        val defs = tools.definitions().associateBy { it.optString("name") }
        for ((deprecated, replacement) in mapOf(
            "list_sftp_directory" to "list_directory",
            "list_rclone_directory" to "list_directory",
            "upload_file_to_sftp" to "upload_file",
            "delete_sftp_file" to "delete_file",
        )) {
            val desc = defs[deprecated]?.optString("description")
                ?: error("$deprecated missing")
            assertTrue(
                "$deprecated description must mention DEPRECATED, got: $desc",
                desc.contains("DEPRECATED", ignoreCase = true),
            )
            assertTrue(
                "$deprecated description must point at $replacement, got: $desc",
                desc.contains(replacement),
            )
        }
    }

    @Test
    fun `unknown tool returns null`() {
        assertNull(newTools().consentFor("does_not_exist"))
    }

    @Test
    fun `disconnect_profile summary names the profile label`() {
        val tools = newTools()
        val c = tools.consentFor("disconnect_profile")!!
        val summary = c.summary(JSONObject().put("profileId", "p1"))
        assertTrue(
            "summary must mention the human label, got: $summary",
            summary.contains("test-host"),
        )
        assertTrue(summary.contains("Disconnect"))
    }

    @Test
    fun `add_port_forward summary describes the rule`() {
        val tools = newTools()
        val c = tools.consentFor("add_port_forward")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("type", "LOCAL")
            .put("bindPort", 8443)
            .put("targetHost", "10.0.0.5")
            .put("targetPort", 443))
        assertTrue("got: $summary", summary.contains("LOCAL"))
        assertTrue("got: $summary", summary.contains("8443"))
        assertTrue("got: $summary", summary.contains("10.0.0.5:443"))
        assertTrue("got: $summary", summary.contains("test-host"))
    }

    @Test
    fun `add_port_forward summary handles DYNAMIC without target host`() {
        val tools = newTools()
        val c = tools.consentFor("add_port_forward")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("type", "DYNAMIC")
            .put("bindPort", 1080))
        // No targetHost on DYNAMIC, but the builder must not crash and
        // must still mention the bind port.
        assertTrue("got: $summary", summary.contains("DYNAMIC"))
        assertTrue("got: $summary", summary.contains("1080"))
        assertTrue("got: $summary", summary.contains("(SOCKS)"))
    }

    @Test
    fun `delete_sftp_file summary shows path and host`() {
        val tools = newTools()
        val c = tools.consentFor("delete_sftp_file")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("path", "/var/log/build.log"))
        assertTrue("got: $summary", summary.contains("/var/log/build.log"))
        assertTrue("got: $summary", summary.contains("test-host"))
    }

    @Test
    fun `send_terminal_input summary shows literal payload (truncated)`() {
        val tools = newTools()
        val c = tools.consentFor("send_terminal_input")!!
        val short = c.summary(JSONObject()
            .put("sessionId", "s1")
            .put("text", "ls -la\n"))
        assertTrue("must show the typed text literally, got: $short",
            short.contains("ls -la"))
        // Newline is rendered as \n so the user sees it. Otherwise an
        // input ending with Enter looks the same as one that doesn't.
        assertTrue("newline must be visible in the summary, got: $short",
            short.contains("\\n"))

        // Long input is truncated so the prompt stays readable.
        val long = "x".repeat(200)
        val summary = c.summary(JSONObject().put("sessionId", "s1").put("text", long))
        assertTrue("long input must be truncated with ellipsis, got: ${summary.length} chars",
            summary.length < 200)
        assertTrue("got: $summary", summary.contains("…"))
    }

    @Test
    fun `convert_file summary names the codecs`() {
        val tools = newTools()
        val c = tools.consentFor("convert_file")!!
        val summary = c.summary(JSONObject()
            .put("sourceUrl", "http://127.0.0.1:8080/in.mkv")
            .put("container", "mp4")
            .put("videoEncoder", "libx264")
            .put("audioEncoder", "aac"))
        assertTrue("got: $summary", summary.contains("mp4"))
        assertTrue("got: $summary", summary.contains("libx264"))
        assertTrue("got: $summary", summary.contains("aac"))
    }

    @Test
    fun `summary builder is robust to missing optional args`() {
        val tools = newTools()
        // upload_file_to_sftp summary opens File(localPath) — passing a
        // non-existent path must yield a sane string, not a crash.
        val upload = tools.consentFor("upload_file_to_sftp")!!
        val s = upload.summary(JSONObject()
            .put("profileId", "p1")
            .put("localPath", "/no/such/file")
            .put("remotePath", "/dest"))
        assertTrue("got: $s", s.contains("/dest"))
        assertTrue("got: $s", s.contains("test-host"))
        assertFalse(
            "must not crash on missing file — should report unknown size",
            s.isBlank(),
        )
    }

    @Test
    fun `install_apk_from_url summary surfaces the source as a warning`() {
        val tools = newTools()
        val c = tools.consentFor("install_apk_from_url")!!
        val s = c.summary(JSONObject()
            .put("url", "https://example.com/app.apk"))
        // The summary needs to make the trust call obvious — APK install
        // is the highest-risk verb in the registry.
        assertTrue("got: $s", s.contains("APK") || s.contains("install"))
        assertTrue("got: $s", s.contains("example.com") || s.contains("trusted"))
    }

    @Test
    fun `install_apk_from_url schema requires canonical sha256`() {
        val definition = newTools().definitions()
            .first { it.getString("name") == "install_apk_from_url" }
        val schema = definition.getJSONObject("inputSchema")
        val required = schema.getJSONArray("required")
        assertTrue((0 until required.length()).any { required.getString(it) == "url" })
        assertTrue((0 until required.length()).any { required.getString(it) == "sha256" })
        val sha = schema.getJSONObject("properties").getJSONObject("sha256")
        assertEquals(64, sha.getInt("minLength"))
        assertEquals(64, sha.getInt("maxLength"))
        assertEquals("^[0-9A-Fa-f]{64}$", sha.getString("pattern"))
    }

    @Test
    fun `guest service consent shows label and id without command secrets`() {
        val local = mockk<LocalSessionManager>(relaxed = true)
        val services = mockk<GuestServiceManager>(relaxed = true)
        every { local.guestServiceManager } returns services
        every { services.registered() } returns listOf(
            GuestServiceManager.GuestServiceSpec(
                id = "gsvc-42",
                label = "CAD bridge",
                command = "SECRET_TOKEN=never-show python server.py",
                port = 8766,
                autostart = true,
            ),
        )
        val tools = newTools(local)
        for (name in listOf("stop_guest_service", "unregister_guest_service")) {
            val summary = tools.consentFor(name)!!.summary(JSONObject().put("id", "gsvc-42"))
            assertTrue("got: $summary", summary.contains("CAD bridge"))
            assertTrue("got: $summary", summary.contains("gsvc-42"))
            assertFalse("got: $summary", summary.contains("SECRET_TOKEN"))
        }

        val registerSummary = tools.consentFor("register_guest_service")!!.summary(
            JSONObject()
                .put("label", "CAD bridge")
                .put("command", "SECRET_TOKEN=never-show python server.py")
                .put("port", 8766),
        )
        assertTrue("got: $registerSummary", registerSummary.contains("CAD bridge"))
        assertTrue("got: $registerSummary", registerSummary.contains("8766"))
        assertFalse("got: $registerSummary", registerSummary.contains("SECRET_TOKEN"))
    }
}
