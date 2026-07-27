package sh.haven.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pin the reverse-forward liveness-probe interpretation used by
 * [McpTunnelManager]'s watchdog. The probe asks the tunnel endpoint to HTTP-GET
 * the forwarded MCP port; this parser turns the endpoint's `P:<code>:<rc>` line
 * into the ALIVE / DEAD / SKIP decision that drives a wedged-forward reconnect.
 * Getting DEAD wrong = either a missed wedge (the bug we're fixing) or a
 * spurious reconnect storm, so the boundaries are worth nailing down.
 */
class McpTunnelProbeTest {

    @Test
    fun `http status code means the forward round-tripped — ALIVE`() {
        // The MCP server answers a bare GET with 405/406; any response proves it.
        assertEquals(Probe.ALIVE, interpretProbeLine("P:405:0"))
        assertEquals(Probe.ALIVE, interpretProbeLine("P:406:0"))
        assertEquals(Probe.ALIVE, interpretProbeLine("P:200:0"))
    }

    @Test
    fun `curl timeout on a wedged forward is DEAD`() {
        // code 000, rc 28 (curl --max-time exceeded) — connection accepted but
        // no response came back through the stuck channel.
        assertEquals(Probe.DEAD, interpretProbeLine("P:000:28"))
    }

    @Test
    fun `connection refused — dead listener — is DEAD`() {
        assertEquals(Probe.DEAD, interpretProbeLine("P:000:7"))
    }

    @Test
    fun `endpoint without curl is SKIP, not a false reconnect`() {
        assertEquals(Probe.SKIP, interpretProbeLine("P:NOCURL:127"))
    }

    @Test
    fun `unrecognised or empty output is SKIP`() {
        assertEquals(Probe.SKIP, interpretProbeLine(""))
        assertEquals(Probe.SKIP, interpretProbeLine("some unrelated banner\n"))
    }

    @Test
    fun `probe line is found among surrounding noise and trimmed`() {
        assertEquals(Probe.ALIVE, interpretProbeLine("login banner\n  P:405:0  \nbye"))
    }

    @Test
    fun `non-3-digit codes are not treated as ALIVE`() {
        assertEquals(Probe.DEAD, interpretProbeLine("P:0:0"))
        assertEquals(Probe.DEAD, interpretProbeLine("P:99:0"))
        assertEquals(Probe.DEAD, interpretProbeLine("P::28"))
    }
}
