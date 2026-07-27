package sh.haven.core.ssh.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.ssh.SshIoException
import java.io.InputStream
import java.io.OutputStream

/**
 * [SftpSession] backed by a JSch [ChannelSftp].
 *
 * The JSch channel is owned externally (by [sh.haven.core.ssh.SshSessionManager])
 * — this class does not connect or disconnect it. [isConnected] reflects the
 * channel's live state.
 *
 * Translates `com.jcraft.jsch.JSchException` and `com.jcraft.jsch.SftpException`
 * to [SshIoException] so callers do not need to import JSch types.
 */
internal class JschSftpSession(private val channel: ChannelSftp) : SftpSession {

    override val isConnected: Boolean
        get() = channel.isConnected

    override suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult) =
        withContext(Dispatchers.IO) {
            translatingJschErrors {
                channel.ls(path) { ls ->
                    val name = ls.filename
                    if (name == "." || name == "..") {
                        ChannelSftp.LsEntrySelector.CONTINUE
                    } else {
                        val attrs = ls.attrs
                        val verdict = onEntry(
                            SftpAttrs(
                                filename = name,
                                isDirectory = attrs.isDir,
                                isSymlink = attrs.isLink,
                                size = attrs.size,
                                modifiedTimeSeconds = attrs.mTime,
                                permissions = attrs.permissionsString ?: "",
                                uid = attrs.uId,
                                gid = attrs.gId,
                            ),
                        )
                        when (verdict) {
                            ListResult.CONTINUE -> ChannelSftp.LsEntrySelector.CONTINUE
                            ListResult.BREAK -> ChannelSftp.LsEntrySelector.BREAK
                        }
                    }
                }
            }
            Unit
        }

    override suspend fun stat(path: String): SftpAttrs = withContext(Dispatchers.IO) {
        translatingJschErrors {
            val attrs = channel.stat(path)
            SftpAttrs(
                filename = path.substringAfterLast('/').ifEmpty { path },
                isDirectory = attrs.isDir,
                isSymlink = attrs.isLink,
                size = attrs.size,
                modifiedTimeSeconds = attrs.mTime,
                permissions = attrs.permissionsString ?: "",
                uid = attrs.uId,
                gid = attrs.gId,
            )
        }
    }

    override suspend fun lstat(path: String): SftpAttrs = withContext(Dispatchers.IO) {
        translatingJschErrors {
            val attrs = channel.lstat(path)
            SftpAttrs(
                filename = path.substringAfterLast('/').ifEmpty { path },
                isDirectory = attrs.isDir,
                isSymlink = attrs.isLink,
                size = attrs.size,
                modifiedTimeSeconds = attrs.mTime,
                permissions = attrs.permissionsString ?: "",
                uid = attrs.uId,
                gid = attrs.gId,
            )
        }
    }

    override suspend fun download(
        srcPath: String,
        output: OutputStream,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        translatingJschErrors {
            channel.get(srcPath, output, lambdaMonitor(sizeHint = -1, onBytes = onBytes))
        }
    }

    override suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        mode: SftpWriteMode,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        translatingJschErrors {
            val jschMode = when (mode) {
                SftpWriteMode.OVERWRITE -> ChannelSftp.OVERWRITE
                SftpWriteMode.RESUME -> ChannelSftp.RESUME
            }
            channel.put(input, destPath, lambdaMonitor(sizeHint = sizeHint, onBytes = onBytes), jschMode)
        }
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            translatingJschErrors {
                channel.get(path, null as SftpProgressMonitor?, offset)
            }
        }

    override suspend fun home(): String = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.home }
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.mkdir(path) }
    }

    override suspend fun rmdir(path: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.rmdir(path) }
    }

    override suspend fun rm(path: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.rm(path) }
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.rename(from, to) }
    }

    override suspend fun chmod(path: String, mode: Int) = withContext(Dispatchers.IO) {
        translatingJschErrors { channel.chmod(mode, path) }
    }

    override fun close() {
        try {
            channel.disconnect()
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun lambdaMonitor(
        sizeHint: Long,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ): SftpProgressMonitor = object : SftpProgressMonitor {
        private var total = 0L
        private var transferred = 0L
        override fun init(op: Int, src: String, dest: String, max: Long) {
            total = if (max == SftpProgressMonitor.UNKNOWN_SIZE) sizeHint else max
            transferred = 0L
            onBytes(0L, total)
        }
        override fun count(bytes: Long): Boolean {
            transferred += bytes
            onBytes(transferred, total)
            return true
        }
        override fun end() {
            onBytes(total, total)
        }
    }

    private inline fun <T> translatingJschErrors(block: () -> T): T = try {
        block()
    } catch (e: SftpException) {
        throw SshIoException(e.message, e)
    } catch (e: JSchException) {
        throw SshIoException(e.message, e)
    }
}
