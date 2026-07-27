package sh.haven.feature.sftp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Raw-socket coverage for the authenticated loopback HTTP bridge. These tests exercise the
 * production byte-level parser without pulling in an HTTP client whose own normalization could
 * hide malformed input from the server.
 */
class SftpStreamServerTest {

    private lateinit var server: SftpStreamServer
    private val payload: ByteArray = "0123456789abcdef".toByteArray()

    @Before
    fun setUp() {
        server = SftpStreamServer()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun publishPayload(filename: String = "movie.mp4"): String =
        server.publish(
            path = "/$filename",
            size = payload.size.toLong(),
            contentType = "video/mp4",
            opener = { offset ->
                ByteArrayInputStream(payload, offset.toInt(), payload.size - offset.toInt()) as InputStream
            },
        )

    private data class HttpResponse(
        val statusLine: String,
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val rawBytes: ByteArray,
    )

    private fun request(
        method: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: String = "",
    ): HttpResponse {
        val raw = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
            append(body)
        }
        return rawRequest(raw)
    }

    private fun rawGet(path: String, range: String? = null): HttpResponse =
        request(
            method = "GET",
            path = path,
            headers = if (range == null) emptyList() else listOf("Range" to range),
        )

    private fun rawHead(path: String, range: String? = null): HttpResponse =
        request(
            method = "HEAD",
            path = path,
            headers = if (range == null) emptyList() else listOf("Range" to range),
        )

    private fun rawRequest(raw: String, socketReadTimeoutMillis: Int = 5_000): HttpResponse {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = socketReadTimeoutMillis
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.US_ASCII))
                flush()
            }
            return parseResponse(socket.getInputStream().readBytes())
        }
    }

    private fun parseResponse(all: ByteArray): HttpResponse {
        val separator = indexOf(all, "\r\n\r\n".toByteArray(Charsets.US_ASCII))
        require(separator >= 0) { "Malformed response: no header/body separator" }
        val headerText = String(all, 0, separator, Charsets.US_ASCII)
        val body = all.copyOfRange(separator + 4, all.size)
        val lines = headerText.split("\r\n")
        val statusLine = lines.first()
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        val headers = lines.drop(1)
            .mapNotNull {
                val colon = it.indexOf(':')
                if (colon <= 0) null else {
                    it.substring(0, colon).trim().lowercase() to it.substring(colon + 1).trim()
                }
            }
            .toMap()
        return HttpResponse(statusLine, statusCode, headers, body, all)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        if (haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun awaitActiveClients(expected: Int, timeoutMillis: Long = 2_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (server.activeClientCount != expected && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("active client count did not settle", expected, server.activeClientCount)
    }

    @Test
    fun `server binds the concrete IPv4 loopback address`() {
        server.start()
        val address = server.boundAddress
        assertNotNull(address)
        assertTrue(address!!.isLoopbackAddress)
        assertEquals("127.0.0.1", address.hostAddress)
    }

    @Test
    fun `published path begins with leading slash followed by token segment`() {
        server.start()
        val urlPath = publishPayload()
        assertTrue("expected leading slash, got '$urlPath'", urlPath.startsWith("/"))
        val parts = urlPath.removePrefix("/").split('/', limit = 2)
        assertEquals(2, parts.size)
        assertTrue("token must not be empty", parts[0].isNotEmpty())
        assertEquals("movie.mp4", parts[1])
    }

    @Test
    fun `request with correct token returns 200 and full payload`() {
        server.start()
        val response = rawGet(publishPayload())
        assertEquals(200, response.statusCode)
        assertEquals(payload.size.toString(), response.headers["content-length"])
        assertTrue(payload.contentEquals(response.body))
    }

    @Test
    fun `HEAD returns GET headers without opening or sending the body`() {
        server.start()
        val opens = AtomicInteger()
        val path = server.publish("/movie.mp4", payload.size.toLong(), "video/mp4") { offset ->
            opens.incrementAndGet()
            ByteArrayInputStream(payload, offset.toInt(), payload.size - offset.toInt())
        }
        val response = rawHead(path)
        assertEquals(200, response.statusCode)
        assertEquals(payload.size.toString(), response.headers["content-length"])
        assertEquals(0, response.body.size)
        assertEquals(0, opens.get())
    }

    @Test
    fun `HEAD with a legal range returns 206 headers and no body`() {
        server.start()
        val response = rawHead(publishPayload(), range = "bytes=4-9")
        assertEquals(206, response.statusCode)
        assertEquals("bytes 4-9/${payload.size}", response.headers["content-range"])
        assertEquals("6", response.headers["content-length"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun `request without any token segment returns 404`() {
        server.start()
        publishPayload()
        val response = rawGet("/movie.mp4")
        assertEquals(404, response.statusCode)
        assertEquals(0, response.body.size)
    }

    @Test
    fun `request with wrong token returns 404 even when the key exists`() {
        server.start()
        val realKey = publishPayload().substringAfterLast('/')
        assertEquals(404, rawGet("/this-is-not-the-real-token/$realKey").statusCode)
    }

    @Test
    fun `request with correct token but unknown key returns 404`() {
        server.start()
        val token = publishPayload().removePrefix("/").substringBefore('/')
        assertEquals(404, rawGet("/$token/missing-file.mp4").statusCode)
    }

    @Test
    fun `error response does not echo token or remote path`() {
        server.start()
        val published = publishPayload("private-secret-name.mp4")
        val token = published.removePrefix("/").substringBefore('/')
        val response = rawGet("/$token/private-secret-name-missing.mp4")
        val rawResponse = response.rawBytes.toString(Charsets.US_ASCII)
        assertEquals(404, response.statusCode)
        assertFalse(rawResponse.contains(token))
        assertFalse(rawResponse.contains("private-secret-name"))
    }

    @Test
    fun `token is regenerated on every restart`() {
        server.start()
        val firstToken = publishPayload().removePrefix("/").substringBefore('/')
        server.stop()
        server.start()
        val secondToken = publishPayload().removePrefix("/").substringBefore('/')
        assertNotEquals(firstToken, secondToken)
    }

    @Test
    fun `replaying a token from a previous server lifecycle returns 404`() {
        server.start()
        val firstUrl = publishPayload()
        server.stop()
        server.start()
        publishPayload()
        assertEquals(404, rawGet(firstUrl).statusCode)
    }

    @Test
    fun `successful response does NOT include Access-Control-Allow-Origin`() {
        server.start()
        val response = rawGet(publishPayload())
        assertEquals(200, response.statusCode)
        assertFalse(response.headers.containsKey("access-control-allow-origin"))
    }

    @Test
    fun `Range request with valid token returns 206 Partial Content`() {
        server.start()
        val response = rawGet(publishPayload(), range = "bytes=4-9")
        assertEquals(206, response.statusCode)
        assertTrue(payload.copyOfRange(4, 10).contentEquals(response.body))
        assertEquals("bytes 4-9/${payload.size}", response.headers["content-range"])
    }

    @Test
    fun `open-ended and suffix ranges retain legal byte semantics`() {
        server.start()
        val path = publishPayload()
        val openEnded = rawGet(path, range = "bytes=12-")
        assertEquals(206, openEnded.statusCode)
        assertTrue(payload.copyOfRange(12, payload.size).contentEquals(openEnded.body))

        val suffix = rawGet(path, range = "bytes=-4")
        assertEquals(206, suffix.statusCode)
        assertTrue(payload.copyOfRange(payload.size - 4, payload.size).contentEquals(suffix.body))
    }

    @Test
    fun `unsatisfiable range returns 416 without opening the stream`() {
        server.start()
        val opens = AtomicInteger()
        val path = server.publish("/movie.mp4", payload.size.toLong(), "video/mp4") {
            opens.incrementAndGet()
            ByteArrayInputStream(payload)
        }
        val response = rawGet(path, range = "bytes=99-100")
        assertEquals(416, response.statusCode)
        assertEquals("bytes */${payload.size}", response.headers["content-range"])
        assertEquals(0, opens.get())
    }

    @Test
    fun `Range request without token returns 404 not 206`() {
        server.start()
        publishPayload()
        assertEquals(404, rawGet("/movie.mp4", range = "bytes=0-3").statusCode)
    }

    @Test
    fun `token has at least 128 bits of entropy in printable form`() {
        server.start()
        val token = publishPayload().removePrefix("/").substringBefore('/')
        assertTrue("token too short: '$token'", token.length >= 40)
    }

    @Test
    fun `unsupported method is rejected with 405 and Allow`() {
        server.start()
        val response = request("POST", publishPayload())
        assertEquals(405, response.statusCode)
        assertEquals("GET, HEAD", response.headers["allow"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun `nonzero Content-Length and request body are rejected`() {
        server.start()
        val response = request(
            method = "GET",
            path = publishPayload(),
            headers = listOf("Content-Length" to "1"),
            body = "X",
        )
        assertEquals(400, response.statusCode)
        assertEquals(0, response.body.size)
    }

    @Test
    fun `malformed or conflicting Content-Length is rejected`() {
        server.start()
        val path = publishPayload()
        assertEquals(
            400,
            request("GET", path, headers = listOf("Content-Length" to "not-a-number")).statusCode,
        )
        assertEquals(
            400,
            request(
                "GET",
                path,
                headers = listOf("Content-Length" to "0", "Content-Length" to "1"),
            ).statusCode,
        )
    }

    @Test
    fun `Transfer-Encoding is rejected`() {
        server.start()
        val response = request(
            method = "GET",
            path = publishPayload(),
            headers = listOf("Transfer-Encoding" to "chunked"),
            body = "1\r\nX\r\n0\r\n\r\n",
        )
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `overlong request line returns 414 without echoing the URL`() {
        server.start()
        val path = publishPayload()
        val token = path.removePrefix("/").substringBefore('/')
        val response = rawRequest("GET $path${"a".repeat(9_000)} HTTP/1.1\r\n\r\n")
        val rawResponse = response.rawBytes.toString(Charsets.US_ASCII)
        assertEquals(414, response.statusCode)
        assertFalse(rawResponse.contains(token))
        assertFalse(rawResponse.contains("movie.mp4"))
    }

    @Test
    fun `overlong individual header line returns 431`() {
        server.start()
        val path = publishPayload()
        val response = rawRequest(
            "GET $path HTTP/1.1\r\nX-Long: ${"a".repeat(9_000)}\r\n\r\n",
        )
        assertEquals(431, response.statusCode)
    }

    @Test
    fun `total header bytes are bounded independently of line size`() {
        server.start()
        val path = publishPayload()
        val response = rawRequest(
            buildString {
                append("GET $path HTTP/1.1\r\n")
                repeat(5) { index ->
                    append("X-$index: ").append("a".repeat(7_000)).append("\r\n")
                }
                append("\r\n")
            },
        )
        assertEquals(431, response.statusCode)
    }

    @Test
    fun `more than 64 headers returns 431`() {
        server.start()
        val path = publishPayload()
        val response = rawRequest(
            buildString {
                append("GET $path HTTP/1.1\r\n")
                repeat(65) { index -> append("X-$index: value\r\n") }
                append("\r\n")
            },
        )
        assertEquals(431, response.statusCode)
    }

    @Test
    fun `partial request is closed with 408 after configured read timeout`() {
        server.stop()
        server = SftpStreamServer(clientReadTimeoutMillis = 150, maxConcurrentClients = 8)
        server.start()

        val started = System.nanoTime()
        val response = Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().apply {
                write("GET /unfinished".toByteArray(Charsets.US_ASCII))
                flush()
            }
            parseResponse(socket.getInputStream().readBytes())
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(408, response.statusCode)
        assertTrue("timeout response took too long: ${elapsedMillis}ms", elapsedMillis < 1_500)
        awaitActiveClients(0)
    }

    @Test
    fun `ninth concurrent client gets quick 503 and permits are released in finally`() {
        server.stop()
        server = SftpStreamServer(clientReadTimeoutMillis = 5_000, maxConcurrentClients = 8)
        server.start()
        val path = publishPayload()
        val heldSockets = mutableListOf<Socket>()
        try {
            repeat(8) {
                heldSockets += Socket("127.0.0.1", server.port).also { socket ->
                    socket.getOutputStream().apply {
                        write('G'.code)
                        flush()
                    }
                }
            }
            awaitActiveClients(8)

            val started = System.nanoTime()
            val overloaded = rawGet(path)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(503, overloaded.statusCode)
            assertTrue("overload response took too long: ${elapsedMillis}ms", elapsedMillis < 1_000)

            heldSockets.removeAt(heldSockets.lastIndex).close()
            awaitActiveClients(7)
            assertEquals(200, rawGet(path).statusCode)
        } finally {
            heldSockets.forEach { socket ->
                try {
                    socket.close()
                } catch (_: Exception) {
                    // Test cleanup only.
                }
            }
        }
    }

    @Test
    fun `content type cannot inject response headers`() {
        server.start()
        try {
            server.publish("/movie.mp4", payload.size.toLong(), "video/mp4\r\nX-Leak: yes") {
                ByteArrayInputStream(payload)
            }
            fail("header-injecting content type should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
