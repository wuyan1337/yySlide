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
import sh.haven.et.EtLogger
import sh.haven.et.transport.EtTransport
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.transport.MoshTransport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests that session manager commands (tmux/screen) work correctly
 * over Mosh and ET transports — verifying prompt detection logic.
 *
 * Requires tmux installed on the test server.
 */
class SessionManagerTest {

    private var sshSession: com.jcraft.jsch.Session? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receivedOutput = CopyOnWriteArrayList<String>()

    private val etLogger = object : EtLogger {
        override fun d(tag: String, msg: String) = println("[$tag] $msg")
        override fun e(tag: String, msg: String, throwable: Throwable?) {
            System.err.println("[$tag] ERROR: $msg")
            throwable?.printStackTrace(System.err)
        }
    }

    private val moshLogger = object : MoshLogger {
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
        sshSession?.disconnect()
    }

    /** Strip ANSI/OSC escape sequences. */
    private fun stripEscapes(text: String): String {
        return text.replace(Regex("\u001b(?:\\[[^a-zA-Z]*[a-zA-Z]|][^\u0007]*\u0007)"), "")
    }

    @Test
    fun `prompt detection finds shell prompt in mosh output`() = runBlocking {
        val session = TestServer.sshConnect()
        sshSession = session
        val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
        val output = result.stdout + "\n" + result.stderr
        val connectLine = output.lines().firstOrNull { it.startsWith("MOSH CONNECT") }
            ?: return@runBlocking // mosh-server not available, skip

        val parts = connectLine.split(" ")
        val moshPort = parts[2].toInt()
        val moshKey = parts[3]

        val promptDetected = AtomicBoolean(false)
        val promptLatch = CountDownLatch(1)

        val transport = MoshTransport(
            serverIp = TestServer.host,
            port = moshPort,
            key = moshKey,
            onOutput = { data, offset, len ->
                val raw = String(data, offset, len)
                receivedOutput.add(raw)
                if (!promptDetected.get()) {
                    val stripped = stripEscapes(raw).trimEnd()
                    if (stripped.isNotEmpty()) {
                        val last = stripped.last()
                        if (last == '$' || last == '#' || last == '%' || last == '>') {
                            promptDetected.set(true)
                            promptLatch.countDown()
                        }
                    }
                }
            },
            logger = moshLogger,
        )
        transport.start(scope)

        assertTrue(
            "Should detect shell prompt within 5s",
            promptLatch.await(5, TimeUnit.SECONDS),
        )

        transport.close()
    }

    @Test
    fun `tmux attach command executes after prompt over mosh`() = runBlocking {
        val session = TestServer.sshConnect()
        sshSession = session

        // Check tmux is available
        val tmuxCheck = TestServer.exec(session, "command -v tmux")
        if (tmuxCheck.exitStatus != 0) {
            println("tmux not installed, skipping")
            return@runBlocking
        }

        // Kill any existing test session
        TestServer.exec(session, "tmux kill-session -t haven-integ-test 2>/dev/null")

        val result = TestServer.exec(session, "mosh-server new -s -c 256 -l LANG=en_US.UTF-8")
        val output = result.stdout + "\n" + result.stderr
        val connectLine = output.lines().firstOrNull { it.startsWith("MOSH CONNECT") }
            ?: return@runBlocking

        val parts = connectLine.split(" ")
        val moshPort = parts[2].toInt()
        val moshKey = parts[3]

        val promptDetected = AtomicBoolean(false)
        val tmuxCommand = "tmux new-session -A -s haven-integ-test"
        val transportRef = arrayOfNulls<MoshTransport>(1)

        val transport = MoshTransport(
            serverIp = TestServer.host,
            port = moshPort,
            key = moshKey,
            onOutput = { data, offset, len ->
                val raw = String(data, offset, len)
                receivedOutput.add(raw)
                // Same prompt detection logic as TerminalViewModel
                if (!promptDetected.get()) {
                    val stripped = stripEscapes(raw).trimEnd()
                    if (stripped.isNotEmpty()) {
                        val last = stripped.last()
                        if (last == '$' || last == '#' || last == '%' || last == '>') {
                            if (promptDetected.compareAndSet(false, true)) {
                                println("Prompt detected ('$last'), sending tmux command")
                                transportRef[0]?.sendInput("$tmuxCommand\n".toByteArray())
                            }
                        }
                    }
                }
            },
            logger = moshLogger,
        )
        transportRef[0] = transport
        transport.start(scope)

        // Wait for tmux to start
        delay(3000)

        // Verify tmux session was created
        val tmuxList = TestServer.exec(session, "tmux list-sessions 2>/dev/null")
        assertTrue(
            "tmux session 'haven-integ-test' should exist. Output: ${tmuxList.stdout}",
            tmuxList.stdout.contains("haven-integ-test"),
        )

        transport.close()

        // Cleanup
        TestServer.exec(session, "tmux kill-session -t haven-integ-test 2>/dev/null")
    }
}
