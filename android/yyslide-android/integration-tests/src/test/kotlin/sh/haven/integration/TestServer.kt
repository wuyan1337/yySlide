package sh.haven.integration

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * SSH connection to the test server for bootstrapping mosh/ET sessions.
 *
 * Configure via system properties:
 *   -Dtest.host=192.168.0.180
 *   -Dtest.port=22
 *   -Dtest.user=ian
 *   -Dtest.key=~/.ssh/id_ed25519
 *   -Dtest.password=
 *   -Dtest.et.port=2022
 *
 * Or environment variables: TEST_HOST, TEST_PORT, TEST_USER, TEST_KEY, TEST_PASSWORD, TEST_ET_PORT
 */
object TestServer {

    val host: String = prop("test.host", "TEST_HOST") ?: ""
    val port: Int = prop("test.port", "TEST_PORT")?.toIntOrNull() ?: 22
    val user: String = prop("test.user", "TEST_USER") ?: ""
    val keyPath: String = prop("test.key", "TEST_KEY") ?: ""
    val password: String = prop("test.password", "TEST_PASSWORD") ?: ""
    val etPort: Int = prop("test.et.port", "TEST_ET_PORT")?.toIntOrNull() ?: 2022

    val isConfigured: Boolean
        get() = host.isNotBlank() && user.isNotBlank()

    fun requireConfigured() {
        assumeTrue(
            "Test server not configured. Set -Dtest.host=... -Dtest.user=...",
            isConfigured,
        )
    }

    /** Open an SSH session to the test server. Caller must disconnect. */
    fun sshConnect(): Session {
        val jsch = JSch()
        val expandedKey = keyPath.replaceFirst("~", System.getProperty("user.home"))
        if (expandedKey.isNotBlank() && File(expandedKey).exists()) {
            jsch.addIdentity(expandedKey)
        }

        val session = jsch.getSession(user, host, port)
        if (password.isNotBlank()) {
            session.setPassword(password)
        }
        session.setConfig("StrictHostKeyChecking", "no")
        session.connect(10_000)
        return session
    }

    /** Execute a command over SSH and return stdout + stderr + exit status. */
    fun exec(session: Session, command: String): ExecResult {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null

        val stdout = channel.inputStream
        val stderr = channel.errStream
        channel.connect(10_000)

        val outText = stdout.bufferedReader().readText()
        val errText = stderr.bufferedReader().readText()
        channel.disconnect()

        return ExecResult(
            exitStatus = channel.exitStatus,
            stdout = outText,
            stderr = errText,
        )
    }

    data class ExecResult(
        val exitStatus: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun prop(sysProp: String, envVar: String): String? {
        return System.getProperty(sysProp)?.takeIf { it.isNotBlank() }
            ?: System.getenv(envVar)?.takeIf { it.isNotBlank() }
    }
}
