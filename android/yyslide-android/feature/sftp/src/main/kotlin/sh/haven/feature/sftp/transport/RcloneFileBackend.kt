package sh.haven.feature.sftp.transport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.rclone.SyncConfig
import sh.haven.core.rclone.SyncMode
import sh.haven.feature.sftp.SftpEntry
import java.io.File
import java.time.Instant

/**
 * [FileBackend] over a connected rclone remote. The remote name (e.g.
 * `gdrive:`) is captured at resolution time; rclone treats the empty
 * string as remote root, so the synthetic `"/"` from `currentPath` is
 * normalised here rather than at the call site.
 *
 * [appContext] is needed for the temp-file dance behind [readBytes] and
 * [writeBytes]: rclone's RPC takes file paths, not streams, so small-file
 * round trips through the app cache. The cache directory is private to
 * Haven and cleared by Android under memory pressure, which is the
 * correct lifetime for these throwaway buffers.
 */
class RcloneFileBackend(
    private val client: RcloneClient,
    private val remoteName: String,
    private val appContext: Context,
) : FileBackend {

    override val label: String = "Rclone"

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        val rclonePath = if (path == "/" || path.isEmpty()) "" else path
        client.listDirectory(remoteName, rclonePath).map { entry ->
            val modTime = try {
                Instant.parse(entry.modTime).epochSecond
            } catch (_: Exception) {
                0L
            }
            SftpEntry(
                name = entry.name,
                path = if (rclonePath.isEmpty()) entry.name else "${rclonePath.trimEnd('/')}/${entry.name}",
                isDirectory = entry.isDir,
                size = entry.size,
                modifiedTime = modTime,
                permissions = if (entry.isDir) "drwxr-xr-x" else "-rw-r--r--",
                mimeType = entry.mimeType,
            )
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        if (isDirectory) {
            client.deleteDir(remoteName, path)
        } else {
            client.deleteFile(remoteName, path)
        }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        client.mkdir(remoteName, path)
    }

    /**
     * Rename a file or directory. rclone splits these at the protocol
     * level — `operations/movefile` for files and a `sync MOVE` job for
     * directories — so we probe the parent listing once to dispatch
     * correctly. The probe is cheap relative to the move itself for any
     * non-trivial backend (Drive, S3, etc.) and matches the legacy
     * behaviour the [SftpViewModel] used to keep inline.
     */
    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val isDir = isDirectory(from)
        if (isDir) {
            val config = SyncConfig(
                srcFs = "$remoteName:$from",
                dstFs = "$remoteName:$to",
                mode = SyncMode.MOVE,
            )
            val jobId = client.startSync(config)
            while (true) {
                delay(200)
                val status = client.getJobStatus(jobId)
                if (status.finished) {
                    if (!status.success) throw Exception(status.error ?: "Rename failed")
                    break
                }
            }
        } else {
            client.moveFile(remoteName, from, remoteName, to)
        }
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val tempFile = File(appContext.cacheDir, "rclone-read-${System.nanoTime()}.bin")
        try {
            client.copyFile(remoteName, path, tempFile.parent!!, tempFile.name)
            tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val tempFile = File(appContext.cacheDir, "rclone-write-${System.nanoTime()}.bin")
        try {
            tempFile.writeBytes(data)
            client.copyFile(tempFile.parent!!, tempFile.name, remoteName, path)
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun stat(path: String): SftpEntry = withContext(Dispatchers.IO) {
        // rclone's RPC has no single-entry stat — list the parent
        // remote-path and filter. Bounded by parent-dir size; acceptable
        // for serve_file's per-call consent flow.
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty {
            throw java.io.FileNotFoundException("Cannot derive name from path: $path")
        }
        list(parent).firstOrNull { it.name == name }
            ?: throw java.io.FileNotFoundException(path)
    }

    /**
     * Stream via a temp-file download — rclone's gomobile RPC is
     * file-path oriented, not stream oriented. Caller closes the
     * returned stream; close also deletes the cached temp file so
     * heavy `serve_file` usage doesn't fill the app cache.
     *
     * For media files the existing rclone HTTP serve (used by the
     * frame-preview / HLS pipeline) is the lighter path; this method
     * exists so non-media files (PDFs, configs, source) can also be
     * served via the MCP `serve_file` tool without per-backend special
     * casing on the call site.
     */
    override suspend fun openInputStream(path: String, offset: Long): java.io.InputStream =
        withContext(Dispatchers.IO) {
            val tempFile = File(appContext.cacheDir, "rclone-serve-${System.nanoTime()}.bin")
            try {
                client.copyFile(remoteName, path, tempFile.parent!!, tempFile.name)
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
            val raw = java.io.FileInputStream(tempFile)
            if (offset > 0) raw.channel.position(offset)
            object : java.io.InputStream() {
                override fun read(): Int = raw.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = raw.read(b, off, len)
                override fun available(): Int = raw.available()
                override fun close() {
                    try { raw.close() } finally { tempFile.delete() }
                }
            }
        }

    /**
     * Probe whether [path] points at a directory by listing its parent.
     * Returns false when the entry isn't found at all — let the caller's
     * subsequent operation surface the missing-entry error rather than
     * pretending we know the answer.
     */
    private fun isDirectory(path: String): Boolean {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return true
        val slash = trimmed.lastIndexOf('/')
        val parent = if (slash < 0) "" else trimmed.substring(0, slash)
        val name = if (slash < 0) trimmed else trimmed.substring(slash + 1)
        return try {
            client.listDirectory(remoteName, parent)
                .firstOrNull { it.name == name }?.isDir == true
        } catch (_: Exception) {
            false
        }
    }
}
