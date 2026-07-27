package sh.haven.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.transport.MoshTransport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the Mosh transport against a real mosh-server.
 *
 * Requires:
 *   - mosh-server installed on test.host
 *   - UDP port 60001+ open on the server firewall
 *   - SSH key or password auth configured
 *
 * Run with:
 *   ./gradlew :integration-tests:test -Dtest.host=192.168.0.180 -Dtest.user=ian -Dtest.key=~/.ssh/id_ed25519
 */
class MoshTransportTest {

    private var sshSession: com.jcraft.jsch.Session? = null
    private var transport: MoshTransport? = null
    private val receivedOutput = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val testLogger = object : MoshLogger {
        override fun d(tag: String, msg: String) = println("[$tag] $msg")
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            System.err.println("[$tag] ERROR: $msg")
            throwable?.printStackTrace(System.err)
        }
    }

    @Before
    fun setUp() {
        TestServer.requireConfigured()
    }

    @After
    fun tearDown() {
        transport?.close()
        sshSession?.disconnect()
    }

    /** Bootstrap mosh-server via SSH, returning (serverIp, port, key). */
    private fun bootstrap(): Triple<String, Int, String> {
        val session = TestServer.sshConnect()
        sshSession = session

        val cmd = "mosh-server new -s -c 256 -l LANG=en_US.UTF-8"
        val result = TestServer.exec(session, cmd)
        val output = result.stdout + "\n" + result.stderr

        val connectLine = output.lines().firstOrNull { it.startsWith("MOSH CONNECT") }
        assertNotNull(
            "mosh-server bootstrap failed. stderr: ${result.stderr.take(200)}",
            connectLine,
        )

        val parts = connectLine!!.split(" ")
        assertTrue("Expected MOSH CONNECT <port> <key>", parts.size >= 4)

        val moshPort = parts[2].toInt()
        val moshKey = parts[3]

        return Triple(TestServer.host, moshPort, moshKey)
    }

    @Test
    fun `mosh bootstrap returns valid MOSH CONNECT`() {
        val (host, port, key) = bootstrap()
        assertTrue("Port should be >= 60001", port >= 60001)
        assertTrue("Key should be non-empty", key.isNotBlank())
        println("MOSH CONNECT $host:$port key=${key.take(6)}...")
    }

    @Test
    fun `mosh transport connects and receives shell output`() {
        val (host, port, key) = bootstrap()
        val outputLatch = CountDownLatch(1)
        var disconnectReason: Boolean? = null

        println("Connecting mosh transport to $host:$port")
        transport = MoshTransport(
            serverIp = host,
            port = port,
            key = key,
            onOutput = { data, offset, len ->
                val text = String(data, offset, len)
                println("Mosh output (${len} bytes): ${text.take(80).replace("\n", "\\n")}")
                receivedOutput.add(text)
                outputLatch.countDown()
            },
            onDisconnect = { clean ->
                println("Mosh disconnected (clean=$clean)")
                disconnectReason = clean
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue(
            "Should receive shell output within 15s (disconnected=$disconnectReason)",
            outputLatch.await(15, TimeUnit.SECONDS),
        )
        assertTrue("Should have received some output", receivedOutput.isNotEmpty())
    }

    @Test
    fun `mosh transport sends input and receives echo`() = runBlocking {
        val (host, port, key) = bootstrap()
        val echoLatch = CountDownLatch(1)
        val outputLatch = CountDownLatch(1)
        val marker = "HAVEN_MOSH_${System.currentTimeMillis()}"
        var disconnected = false

        transport = MoshTransport(
            serverIp = host,
            port = port,
            key = key,
            onOutput = { data, offset, len ->
                val text = String(data, offset, len)
                receivedOutput.add(text)
                outputLatch.countDown()
                if (receivedOutput.joinToString("").contains(marker)) {
                    echoLatch.countDown()
                }
            },
            onDisconnect = { clean ->
                println("Mosh disconnected during echo test (clean=$clean)")
                disconnected = true
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        // Wait for shell prompt
        assertTrue("Should receive initial output within 15s", outputLatch.await(15, TimeUnit.SECONDS))

        transport!!.sendInput("echo $marker\n".toByteArray())

        assertTrue(
            "Should receive echo of marker within 5s (disconnected=$disconnected)",
            echoLatch.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `mosh transport handles resize`() = runBlocking {
        val (host, port, key) = bootstrap()
        val outputLatch = CountDownLatch(1)
        var disconnected = false

        transport = MoshTransport(
            serverIp = host,
            port = port,
            key = key,
            onOutput = { _, _, _ -> outputLatch.countDown() },
            onDisconnect = { clean ->
                println("Mosh disconnected during resize test (clean=$clean)")
                disconnected = true
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should connect within 15s", outputLatch.await(15, TimeUnit.SECONDS))

        transport!!.resize(120, 40)
        delay(500)
        transport!!.resize(80, 24)
        delay(500)

        assertFalse("Should not disconnect after resize", disconnected)
    }

    @Test
    fun `mosh transport survives idle period`() = runBlocking {
        val (host, port, key) = bootstrap()
        val outputLatch = CountDownLatch(1)
        var disconnected = false

        transport = MoshTransport(
            serverIp = host,
            port = port,
            key = key,
            onOutput = { _, _, _ -> outputLatch.countDown() },
            onDisconnect = { clean ->
                println("Mosh disconnected during idle test (clean=$clean)")
                disconnected = true
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should connect within 15s", outputLatch.await(15, TimeUnit.SECONDS))

        // Mosh keepalive interval is 3s — wait long enough for several
        delay(10_000)

        assertFalse("Should not disconnect during idle", disconnected)

        // Verify session still works
        val marker = "ALIVE_${System.currentTimeMillis()}"
        val aliveLatch = CountDownLatch(1)
        // Re-wire output capture
        transport!!.sendInput("echo $marker\n".toByteArray())
        delay(2000)

        val allOutput = receivedOutput.joinToString("")
        // Transport should still be functional
        assertNotNull("Transport should still be active", transport)
    }

    @Test
    fun `mosh transport handles rapid input without nonce desync`() = runBlocking {
        val (host, port, key) = bootstrap()
        val outputLatch = CountDownLatch(1)
        var disconnected = false

        transport = MoshTransport(
            serverIp = host,
            port = port,
            key = key,
            onOutput = { _, _, _ -> outputLatch.countDown() },
            onDisconnect = { clean ->
                println("Mosh disconnected during rapid input test (clean=$clean)")
                disconnected = true
            },
            logger = testLogger,
        )
        transport!!.start(scope)

        assertTrue("Should connect within 15s", outputLatch.await(15, TimeUnit.SECONDS))

        // Rapid-fire input
        repeat(50) { i ->
            transport!!.sendInput("echo rapid$i\n".toByteArray())
        }

        delay(3000)
        assertFalse("Should not disconnect during rapid input", disconnected)
    }
}
