package sh.haven.core.local

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "LocalSession"
private const val READ_BUFFER_SIZE = 8192

/**
 * Bridges a local PTY process to the terminal emulator.
 *
 * Manages a child process (shell or proot) with a pseudoterminal,
 * reading output on a background thread and writing input from any thread.
 */
class LocalSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val command: String,
    private val args: Array<String>,
    private val env: Array<String>,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onExited: ((exitCode: Int) -> Unit)? = null,
) : Closeable {

    @Volatile
    private var closed = false

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var masterPfd: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var readerThread: Thread? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-pty-write-$sessionId").apply { isDaemon = true }
    }

    /**
     * Fork the child process and start reading PTY output.
     * @throws IllegalStateException if forkpty fails
     */
    fun start(rows: Int = 24, cols: Int = 80) {
        if (closed) return

        val result = PtyBridge.nativeForkPty(command, args, env, rows, cols)
        masterFd = result[0]
        childPid = result[1]

        if (masterFd < 0) {
            throw IllegalStateException("forkpty failed: errno=${result[1]}")
        }

        Log.d(TAG, "Started local process: pid=$childPid fd=$masterFd cmd=$command")

        val pfd = ParcelFileDescriptor.adoptFd(masterFd)
        masterPfd = pfd  // prevent GC from closing the fd
        inputStream = FileInputStream(pfd.fileDescriptor)
        outputStream = FileOutputStream(pfd.fileDescriptor)

        // Start reader thread
        readerThread = Thread({
            val buf = ByteArray(READ_BUFFER_SIZE)
            var readCount = 0
            try {
                while (!closed) {
                    val n = inputStream!!.read(buf)
                    if (n <= 0) break
                    readCount++
                    onDataReceived(buf, 0, n)
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.d(TAG, "Read loop ended (after $readCount reads): ${e.message}")
                }
            }
            // Process exited — wait for exit code
            if (!closed) {
                val exitCode = try {
                    PtyBridge.nativeWaitPid(childPid)
                } catch (_: Exception) {
                    -1
                }
                Log.d(TAG, "Process $childPid exited: $exitCode")
                onExited?.invoke(exitCode)
            }
        }, "local-pty-read-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * True while the child process and its reader thread are live. Goes false
     * once the PTY hits EOF (process exited) or [close] runs — used so callers
     * can fail a write loudly instead of silently dropping it into a dead fd.
     */
    fun isAlive(): Boolean = !closed && childPid > 0 && readerThread?.isAlive == true

    /**
     * Send keyboard input to the PTY.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        writeExecutor.execute {
            try {
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: Exception) {
                if (!closed) {
                    Log.e(TAG, "Write failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Resize the PTY terminal.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed || masterFd < 0) return
        PtyBridge.nativeSetSize(masterFd, rows, cols)
    }

    override fun close() {
        if (closed) return
        closed = true

        // Close streams — this will cause the read loop to exit
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { masterPfd?.close() } catch (_: Exception) {}

        // Kill the child process if still running
        if (childPid > 0) {
            try {
                android.os.Process.killProcess(childPid)
            } catch (_: Exception) {}
        }

        writeExecutor.shutdown()
        readerThread = null
        inputStream = null
        outputStream = null
        masterPfd = null
        masterFd = -1
        childPid = -1
    }
}
