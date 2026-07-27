package sh.haven.app.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpServerConfigTest {

    @Test
    fun `endpoint URL formats IPv4 and IPv6 authorities`() {
        assertEquals("http://10.8.0.2:8737/mcp", buildMcpEndpointUrl("10.8.0.2", 8737))
        assertEquals("http://[fd00::2]:8737/mcp", buildMcpEndpointUrl("fd00::2", 8737))
        assertEquals("http://[fd00::2]:8737/mcp", buildMcpEndpointUrl("[fd00::2]", 8737))
    }

    @Test
    fun `client config uses live URL and bearer authorization`() {
        val root = JSONObject(
            buildMcpServerConfigJson(
                endpointUrl = "http://127.0.0.1:8737/mcp",
                accessToken = "test-token",
            ),
        )
        val servers = root.getJSONObject("mcpServers")
        val config = servers.getJSONObject("yyslide")

        assertFalse(servers.has("haven"))
        assertEquals("http", config.getString("type"))
        assertEquals("http://127.0.0.1:8737/mcp", config.getString("url"))
        assertEquals(
            "Bearer test-token",
            config.getJSONObject("headers").getString("Authorization"),
        )
    }

    @Test
    fun `client config preserves a bracketed WireGuard IPv6 URL`() {
        val endpointUrl = buildMcpEndpointUrl("fd00::2", 8739)
        val config = JSONObject(buildMcpServerConfigJson(endpointUrl, "wg-token"))
            .getJSONObject("mcpServers")
            .getJSONObject("yyslide")

        assertEquals("http://[fd00::2]:8739/mcp", config.getString("url"))
        assertEquals(
            "Bearer wg-token",
            config.getJSONObject("headers").getString("Authorization"),
        )
    }
}
