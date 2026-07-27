package sh.haven.core.ssh

import com.jcraft.jsch.Logger
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Captures JSch debug output into an in-memory buffer (ssh -v equivalent).
 * Thread-safe: JSch calls log() from the connection thread.
 * Call [drain] after connect/fail to get the captured text.
 */
class SshVerboseLogger : Logger {
    private val lines = ConcurrentLinkedQueue<String>()
    private val startTime = System.currentTimeMillis()

    override fun isEnabled(level: Int): Boolean = level >= Logger.DEBUG

    override fun log(level: Int, message: String?) {
        if (message == null) return
        val elapsed = System.currentTimeMillis() - startTime
        val prefix = when (level) {
            Logger.DEBUG -> "debug"
            Logger.INFO -> "info"
            Logger.WARN -> "WARN"
            Logger.ERROR -> "ERROR"
            Logger.FATAL -> "FATAL"
            else -> "???"
        }
        lines.add("+${elapsed}ms [$prefix] $message")
    }

    fun drain(): String? {
        if (lines.isEmpty()) return null
        return lines.joinToString("\n")
    }

    /** Haven-level entry point so callers don't import `com.jcraft.jsch.Logger.INFO`. */
    fun logInfo(message: String) { log(Logger.INFO, message) }

    /** Haven-level entry point so callers don't import `com.jcraft.jsch.Logger.WARN`. */
    fun logWarn(message: String) { log(Logger.WARN, message) }
}
