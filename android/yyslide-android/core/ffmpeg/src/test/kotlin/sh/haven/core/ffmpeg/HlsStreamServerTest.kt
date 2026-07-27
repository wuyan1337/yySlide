package sh.haven.core.ffmpeg

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsStreamServerTest {

    private val token = "A".repeat(43)

    @Test
    fun `switch index is read from query string`() {
        assertEquals(3, HlsStreamServer.parseSwitchIndex("/switch?i=3"))
        assertEquals(7, HlsStreamServer.parseSwitchIndex("/switch?mode=fast&i=7&autoplay=1"))
    }

    @Test
    fun `switch index rejects missing malformed and unrelated targets`() {
        assertNull(HlsStreamServer.parseSwitchIndex("/switch"))
        assertNull(HlsStreamServer.parseSwitchIndex("/switch?i=oops"))
        assertNull(HlsStreamServer.parseSwitchIndex("/switcheroo?i=1"))
        assertNull(HlsStreamServer.parseSwitchIndex("/other?i=1"))
    }

    @Test
    fun `player uses only authenticated same-origin static assets`() {
        val html = HlsStreamServer.buildPlayerHtml(token)

        assertFalse(html.contains("http://", ignoreCase = true))
        assertFalse(html.contains("https://", ignoreCase = true))
        assertFalse(html.contains("cdn.jsdelivr", ignoreCase = true))
        assertFalse(html.contains("@latest", ignoreCase = true))
        assertTrue(html.contains("src=\"/hls-1.6.16.min.js?token=$token\""))
        assertTrue(html.contains("src=\"/hls-player.js?token=$token\""))
        assertTrue(html.contains("href=\"/hls-player.css?token=$token\""))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<style>"))
    }

    @Test
    fun `static assets have strict paths mime types and immutable private caching`() {
        val hlsJs = HlsStreamServer.staticAssetForPath("/hls-1.6.16.min.js")
        val playerJs = HlsStreamServer.staticAssetForPath("/hls-player.js")
        val playerCss = HlsStreamServer.staticAssetForPath("/hls-player.css")

        assertEquals("application/javascript; charset=utf-8", hlsJs?.contentType)
        assertEquals("application/javascript; charset=utf-8", playerJs?.contentType)
        assertEquals("text/css; charset=utf-8", playerCss?.contentType)
        listOf(hlsJs, playerJs, playerCss).forEach { spec ->
            assertEquals("private, max-age=31536000, immutable", spec?.cacheControl)
        }
        assertNull(HlsStreamServer.staticAssetForPath("/assets/hls-1.6.16.min.js"))
        assertNull(HlsStreamServer.staticAssetForPath("/../hls-1.6.16.min.js"))
        assertNull(HlsStreamServer.staticAssetForPath("/%2e%2e/hls-1.6.16.min.js"))
    }

    @Test
    fun `player CSP forbids inline and third party scripts`() {
        val csp = HlsStreamServer.playerContentSecurityPolicy()

        assertTrue(csp.contains("default-src 'self'"))
        assertTrue(csp.contains("script-src 'self'"))
        assertTrue(csp.contains("style-src 'self'"))
        assertTrue(csp.contains("worker-src 'none'"))
        assertFalse(csp.contains("unsafe-inline"))
        assertFalse(csp.contains("unsafe-eval"))
        assertFalse(csp.contains("http:"))
        assertFalse(csp.contains("https:"))
    }

    @Test
    fun `URL credentials and tokens are redacted from ffmpeg logs`() {
        assertEquals(
            "Opening <redacted-url> failed",
            HlsStreamServer.redactUrlsForLog(
                "Opening http://user:password@127.0.0.1:8080/secret-token/file?token=secret failed",
            ),
        )
        assertEquals("frame=10 fps=30", HlsStreamServer.redactUrlsForLog("frame=10 fps=30"))
    }

    @Test
    fun `access token must be present correct and unique`() {
        assertTrue(HlsStreamServer.hasValidAccessToken("/?token=$token", token))
        assertTrue(HlsStreamServer.hasValidAccessToken("/stream.m3u8?t=1&token=$token", token))
        assertFalse(HlsStreamServer.hasValidAccessToken("/", token))
        assertFalse(HlsStreamServer.hasValidAccessToken("/?token=${"B".repeat(43)}", token))
        assertFalse(HlsStreamServer.hasValidAccessToken("/?token=$token&token=$token", token))
        assertFalse(HlsStreamServer.hasValidAccessToken("/?token=$token", "short"))
    }

    @Test
    fun `playlist segment key and map URIs receive the access token`() {
        val source = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="secret.key"
            #EXT-X-MAP:URI="init.mp4?version=1"
            #EXTINF:2.0,
            seg000.ts
        """.trimIndent() + "\n"

        val secured = HlsStreamServer.securePlaylist(source, token)

        assertTrue(secured.contains("URI=\"secret.key?token=$token\""))
        assertTrue(secured.contains("URI=\"init.mp4?version=1&token=$token\""))
        assertTrue(secured.contains("seg000.ts?token=$token"))
        assertTrue(secured.endsWith('\n'))
    }

    @Test
    fun `playlist rejects external traversal fragmented and preauthenticated URIs`() {
        listOf(
            "https://example.test/seg.ts",
            "//example.test/seg.ts",
            "../seg.ts",
            "%2e%2e%2fseg.ts",
            "seg.ts#fragment",
            "seg.ts?token=$token",
        ).forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) {
                HlsStreamServer.securePlaylist("#EXTM3U\n$uri\n", token)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HlsStreamServer.securePlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.test/key\"\n",
                token,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HlsStreamServer.securePlaylist(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=https://example.test/key\n",
                token,
            )
        }
    }

    @Test
    fun `HLS media paths are restricted to single safe filenames`() {
        assertEquals("seg001.ts", HlsStreamServer.resolveHlsMediaName("/seg001.ts"))
        assertEquals("init.mp4", HlsStreamServer.resolveHlsMediaName("/init.mp4"))
        listOf(
            "/../seg.ts",
            "/sub/seg.ts",
            "/%2e%2e%2fseg.ts",
            "/seg.ts/extra",
            "/stream.m3u8",
            "/seg001.exe",
        ).forEach { path -> assertNull(HlsStreamServer.resolveHlsMediaName(path)) }
    }

    @Test
    fun `local streams bind loopback and LAN streams bind wildcard`() {
        assertEquals("127.0.0.1", HlsStreamServer.bindAddressFor(localOnly = true))
        assertEquals("0.0.0.0", HlsStreamServer.bindAddressFor(localOnly = false))
    }

    @Test
    fun `bounded parser accepts GET and HEAD`() {
        assertEquals(
            HttpRequest("GET", "/?token=$token"),
            parse("GET /?token=$token HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"),
        )
        assertEquals(
            HttpRequest("HEAD", "/stream.m3u8?token=$token"),
            parse("HEAD /stream.m3u8?token=$token HTTP/1.0\r\nHost: localhost\r\n\r\n"),
        )
        assertTrue(HlsStreamServer.isSupportedHttpMethod("GET"))
        assertTrue(HlsStreamServer.isSupportedHttpMethod("HEAD"))
        assertFalse(HlsStreamServer.isSupportedHttpMethod("POST"))
        assertFalse(HlsStreamServer.isSupportedHttpMethod("OPTIONS"))
    }

    @Test
    fun `bounded parser rejects oversized request line`() {
        val error = assertThrows(HttpRequestException::class.java) {
            parse("GET /${"a".repeat(4_096)} HTTP/1.1\r\n\r\n")
        }
        assertEquals("414 URI Too Long", error.status)
    }

    @Test
    fun `bounded parser rejects oversized and excessive headers`() {
        val oversized = assertThrows(HttpRequestException::class.java) {
            parse("GET / HTTP/1.1\r\nX-Long: ${"a".repeat(8_193)}\r\n\r\n")
        }
        assertEquals("431 Request Header Fields Too Large", oversized.status)

        val headers = buildString {
            append("GET / HTTP/1.1\r\n")
            repeat(65) { append("X-$it: value\r\n") }
            append("\r\n")
        }
        val excessive = assertThrows(HttpRequestException::class.java) { parse(headers) }
        assertEquals("431 Request Header Fields Too Large", excessive.status)
    }

    @Test
    fun `bounded parser maps slow input timeout to request timeout`() {
        val timeoutInput = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("slow client")
        }

        val error = assertThrows(HttpRequestException::class.java) {
            HlsStreamServer.readHttpRequest(timeoutInput)
        }
        assertEquals("408 Request Timeout", error.status)
    }

    @Test
    fun `client limiter rejects the seventh concurrent connection`() {
        val limiter = ClientLimiter(6)
        repeat(6) { assertTrue(limiter.tryAcquire()) }

        assertFalse(limiter.tryAcquire())
        assertEquals(0, limiter.availablePermits())

        limiter.release()
        assertTrue(limiter.tryAcquire())
    }

    private fun parse(request: String): HttpRequest = HlsStreamServer.readHttpRequest(
        ByteArrayInputStream(request.toByteArray(StandardCharsets.US_ASCII)),
    )
}
