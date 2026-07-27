package sh.haven.core.ssh

import java.io.IOException

/**
 * Haven-internal SSH/SFTP I/O exception.
 *
 * Thrown by `core/ssh` adapters (e.g. [sh.haven.core.ssh.sftp.JschSftpSession])
 * in place of library-specific exceptions like `com.jcraft.jsch.JSchException`
 * and `com.jcraft.jsch.SftpException`, so callers in feature- and app-modules
 * do not have to import library types directly.
 *
 * Treated as a transient/retryable error by SFTP transfer queues — equivalent
 * in retry semantics to a [java.net.SocketException].
 */
class SshIoException(message: String?, cause: Throwable? = null) : IOException(message, cause)
