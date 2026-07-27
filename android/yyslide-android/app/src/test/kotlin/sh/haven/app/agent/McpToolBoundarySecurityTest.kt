package sh.haven.app.agent

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.GuestServiceManager

class McpToolBoundarySecurityTest {

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw t
        }
        fail("Expected ${T::class.java.simpleName}")
        throw AssertionError("unreachable")
    }

    @Test
    fun `play_file URI policy only grants own FileProvider content URI`() {
        val authority = "com.yyslide.android.fileprovider"
        val https = validateAgentPlayableUri("https://media.example/video.mp4", authority)
        assertFalse(https.grantReadPermission)

        val ownContent = validateAgentPlayableUri(
            "content://com.yyslide.android.fileprovider/cache/video.mp4",
            authority,
        )
        assertTrue(ownContent.grantReadPermission)

        for (url in listOf(
            "content://com.other.provider/private/video.mp4",
            "file:///sdcard/private.mp4",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "custom://host/value",
        )) {
            expectThrows<IllegalArgumentException> { validateAgentPlayableUri(url, authority) }
        }
    }

    @Test
    fun `agent HLS launch is loopback-only and returns authenticated helper URLs`() {
        val server = mockk<HlsStreamServer>()
        val input = "http://127.0.0.1:8123/source"
        every { server.startFile(input, localOnly = true) } returns 9042
        every { server.playlistUrl("127.0.0.1") } returns "http://127.0.0.1:9042/stream.m3u8?token=secret"
        every { server.playerUrl("127.0.0.1") } returns "http://127.0.0.1:9042/?token=secret"

        val launch = startAgentLocalHls(server, input)

        assertEquals(9042, launch.port)
        assertTrue(launch.playlistUrl.contains("token="))
        assertTrue(launch.playerUrl.contains("token="))
        verify(exactly = 1) { server.startFile(input, localOnly = true) }
    }

    @Test
    fun `guest service projection never leaks command environment or error output`() {
        val secret = "SUPER_SECRET_TOKEN=do-not-leak"
        val spec = GuestServiceManager.GuestServiceSpec(
            id = "gsvc-1",
            label = "Safe label",
            command = "$secret python server.py --password hunter2",
            port = 8766,
            autostart = true,
            isMcp = true,
            mcpPath = "/mcp?token=$secret",
        )
        val instance = GuestServiceManager.GuestServiceInstance(
            spec = spec,
            state = GuestServiceManager.ServiceState.ERROR,
            errorMessage = "process echoed $secret and password hunter2",
        )

        val result = safeGuestServiceJson(instance, spec)
        val encoded = result.toString()
        assertEquals("gsvc-1", result.getString("id"))
        assertEquals("Safe label", result.getString("label"))
        assertEquals(8766, result.getInt("port"))
        assertTrue(result.getBoolean("hasError"))
        assertFalse(result.has("command"))
        assertFalse(result.has("errorMessage"))
        assertFalse(result.has("mcpPath"))
        assertFalse(encoded.contains(secret))
        assertFalse(encoded.contains("hunter2"))
    }
}
