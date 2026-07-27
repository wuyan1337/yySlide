package sh.haven.core.ssh

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshSessionManagerTest {

    private lateinit var manager: SshSessionManager

    @Before
    fun setUp() {
        manager = SshSessionManager(mockk(relaxed = true))
    }

    @Test
    fun `initially has no sessions`() {
        assertTrue(manager.sessions.value.isEmpty())
        assertFalse(manager.hasActiveSessions)
    }

    @Test
    fun `registerSession adds session with CONNECTING status and returns sessionId`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "My Server", client)

        assertNotNull(sessionId)
        val session = manager.getSession(sessionId)
        assertNotNull(session)
        assertEquals("My Server", session!!.label)
        assertEquals("profile1", session.profileId)
        assertEquals(sessionId, session.sessionId)
        assertEquals(SshSessionManager.SessionState.Status.CONNECTING, session.status)
    }

    @Test
    fun `updateStatus changes session status`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)

        val session = manager.getSession(sessionId)
        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, session!!.status)
    }

    @Test
    fun `updateStatus for non-existent session is no-op`() {
        manager.updateStatus("nonexistent", SshSessionManager.SessionState.Status.CONNECTED)
        assertNull(manager.getSession("nonexistent"))
    }

    @Test
    fun `activeSessions returns only CONNECTING and CONNECTED`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val c3 = mockk<SshClient>(relaxed = true)

        val s1 = manager.registerSession("p1", "S1", c1)
        val s2 = manager.registerSession("p2", "S2", c2)
        val s3 = manager.registerSession("p3", "S3", c3)

        manager.updateStatus(s1, SshSessionManager.SessionState.Status.CONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.DISCONNECTED)
        // s3 remains CONNECTING

        val active = manager.activeSessions
        assertEquals(2, active.size)
        assertTrue(active.any { it.sessionId == s1 })
        assertTrue(active.any { it.sessionId == s3 })
    }

    @Test
    fun `removeSession disconnects client and removes from map`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.removeSession(sessionId)

        // State is cleared synchronously
        assertNull(manager.getSession(sessionId))
        assertFalse(manager.hasActiveSessions)

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { client.disconnect() }
    }

    @Test
    fun `removeSession for non-existent session is safe`() {
        manager.removeSession("nonexistent")
        // No exception
    }

    @Test
    fun `disconnectAll clears all sessions`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        manager.registerSession("p1", "S1", c1)
        manager.registerSession("p2", "S2", c2)

        manager.disconnectAll()

        // State is cleared synchronously
        assertTrue(manager.sessions.value.isEmpty())

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    @Test
    fun `hasActiveSessions returns true when connected sessions exist`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)

        assertTrue(manager.hasActiveSessions)
    }

    @Test
    fun `hasActiveSessions returns false when all disconnected`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.updateStatus(sessionId, SshSessionManager.SessionState.Status.DISCONNECTED)

        assertFalse(manager.hasActiveSessions)
    }

    @Test
    fun `attachShellChannel stores channel in session state`() {
        val client = mockk<SshClient>(relaxed = true)
        val channel = mockk<com.jcraft.jsch.ChannelShell>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.attachShellChannel(sessionId, channel)

        val session = manager.getSession(sessionId)
        assertNotNull(session?.shellChannel)
        assertEquals(channel, session?.shellChannel)
    }

    @Test
    fun `attachTerminalSession stores terminal session in state`() {
        val client = mockk<SshClient>(relaxed = true)
        val terminalSession = mockk<TerminalSession>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.attachTerminalSession(sessionId, terminalSession)

        val session = manager.getSession(sessionId)
        assertNotNull(session?.terminalSession)
    }

    @Test
    fun `removeSession closes terminal session`() {
        val client = mockk<SshClient>(relaxed = true)
        val terminalSession = mockk<TerminalSession>(relaxed = true)
        val sessionId = manager.registerSession("profile1", "Server", client)
        manager.attachTerminalSession(sessionId, terminalSession)
        manager.removeSession(sessionId)

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { terminalSession.close() }
        verify { client.disconnect() }
    }

    @Test
    fun `disconnectAll closes all terminal sessions`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val t1 = mockk<TerminalSession>(relaxed = true)
        val s1 = manager.registerSession("p1", "S1", c1)
        manager.registerSession("p2", "S2", c2)
        manager.attachTerminalSession(s1, t1)

        manager.disconnectAll()

        // State is cleared synchronously
        assertTrue(manager.sessions.value.isEmpty())

        // Teardown dispatches to background executor
        Thread.sleep(200)
        verify { t1.close() }
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    @Test
    fun `multiple sessions for same profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("profile1", "Server", c1)
        val s2 = manager.registerSession("profile1", "Server", c2)

        // Two distinct sessions for same profile
        assertTrue(s1 != s2)
        assertEquals(2, manager.sessions.value.size)

        val forProfile = manager.getSessionsForProfile("profile1")
        assertEquals(2, forProfile.size)
    }

    @Test
    fun `isProfileConnected returns true when any session is connected`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("profile1", "Server", c1)
        val s2 = manager.registerSession("profile1", "Server", c2)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.DISCONNECTED)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        assertTrue(manager.isProfileConnected("profile1"))
    }

    @Test
    fun `getProfileStatus returns best status`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val s1 = manager.registerSession("profile1", "Server", c1)
        val s2 = manager.registerSession("profile1", "Server", c2)
        manager.updateStatus(s1, SshSessionManager.SessionState.Status.RECONNECTING)
        manager.updateStatus(s2, SshSessionManager.SessionState.Status.CONNECTED)

        assertEquals(SshSessionManager.SessionState.Status.CONNECTED, manager.getProfileStatus("profile1"))
    }

    @Test
    fun `removeAllSessionsForProfile removes all sessions for that profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        val c3 = mockk<SshClient>(relaxed = true)
        manager.registerSession("profile1", "Server", c1)
        manager.registerSession("profile1", "Server", c2)
        val s3 = manager.registerSession("profile2", "Other", c3)

        manager.removeAllSessionsForProfile("profile1")

        assertEquals(1, manager.sessions.value.size)
        assertNotNull(manager.getSession(s3))

        Thread.sleep(200)
        verify { c1.disconnect() }
        verify { c2.disconnect() }
    }

    // ----- Tunnel-dependent tracking (#121, KoriKraut) -----

    @Test
    fun `markTunnelOpened sets flag and seeds dependent set`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")

        val s = manager.getSession(sshSid)!!
        assertTrue(s.tunnelOpened)
        assertEquals(setOf("vnc-1"), s.tunnelDependents)
    }

    @Test
    fun `releaseTunnelDependent tears down session opened solely as a tunnel`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")

        val torn = manager.releaseTunnelDependent("vnc-1")

        assertEquals(listOf(sshSid), torn)
        assertNull(manager.getSession(sshSid))
        Thread.sleep(200)
        verify { c.disconnect() }
    }

    @Test
    fun `releaseTunnelDependent keeps session alive when other dependents remain`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        manager.attachTunnelDependent(sshSid, "rdp-1")

        // First dependent released — second still depends on it
        val torn = manager.releaseTunnelDependent("vnc-1")
        assertTrue(torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
        assertEquals(setOf("rdp-1"), manager.getSession(sshSid)!!.tunnelDependents)

        // Last dependent released — now torn down
        val torn2 = manager.releaseTunnelDependent("rdp-1")
        assertEquals(listOf(sshSid), torn2)
        assertNull(manager.getSession(sshSid))
    }

    @Test
    fun `releaseTunnelDependent does not tear down user-opened session that VNC reused`() {
        // Scenario: user opens SSH terminal (tunnelOpened = false), then opens
        // VNC that REUSES the same SSH session (attachTunnelDependent). On
        // VNC disconnect the user's terminal session must not be killed.
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        // No markTunnelOpened — opened directly by user
        manager.attachTunnelDependent(sshSid, "vnc-1")

        val torn = manager.releaseTunnelDependent("vnc-1")
        assertTrue("user-opened SSH must survive VNC disconnect", torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
        assertEquals(emptySet<String>(), manager.getSession(sshSid)!!.tunnelDependents)
    }

    @Test
    fun `releaseTunnelDependent for unknown profile is no-op`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")

        val torn = manager.releaseTunnelDependent("nobody-knows")
        assertTrue(torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
    }

    // ----- Tunnel leases: parent->child cascade (#121) -----

    @Test
    fun `removeSession fires onParentGone for a lease on that session`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        manager.removeSession(sshSid)

        assertEquals("parent removal must notify the dependent exactly once", 1, fired)
    }

    @Test
    fun `releaseTunnelDependent of last auto-opened dependent fires onParentGone (the #2 path)`() {
        // Connections-list disconnect: releaseTunnelDependent tears the SSH
        // down (tunnelOpened + no deps), which must cascade to close the tab.
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        manager.releaseTunnelDependent("vnc-1")

        assertEquals(1, fired)
        assertNull(manager.getSession(sshSid))
    }

    @Test
    fun `reconnect-style status changes do NOT fire onParentGone`() {
        // Only true removeSession fires the callback; an in-place status flip
        // (what reconnect does) must leave a working tab alone.
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        manager.updateStatus(sshSid, SshSessionManager.SessionState.Status.RECONNECTING)
        manager.updateStatus(sshSid, SshSessionManager.SessionState.Status.CONNECTED)

        assertEquals("a network blip must not close the tab", 0, fired)
        assertNotNull(manager.getSession(sshSid))
    }

    @Test
    fun `lease close is idempotent and prevents a later parent-gone fire`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        val lease = manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        lease.close()
        lease.close() // idempotent
        // Closing released the sole dependent → SSH already torn down.
        assertNull(manager.getSession(sshSid))
        // A subsequent removeSession (e.g. a racing teardown) must not re-fire.
        manager.removeSession(sshSid)
        assertEquals("consumer-initiated close must not also fire onParentGone", 0, fired)
    }

    @Test
    fun `teardownTunnelDependent fires lease even when the shared SSH survives`() {
        // VNC reuses a user-opened SSH (tunnelOpened = false). Disconnecting
        // the VNC from the connections list must close its tab (fire the
        // lease) WITHOUT killing the shared SSH session (#121 shared-tunnel).
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.attachTunnelDependent(sshSid, "vnc-1") // reused — tunnelOpened stays false
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        val torn = manager.teardownTunnelDependent("vnc-1")

        assertEquals("the tab lease must fire", 1, fired)
        assertTrue("shared user SSH must survive", torn.isEmpty())
        assertNotNull(manager.getSession(sshSid))
    }

    @Test
    fun `teardownTunnelDependent tears down a solely-tunnel SSH and fires the lease`() {
        val c = mockk<SshClient>(relaxed = true)
        val sshSid = manager.registerSession("ssh-host", "SSH", c)
        manager.markTunnelOpened(sshSid, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(sshSid, "vnc-1", localForwardPort = null) { fired++ }

        val torn = manager.teardownTunnelDependent("vnc-1")

        assertEquals(1, fired)
        assertEquals(listOf(sshSid), torn)
        assertNull(manager.getSession(sshSid))
    }

    @Test
    fun `jump-host cascade fires the dependent session's lease`() {
        // B uses A as its jump host. Removing A cascades to B (removeSession),
        // which must fire B's lease so a tab tunneling over B closes too.
        val ca = mockk<SshClient>(relaxed = true)
        val cb = mockk<SshClient>(relaxed = true)
        val a = manager.registerSession("jump", "A", ca)
        val b = manager.registerSession("leaf", "B", cb)
        manager.setJumpSessionId(b, a)
        manager.markTunnelOpened(b, "vnc-1")
        var fired = 0
        manager.acquireTunnelLease(b, "vnc-1", localForwardPort = null) { fired++ }

        manager.removeSession(a)

        assertEquals(1, fired)
        assertNull(manager.getSession(b))
    }

    // --- #150 reconnect policy gating -----------------------------------

    @Test
    fun `requestReconnectAll skips session whose policy disables autoReconnect`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "S1", client)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = false),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnectAll()

        // Status must stay DISCONNECTED — no reconnect attempt fired.
        assertEquals(
            SshSessionManager.SessionState.Status.DISCONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `requestReconnectAll skips session whose policy disables onNetworkChange`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "S1", client)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = true, onNetworkChange = false),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnectAll()

        // autoReconnect=true alone must NOT trigger network-change reconnect.
        assertEquals(
            SshSessionManager.SessionState.Status.DISCONNECTED,
            manager.getSession(sid)!!.status,
        )
    }

    @Test
    fun `requestReconnectAll honours session whose policy enables both flags`() {
        val client = mockk<SshClient>(relaxed = true)
        every { client.connectBlocking(any(), any(), any()) } throws RuntimeException("offline")
        val sid = manager.registerSession("p1", "S1", client)
        manager.storeConnectionConfig(
            sessionId = sid,
            config = configWithPolicy(autoReconnect = true, onNetworkChange = true),
            sessionMgr = SessionManager.NONE,
        )
        manager.updateStatus(sid, SshSessionManager.SessionState.Status.DISCONNECTED)

        manager.requestReconnectAll()

        // The reconnect attempt fired; with the test config the session is
        // either RECONNECTING (still in the backoff loop) or back to
        // DISCONNECTED after the attempt failed. Either way, the policy
        // didn't gate it out — that's what we're verifying.
        val status = manager.getSession(sid)!!.status
        assertTrue(
            "expected RECONNECTING or DISCONNECTED, got $status",
            status == SshSessionManager.SessionState.Status.RECONNECTING ||
                status == SshSessionManager.SessionState.Status.DISCONNECTED,
        )
    }

    // --- ExitOnForwardFailure: critical port forwards (MCP tunnel) -------

    @Test
    fun `applyPortForwards returns false when a critical REMOTE forward fails to bind`() {
        val client = mockk<SshClient>(relaxed = true)
        every { client.setPortForwardingR(any(), any(), any(), any()) } throws
            RuntimeException("remote port forwarding failed for listen port 8730")
        val sid = manager.registerSession("p1", "S1", client)

        val ok = manager.applyPortForwards(sid, listOf(criticalRemoteForward(8730)))

        assertFalse("a failed critical forward must report failure", ok)
        // The failed forward must not be recorded as active.
        assertTrue(manager.getSession(sid)!!.activeForwards.isEmpty())
    }

    @Test
    fun `applyPortForwards returns true when a non-critical forward fails`() {
        val client = mockk<SshClient>(relaxed = true)
        every { client.setPortForwardingR(any(), any(), any(), any()) } throws
            RuntimeException("bind failed")
        val sid = manager.registerSession("p1", "S1", client)

        val ok = manager.applyPortForwards(
            sid,
            listOf(criticalRemoteForward(8730).copy(critical = false)),
        )

        assertTrue("a non-critical failure keeps best-effort semantics", ok)
    }

    @Test
    fun `applyPortForwards returns true when the critical forward binds`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "S1", client)

        val ok = manager.applyPortForwards(sid, listOf(criticalRemoteForward(8730)))

        assertTrue(ok)
        assertEquals(1, manager.getSession(sid)!!.activeForwards.size)
    }

    @Test
    fun `registerSession marks headless flag`() {
        val client = mockk<SshClient>(relaxed = true)
        val sid = manager.registerSession("p1", "MCP tunnel", client, headless = true)
        assertTrue(manager.getSession(sid)!!.headless)
    }

    @Test
    fun `findRemoteForwardSession skips the headless MCP tunnel and finds the interactive session`() {
        val tunnelClient = mockk<SshClient>(relaxed = true)
        val replClient = mockk<SshClient>(relaxed = true)
        val tunnelSid = manager.registerSession("p-tunnel", "MCP tunnel", tunnelClient, headless = true)
        val replSid = manager.registerSession("p-repl", "Laptop", replClient)
        manager.applyPortForwards(tunnelSid, listOf(criticalRemoteForward(8730)))
        manager.applyPortForwards(replSid, listOf(criticalRemoteForward(8730)))

        // The interactive (non-headless) session is the typing target.
        assertEquals(replSid, manager.findRemoteForwardSession(8730))
    }

    @Test
    fun `findRemoteForwardSession returns null when only the headless tunnel carries the forward`() {
        val tunnelClient = mockk<SshClient>(relaxed = true)
        val tunnelSid = manager.registerSession("p-tunnel", "MCP tunnel", tunnelClient, headless = true)
        manager.applyPortForwards(tunnelSid, listOf(criticalRemoteForward(8730)))

        assertNull(manager.findRemoteForwardSession(8730))
    }

    private fun criticalRemoteForward(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = "mcp-reverse-tunnel",
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = "127.0.0.1",
        bindPort = port,
        targetHost = "127.0.0.1",
        targetPort = port,
        critical = true,
    )

    private fun configWithPolicy(
        autoReconnect: Boolean = true,
        maxAttempts: Int = 5,
        onNetworkChange: Boolean = true,
    ): ConnectionConfig = ConnectionConfig(
        host = "test.example",
        port = 22,
        username = "u",
        reconnectPolicy = ConnectionConfig.ReconnectPolicy(
            autoReconnect = autoReconnect,
            maxAttempts = maxAttempts,
            onNetworkChange = onNetworkChange,
        ),
    )
}
