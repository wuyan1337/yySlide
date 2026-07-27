package sh.haven.core.reticulum

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import java.io.Closeable

private const val TAG = "ReticulumSession"

/**
 * Bridges an rnsh shell session to a terminal emulator.
 *
 * Parallel to [sh.haven.core.ssh.TerminalSession]. Consumes the
 * [RnshShellSession] Flow-based output and delivers data via
 * [onDataReceived].
 */
class ReticulumSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val shellSession: RnshShellSession,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("rns-session-$sessionId")
    )

    /**
     * Start collecting output from the shell session.
     * Call after the session has been opened via [ReticulumTransport.openSession].
     */
    fun start() {
        // Collect output flow
        scope.launch {
            shellSession.output
                .catch { e ->
                    if (!closed) {
                        Log.e(TAG, "Output flow error for $sessionId", e)
                    }
                }
                .collect { data ->
                    if (!closed && data.isNotEmpty()) {
                        onDataReceived(data, 0, data.size)
                    }
                }

            // Flow completed — session disconnected
            if (!closed) {
                Log.d(TAG, "Output flow ended for $sessionId")
                onDisconnected?.invoke(true)
            }
        }

        // Watch for exit code
        scope.launch {
            try {
                val code = shellSession.exitCode.await()
                Log.d(TAG, "Session $sessionId exited with code $code")
                if (!closed) {
                    onDisconnected?.invoke(code == 0)
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.e(TAG, "Exit code error for $sessionId", e)
                    onDisconnected?.invoke(false)
                }
            }
        }
    }

    /**
     * Send keyboard input to the remote shell.
     * Safe to call from any thread.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        Log.d(TAG, "sendInput: ${data.size} bytes, session=$sessionId")
        scope.launch {
            try {
                shellSession.sendInput(data)
                Log.d(TAG, "sendInput: delivered ${data.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "sendInput failed", e)
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (closed) return
        scope.launch {
            try {
                shellSession.resize(rows, cols)
            } catch (e: Exception) {
                Log.e(TAG, "resize failed", e)
            }
        }
    }

    /**
     * Detach without closing the underlying rnsh session.
     * Stops collecting output but leaves the session alive.
     */
    fun detach() {
        if (closed) return
        closed = true
        scope.cancel()
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        try {
            shellSession.close()
        } catch (e: Exception) {
            Log.e(TAG, "close failed", e)
        }
    }
}
