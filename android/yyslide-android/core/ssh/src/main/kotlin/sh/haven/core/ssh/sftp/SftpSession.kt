package sh.haven.core.ssh.sftp

import java.io.InputStream
import java.io.OutputStream

/**
 * Haven-internal SFTP session interface.
 *
 * Lets callers in feature- and app-modules interact with a remote SFTP service
 * without importing `com.jcraft.jsch.ChannelSftp` or `SftpProgressMonitor`
 * directly. The current JSch-backed implementation lives in [JschSftpSession];
 * a sshlib-backed implementation will replace it in the JSch → sshlib swap.
 *
 * All methods are suspending and dispatch to IO themselves — callers do not
 * need to wrap calls in `withContext(Dispatchers.IO)`.
 */
interface SftpSession {

    /** `true` while the underlying channel is alive. */
    val isConnected: Boolean

    /**
     * Enumerate entries in [path]. The callback decides whether to continue
     * by returning [ListResult.CONTINUE] or stop with [ListResult.BREAK].
     *
     * `.` and `..` entries are NOT delivered to the callback.
     */
    suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult)

    /** Stat a single path. Symlinks are followed. */
    suspend fun stat(path: String): SftpAttrs

    /** Stat a single path without following symlinks. */
    suspend fun lstat(path: String): SftpAttrs

    /**
     * Download [srcPath] into [output]. [onBytes] is invoked from inside the
     * IO context with `(transferred, total)`; `total` may be 0 if the server
     * did not report a size up front.
     */
    suspend fun download(
        srcPath: String,
        output: OutputStream,
        onBytes: (transferred: Long, total: Long) -> Unit,
    )

    /**
     * Upload [input] to [destPath] using [mode] (default [SftpWriteMode.OVERWRITE]).
     * [sizeHint] is the caller's best-known total in bytes (used for progress
     * reporting when the wire protocol does not carry a size); -1 if unknown.
     */
    suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        mode: SftpWriteMode = SftpWriteMode.OVERWRITE,
        onBytes: (transferred: Long, total: Long) -> Unit,
    )

    /**
     * The server-side home directory for the authenticated user. Cheap on
     * SFTP (returned during channel handshake); cached after the first call.
     */
    suspend fun home(): String

    /**
     * Open a remote file as an [InputStream], optionally starting from
     * [offset]. The returned stream owns one SFTP transfer; close it when
     * done.
     */
    suspend fun openInputStream(path: String, offset: Long = 0): InputStream

    suspend fun mkdir(path: String)
    suspend fun rmdir(path: String)
    suspend fun rm(path: String)
    suspend fun rename(from: String, to: String)
    suspend fun chmod(path: String, mode: Int)

    /**
     * Close the underlying SFTP channel. After this returns, [isConnected]
     * is `false` and the channel reference held by [sh.haven.core.ssh.SshSessionManager]
     * (if any) is left dangling — the next `openSftpSession` call on that
     * manager observes the dead channel and opens a fresh one.
     */
    fun close()
}

/** Iterator-style verdict returned by [SftpSession.list] callbacks. */
enum class ListResult { CONTINUE, BREAK }

/**
 * Write mode for [SftpSession.upload].
 *
 * - [OVERWRITE]: replace any existing destination file (the default).
 * - [RESUME]: append to an existing destination, skipping the matching prefix
 *   bytes from [input] — used to continue an interrupted transfer.
 */
enum class SftpWriteMode { OVERWRITE, RESUME }
