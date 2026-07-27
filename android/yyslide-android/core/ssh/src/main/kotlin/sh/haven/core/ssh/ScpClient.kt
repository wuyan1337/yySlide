package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

private const val TAG = "ScpClient"

/**
 * Client-side implementation of the legacy SCP protocol (the one you get with
 * `scp -O` on modern OpenSSH). This protocol runs over a single `exec` channel
 * per transfer and is a simple stream of length-prefixed control messages
 * plus raw bytes, acknowledged by single-byte replies.
 *
 * Used when a server exposes `ssh` + an `scp` binary on PATH but does not
 * serve the `sftp-server` subsystem (and for users who just prefer SCP).
 *
 * This class is intentionally not a Singleton — it's a lightweight façade
 * around an already-connected JSch [Session]. Open a new exec channel per
 * operation; OpenSSH's remote `scp -t`/`-f` is single-shot.
 */
class ScpClient(private val session: Session) {

    class ScpException(message: String) : IOException(message)

    /**
     * Progress callback invoked as bytes are streamed. `transferred` is a
     * running total of bytes delivered for the current file, `total` is the
     * declared file size (from the control message for downloads, or from
     * the local `File.length()` for uploads). Return value is ignored —
     * cancel via coroutine cancellation instead.
     */
    fun interface ProgressSink {
        fun onBytes(transferred: Long, total: Long)
    }

    // ── Upload ───────────────────────────────────────────────────────────

    /**
     * Upload [localFile] to [remotePath] on the server. [remotePath] may be
     * either the destination file path or an existing directory into which
     * the file should be placed (SCP's server-side behaviour follows
     * whichever the server picks).
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        preserveTimes: Boolean = true,
        progress: ProgressSink? = null,
    ) = withContext(Dispatchers.IO) {
        require(localFile.isFile) { "Not a file: ${localFile.absolutePath}" }
        val command = buildString {
            append("scp ")
            if (preserveTimes) append("-p ")
            append("-t -- ")
            append(shellQuote(remotePath))
        }
        execScp(command) { out, inp ->
            checkAck(inp)
            sendFile(out, inp, localFile, preserveTimes, progress)
        }
    }

    /**
     * Recursively upload [localDir] to [remotePath].
     * The server-side sink receives `D <mode> 0 <name>\n` at directory entry
     * and `E\n` at exit, with files in between.
     */
    suspend fun uploadDirectory(
        localDir: File,
        remotePath: String,
        preserveTimes: Boolean = true,
        progress: ProgressSink? = null,
    ) = withContext(Dispatchers.IO) {
        require(localDir.isDirectory) { "Not a directory: ${localDir.absolutePath}" }
        val command = buildString {
            append("scp -r ")
            if (preserveTimes) append("-p ")
            append("-t -- ")
            append(shellQuote(remotePath))
        }
        execScp(command) { out, inp ->
            checkAck(inp)
            sendDir(out, inp, localDir, preserveTimes, progress)
        }
    }

    private suspend fun sendDir(
        out: OutputStream,
        inp: InputStream,
        dir: File,
        preserveTimes: Boolean,
        progress: ProgressSink?,
    ) {
        if (preserveTimes) {
            val t = "T${dir.lastModified() / 1000} 0 ${dir.lastModified() / 1000} 0\n"
            out.write(t.toByteArray()); out.flush()
            checkAck(inp)
        }
        // Mode: rely on 0755 — Android's File API doesn't expose Unix perms
        val header = "D0755 0 ${dir.name}\n"
        out.write(header.toByteArray()); out.flush()
        checkAck(inp)

        val children = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                sendDir(out, inp, child, preserveTimes, progress)
            } else if (child.isFile) {
                sendFile(out, inp, child, preserveTimes, progress)
            }
        }

        out.write("E\n".toByteArray()); out.flush()
        checkAck(inp)
    }

    private suspend fun sendFile(
        out: OutputStream,
        inp: InputStream,
        file: File,
        preserveTimes: Boolean,
        progress: ProgressSink?,
    ) {
        if (preserveTimes) {
            val t = "T${file.lastModified() / 1000} 0 ${file.lastModified() / 1000} 0\n"
            out.write(t.toByteArray()); out.flush()
            checkAck(inp)
        }
        val size = file.length()
        val header = "C0644 $size ${file.name}\n"
        out.write(header.toByteArray()); out.flush()
        checkAck(inp)

        val buf = ByteArray(32 * 1024)
        var transferred = 0L
        progress?.onBytes(0, size)
        file.inputStream().use { fis ->
            while (true) {
                coroutineContext.ensureActive()
                val n = fis.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                transferred += n
                progress?.onBytes(transferred, size)
            }
        }
        // Terminating ACK for the body
        out.write(byteArrayOf(0)); out.flush()
        checkAck(inp)
    }

    // ── Download ─────────────────────────────────────────────────────────

    /**
     * Download [remotePath] into [localFile]. If the remote is a directory
     * the call fails — use [downloadDirectory] for that.
     */
    suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        preserveTimes: Boolean = true,
        progress: ProgressSink? = null,
    ) = withContext(Dispatchers.IO) {
        val command = buildString {
            append("scp ")
            if (preserveTimes) append("-p ")
            append("-f -- ")
            append(shellQuote(remotePath))
        }
        execScp(command) { out, inp ->
            out.write(byteArrayOf(0)); out.flush()
            var lastMtime = 0L
            while (true) {
                coroutineContext.ensureActive()
                val control = readControl(inp) ?: break
                when (control[0]) {
                    'T' -> {
                        // T<mtime> 0 <atime> 0
                        lastMtime = parseTime(control)
                        out.write(byteArrayOf(0)); out.flush()
                    }
                    'C' -> {
                        val (_, size, _) = parseCopyHeader(control)
                        out.write(byteArrayOf(0)); out.flush()
                        receiveBody(inp, out, localFile, size, progress)
                        if (preserveTimes && lastMtime > 0) {
                            @Suppress("UNUSED_VARIABLE")
                            val ok = localFile.setLastModified(lastMtime * 1000)
                        }
                        return@execScp
                    }
                    'D' -> throw ScpException("Remote is a directory; use downloadDirectory()")
                    else -> throw ScpException("Unexpected control line: $control")
                }
            }
        }
    }

    /**
     * Recursively download [remotePath] into [localDir]. A new subdirectory
     * matching the remote basename is created inside [localDir] to hold the
     * contents, matching `scp -r` semantics.
     */
    suspend fun downloadDirectory(
        remotePath: String,
        localDir: File,
        preserveTimes: Boolean = true,
        progress: ProgressSink? = null,
    ) = withContext(Dispatchers.IO) {
        require(localDir.isDirectory || localDir.mkdirs()) {
            "Cannot create local dir: ${localDir.absolutePath}"
        }
        val command = buildString {
            append("scp -r ")
            if (preserveTimes) append("-p ")
            append("-f -- ")
            append(shellQuote(remotePath))
        }
        execScp(command) { out, inp ->
            out.write(byteArrayOf(0)); out.flush()
            receiveTree(inp, out, localDir, preserveTimes, progress)
        }
    }

    private suspend fun receiveTree(
        inp: InputStream,
        out: OutputStream,
        root: File,
        preserveTimes: Boolean,
        progress: ProgressSink?,
    ) {
        val stack = ArrayDeque<File>().apply { addLast(root) }
        var pendingMtime = 0L
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val control = readControl(inp) ?: return
            when (control[0]) {
                'T' -> {
                    pendingMtime = parseTime(control)
                    out.write(byteArrayOf(0)); out.flush()
                }
                'C' -> {
                    val (_, size, name) = parseCopyHeader(control)
                    val target = File(stack.last(), name)
                    out.write(byteArrayOf(0)); out.flush()
                    receiveBody(inp, out, target, size, progress)
                    if (preserveTimes && pendingMtime > 0) target.setLastModified(pendingMtime * 1000)
                    pendingMtime = 0
                }
                'D' -> {
                    val (_, _, name) = parseCopyHeader(control)
                    val sub = File(stack.last(), name)
                    sub.mkdirs()
                    stack.addLast(sub)
                    out.write(byteArrayOf(0)); out.flush()
                }
                'E' -> {
                    out.write(byteArrayOf(0)); out.flush()
                    stack.removeLast()
                    if (stack.isEmpty()) return
                }
                else -> throw ScpException("Unexpected control line: $control")
            }
        }
    }

    private suspend fun receiveBody(
        inp: InputStream,
        out: OutputStream,
        target: File,
        size: Long,
        progress: ProgressSink?,
    ) {
        target.parentFile?.mkdirs()
        val buf = ByteArray(32 * 1024)
        var transferred = 0L
        progress?.onBytes(0, size)
        target.outputStream().use { fos ->
            while (transferred < size) {
                coroutineContext.ensureActive()
                val toRead = minOf(buf.size.toLong(), size - transferred).toInt()
                val n = inp.read(buf, 0, toRead)
                if (n <= 0) throw ScpException("Premature EOF reading body (got $transferred of $size)")
                fos.write(buf, 0, n)
                transferred += n
                progress?.onBytes(transferred, size)
            }
        }
        checkAck(inp)                // trailing \0 from sender
        out.write(byteArrayOf(0)); out.flush()  // our ack
    }

    // ── Channel lifecycle ────────────────────────────────────────────────

    private inline fun execScp(command: String, block: (OutputStream, InputStream) -> Unit) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        Log.d(TAG, "scp command: $command")
        channel.setErrStream(System.err, true)
        val out = channel.outputStream
        val inp = channel.inputStream
        channel.connect()
        try {
            block(out, inp)
        } finally {
            try { out.close() } catch (_: Exception) {}
            // Drain and disconnect
            var spins = 0
            while (!channel.isClosed && spins++ < 40) {
                try { Thread.sleep(50) } catch (_: InterruptedException) {}
            }
            if (channel.exitStatus > 0) {
                Log.w(TAG, "scp exited with status=${channel.exitStatus}")
            }
            channel.disconnect()
        }
    }

    // ── Protocol helpers ────────────────────────────────────────────────

    /**
     * Read a single ack byte. 0 = ok; 1 = warning (read error message,
     * continue); 2 = fatal (throw). Only 0 and warnings return normally.
     */
    private fun checkAck(inp: InputStream) {
        val b = inp.read()
        when (b) {
            -1 -> throw ScpException("EOF on ack")
            0 -> return
            1, 2 -> {
                val msg = readLine(inp)
                if (b == 2 || msg.startsWith("scp:")) throw ScpException(msg)
                Log.w(TAG, "scp warning: $msg")
            }
            else -> throw ScpException("Unknown ack byte: $b")
        }
    }

    /** Read one control line (ends at '\n'), or return null on EOF. */
    private fun readControl(inp: InputStream): String? {
        val first = inp.read()
        if (first == -1) return null
        if (first == 1 || first == 2) {
            val msg = readLine(inp)
            if (first == 2) throw ScpException(msg)
            Log.w(TAG, "scp warning: $msg")
            return readControl(inp)
        }
        val sb = StringBuilder()
        sb.append(first.toChar())
        while (true) {
            val c = inp.read()
            if (c == -1 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c == -1 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Parse `C<mode> <size> <name>` or `D<mode> 0 <name>`. */
    private fun parseCopyHeader(line: String): Triple<String, Long, String> {
        val body = line.drop(1)                   // strip leading C/D
        val firstSpace = body.indexOf(' ')
        val secondSpace = body.indexOf(' ', firstSpace + 1)
        if (firstSpace < 0 || secondSpace < 0) throw ScpException("Bad header: $line")
        val mode = body.substring(0, firstSpace)
        val size = body.substring(firstSpace + 1, secondSpace).toLong()
        val name = body.substring(secondSpace + 1)
        return Triple(mode, size, name)
    }

    /** Parse `T<mtime> 0 <atime> 0` → returns mtime in seconds. */
    private fun parseTime(line: String): Long {
        val parts = line.drop(1).split(' ')
        return parts.getOrNull(0)?.toLongOrNull() ?: 0L
    }

    /** Shell-quote using single quotes with embedded-quote escape. */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
