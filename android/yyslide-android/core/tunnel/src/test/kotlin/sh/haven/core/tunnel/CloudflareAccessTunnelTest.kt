package sh.haven.core.tunnel

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CloudflareAccessTunnelTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val openServerSockets = mutableListOf<WebSocket>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After fun tearDown() {
        // MockWebServer's shutdown waits for in-flight WS dispatchers
        // to drain. Closing the server-side socket releases that thread
        // so shutdown can complete.
        openServerSockets.forEach { runCatching { it.close(1000, null) } }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        // shutdown() throws IOException if a WS dispatcher is still draining
        // when the test ends — a teardown-timing race that shows up on slow
        // CI runners, not a product fault (the body assertions have already
        // run). Swallow it so cleanup can't flake an otherwise-green test.
        runCatching { server.shutdown() }
    }

    /**
     * Helper: configure the next request to upgrade to a WebSocket and
     * echo binary frames straight back to the client. Returns a queue
     * the test can poll to inspect what bytes the server received.
     */
    private fun echoOnce(): LinkedBlockingQueue<ByteString> {
        val received = LinkedBlockingQueue<ByteString>()
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                synchronized(openServerSockets) { openServerSockets.add(ws) }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                received.add(bytes)
                ws.send(bytes)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        return received
    }

    @Test
    fun `unprotected route dial sends no auth headers and uses root path`() {
        // Reproduces rkxspace's setup (issue #154): a Cloudflare Tunnel
        // published-hostname route with no Access in front. The client
        // dials `wss://<hostname>/` with no JWT, no subprotocol, and
        // standard gorilla WS headers — same as `cloudflared access ssh`.
        echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)
        conn.close()

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/", req.path)
        assertNull("unauth path must not send Cf-Access-Token", req.getHeader("Cf-Access-Token"))
        assertNull(
            "subprotocol header must not be set — cloudflared uses gorilla default",
            req.getHeader("Sec-WebSocket-Protocol"),
        )
        // Standard WS handshake headers should be present (set by OkHttp).
        assertEquals("websocket", req.getHeader("Upgrade")?.lowercase())
        tunnel.close()
    }

    @Test
    fun `Access-protected route adds Cf-Access-Token header, not a cookie`() {
        // cloudflared sets Cf-Access-Token: <jwt> as an HTTP header on
        // the WS upgrade (carrier/carrier.go:154). The CF_Authorization
        // cookie shape is the browser path — only `Cf-Access-Token` is
        // what the gateway expects from a programmatic client.
        echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "test-jwt-token",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)
        conn.close()

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("test-jwt-token", req.getHeader("Cf-Access-Token"))
        val cookieHeader = req.getHeader("Cookie") ?: ""
        assertFalse(
            "Must not send CF_Authorization cookie — gateway uses Cf-Access-Token header",
            cookieHeader.contains("CF_Authorization"),
        )
        tunnel.close()
    }

    @Test
    fun `bastion-mode tunnel adds Cf-Access-Jump-Destination header`() {
        echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "bastion.example.com",
            jwt = "",
            jumpDestination = "internal-host:22",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val conn = tunnel.dial("bastion.example.com", 22, 3_000)
        conn.close()

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("internal-host:22", req.getHeader("Cf-Access-Jump-Destination"))
        tunnel.close()
    }

    @Test
    fun `bytes round-trip through binary frames`() {
        val serverInbox = echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)

        val payload = "SSH-2.0-OpenSSH_9.0\r\n".toByteArray()
        conn.outputStream.write(payload)
        conn.outputStream.flush()

        val seenByServer = serverInbox.poll(2, TimeUnit.SECONDS)!!
        assertArrayEquals(payload, seenByServer.toByteArray())

        // Echoed back into inputStream
        val readBack = ByteArray(payload.size)
        var off = 0
        while (off < payload.size) {
            val n = conn.inputStream.read(readBack, off, payload.size - off)
            check(n > 0) { "EOF before all bytes read" }
            off += n
        }
        assertArrayEquals(payload, readBack)
        conn.close()
        tunnel.close()
    }

    @Test
    fun `dial rejects hostname mismatch with a clear error`() {
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            tunnel.dial("other.example.com", 22, 3_000)
        }
        assertTrue(ex.message!!.contains("ssh.example.com"))
        assertTrue(ex.message!!.contains("other.example.com"))
        tunnel.close()
    }

    @Test
    fun `302 to Access login surfaces a sign-in hint`() {
        // The signal that a Cloudflare Tunnel route is Access-protected
        // is the edge replying with a 302 whose Location starts with
        // /cdn-cgi/access/login (cloudflared's IsAccessResponse check).
        // Surface that as actionable text rather than the generic
        // "Expected HTTP 101".
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://myteam.cloudflareaccess.com/cdn-cgi/access/login/ssh.example.com?kid=abcd")
                .setHeader("cf-ray", "8c34abcde1234567-LHR"),
        )
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val ex = assertThrows(java.io.IOException::class.java) {
            tunnel.dial("ssh.example.com", 22, 3_000)
        }
        val msg = ex.message ?: ""
        assertTrue("expected sign-in hint, got: $msg", msg.contains("requires Access auth"))
        assertTrue("expected hostname in message, got: $msg", msg.contains("ssh.example.com"))
        assertTrue("expected cf-ray in message, got: $msg", msg.contains("8c34abcde1234567-LHR"))
        tunnel.close()
    }

    @Test
    fun `403 on upgrade surfaces JWT-rejected hint with cf-ray and body excerpt`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("cf-ray", "8c34abcde1234567-LHR")
                .setHeader("Server", "cloudflare")
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><body>Access denied — invalid JWT</body></html>"),
        )
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "stale",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val ex = assertThrows(java.io.IOException::class.java) {
            tunnel.dial("ssh.example.com", 22, 3_000)
        }
        val msg = ex.message ?: ""
        assertTrue("expected JWT-invalid hint, got: $msg", msg.contains("JWT may be invalid"))
        assertTrue("expected HTTP 403 in message, got: $msg", msg.contains("403"))
        assertTrue("expected cf-ray in message, got: $msg", msg.contains("8c34abcde1234567-LHR"))
        assertTrue(
            "expected body excerpt in message, got: $msg",
            msg.contains("Access denied"),
        )
        tunnel.close()
    }

    @Test
    fun `5xx on upgrade surfaces gateway error message`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("cf-ray", "abc-IAD")
                .setBody("Bad Gateway"),
        )
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val ex = assertThrows(java.io.IOException::class.java) {
            tunnel.dial("ssh.example.com", 22, 3_000)
        }
        val msg = ex.message ?: ""
        assertTrue("expected HTTP 502 in message, got: $msg", msg.contains("502"))
        assertTrue("expected gateway-error phrasing, got: $msg", msg.contains("gateway error"))
        assertTrue("expected cf-ray in message, got: $msg", msg.contains("abc-IAD"))
        tunnel.close()
    }

    @Test
    fun `tunnel close tears down active connections`() {
        echoOnce()
        val tunnel = CloudflareAccessTunnel(
            hostname = "ssh.example.com",
            jwt = "",
            jumpDestination = "",
            httpClient = client,
            gatewayUrlOverride = server.url("/").toString(),
        )
        val conn = tunnel.dial("ssh.example.com", 22, 3_000)
        tunnel.close()
        // After tunnel close, writes should fail rather than silently succeed.
        val writeFailed = runCatching {
            conn.outputStream.write(byteArrayOf(1, 2, 3))
            conn.outputStream.flush()
        }.isFailure
        assertTrue("write after close should fail", writeFailed)
    }
}
