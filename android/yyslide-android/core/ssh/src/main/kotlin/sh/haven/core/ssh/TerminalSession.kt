package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelShell
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "TerminalSession"

/**
 * Bridges a JSch [ChannelShell] to a terminal emulator.
 *
 * Reads SSH output on a background thread and delivers it via [onDataReceived].
 * Call [sendToSsh] to forward keyboard input to the remote shell.
 */
class TerminalSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    @Volatile private var channel: ChannelShell,
    @Volatile private var client: SshClient,
    @Volatile private var onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: ((cleanExit: Boolean) -> Unit)? = null,
    pendingCommands: List<String> = emptyList(),
) : Closeable {

    /** Queue of commands to send one-by-one as shell prompts are detected. */
    private val _pendingCommands: MutableList<String> = pendingCommands.toMutableList()

    /** Replace the pending command queue (used on reconnect). */
    fun setPendingCommands(commands: List<String>) {
        synchronized(_pendingCommands) {
            _pendingCommands.clear()
            _pendingCommands.addAll(commands)
        }
    }

    @Volatile private var sshInput: InputStream = channel.inputStream
    @Volatile private var sshOutput: OutputStream = channel.outputStream

    /** Single-thread executor for serialising writes and scheduling debounced resizes. */
    private val writeExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ssh-writer-$sessionId").apply { isDaemon = true }
    }

    @Volatile
    private var closed = false

    /** Pending debounced resize — cancelled on each new resize call. */
    @Volatile
    private var pendingResize: ScheduledFuture<*>? = null

    /**
     * Last PTY size we asked the remote for. Tracked so [reconnect] can
     * replay it on the freshly-opened channel — without this, the reader
     * comes up with an 80×24 default while the local canvas is at its
     * actual size, and the divergence shows up as wrap-mismatch garbage
     * in scrollback after a WiFi jump (#120).
     * 0 means "no resize seen yet"; replay only when we have a real size.
     */
    @Volatile private var lastCols: Int = 0
    @Volatile private var lastRows: Int = 0

    private var readerThread: Thread? = null

    /**
     * Start the reader thread that delivers SSH output to [onDataReceived].
     * Call this after all wiring (e.g., emulator setup) is complete.
     */
    /**
     * Replace the data callback (used when reattaching after Activity recreation).
     * The reader thread continues running and will use the new callback immediately.
     */
    fun replaceDataCallback(callback: (ByteArray, Int, Int) -> Unit) {
        onDataReceived = callback
    }

    fun start() {
        readerThread = thread(
            name = "ssh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(8192)
        var pendingSent = false
        var gotEof = false
        var gotException = false
        try {
            while (!closed && channel.isConnected) {
                val bytesRead = sshInput.read(buffer)
                if (bytesRead == -1) {
                    gotEof = true
                    break
                }
                if (bytesRead > 0) {
                    onDataReceived(buffer, 0, bytesRead)

                    // After delivering output, check if we have pending commands
                    // to send once a shell prompt appears.
                    // Strip ANSI/OSC escape sequences before checking for prompt chars,
                    // since shell integration (OSC 133) wraps the prompt in escape codes
                    // that would mask the trailing $ / # / % / > character.
                    // Check each line individually: tmux status bars or other
                    // output after the prompt would mask the prompt char if we
                    // only checked the last character of the entire chunk.
                    val hasCmd = synchronized(_pendingCommands) { _pendingCommands.isNotEmpty() }
                    if (hasCmd) {
                        val raw = String(buffer, 0, bytesRead)
                        val stripped = raw.replace(Regex("\u001b(?:\\[[^a-zA-Z]*[a-zA-Z]|][^\u0007]*\u0007|[()][A-Za-z])"), "")
                        val promptChar = stripped.split('\n')
                            .map { it.trimEnd() }
                            .filter { it.isNotEmpty() }
                            .mapNotNull { line -> line.last().takeIf { it == '$' || it == '#' || it == '%' || it == '>' || it == '\u276F' /* ❯ */ } }
                            .firstOrNull()
                        if (promptChar != null) {
                            val cmd = synchronized(_pendingCommands) { _pendingCommands.removeFirstOrNull() }
                            if (cmd != null) {
                                Log.d(TAG, "Shell prompt detected ('$promptChar'), sending pending command")
                                sendToSsh((cmd + "\n").toByteArray())
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            gotException = true
        }
        if (!closed) {
            // Wait briefly for channel to fully close and exit status to propagate
            for (i in 1..10) {
                if (channel.isClosed) break
                try { Thread.sleep(50) } catch (_: InterruptedException) { break }
            }
            val exitStatus = channel.exitStatus
            // Clean exit: remote sent a real exit status (>= 0), e.g. shell
            // exited normally.  A network drop produces EOF with exitStatus -1
            // (no status received) — that must trigger reconnection.
            val cleanExit = exitStatus >= 0 && !gotException
            Log.d(TAG, "readLoop ended for $sessionId — eof=$gotEof exception=$gotException exitStatus=$exitStatus cleanExit=$cleanExit")
            onDisconnected?.invoke(cleanExit)
        }
    }

    /**
     * Forward keyboard input to the remote shell.
     * Safe to call from any thread — writes are dispatched to a background thread
     * to avoid NetworkOnMainThreadException.
     */
    fun sendToSsh(data: ByteArray) {
        if (closed || !channel.isConnected) {
            Log.d(TAG, "sendToSsh: dropping ${data.size} bytes (closed=$closed connected=${channel.isConnected})")
            return
        }

        val copy = data.copyOf()
        try {
            writeExecutor.execute {
                if (closed || !channel.isConnected) return@execute
                try {
                    sshOutput.write(copy)
                    sshOutput.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "sendToSsh: write failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down — drop the write
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (closed || writeExecutor.isShutdown) return
        // Remember the latest size so reconnect() can replay it (#120).
        lastCols = cols
        lastRows = rows
        // Debounce: cancel any pending resize and schedule a new one.
        // During keyboard/tab animations the terminal view resizes every frame;
        // only the final size matters for the remote PTY.
        pendingResize?.cancel(false)
        try {
            pendingResize = writeExecutor.schedule({
                try {
                    Log.d(TAG, "setPtySize: ${cols}x${rows}")
                    client.resizeShell(channel, cols, rows)
                } catch (e: Exception) {
                    Log.e(TAG, "resize failed", e)
                }
            }, 150, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between check and execute — ignore
        }
    }

    /**
     * Swap the underlying SSH channel after a reconnect.
     * The old reader thread has already exited (which triggered the reconnect).
     * Starts a new reader on the new channel.
     */
    fun reconnect(newChannel: ChannelShell, newClient: SshClient) {
        channel = newChannel
        client = newClient
        sshInput = newChannel.inputStream
        sshOutput = newChannel.outputStream
        Log.d(TAG, "reconnect: swapped channel for $sessionId, starting new reader")
        // The new channel was opened with the default 80×24 PTY size
        // (SshClient.openShellChannel defaults). If the user had resized
        // since session start, replay the last known size on the new
        // channel so the server's terminal matches the local canvas.
        // Without this, post-WiFi-jump output wraps at 80 columns while
        // the client renders at the real width — visible as garbage in
        // scrollback (#120).
        val replayCols = lastCols
        val replayRows = lastRows
        if (replayCols > 0 && replayRows > 0 && !writeExecutor.isShutdown) {
            try {
                writeExecutor.submit {
                    try {
                        Log.d(TAG, "reconnect: replaying setPtySize ${replayCols}x${replayRows}")
                        newClient.resizeShell(newChannel, replayCols, replayRows)
                    } catch (e: Exception) {
                        Log.w(TAG, "reconnect: setPtySize replay failed", e)
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Executor shutdown raced — ignore
            }
        }
        readerThread = thread(
            name = "ssh-reader-$sessionId",
            isDaemon = true,
        ) {
            readLoop()
        }
    }

    /**
     * Detach from the channel without disconnecting it.
     * Stops the reader thread and write executor but leaves the shell channel
     * alive so a new TerminalSession can be attached to it.
     * Used when TerminalViewModel is cleared but the SSH connection persists
     * (e.g., Activity destroyed while foreground service keeps the process alive).
     */
    fun detach() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        readerThread?.interrupt()
    }

    override fun close() {
        if (closed) return
        closed = true
        writeExecutor.shutdown()
        try { channel.disconnect() } catch (_: Exception) {}
        readerThread?.interrupt()
    }
}
