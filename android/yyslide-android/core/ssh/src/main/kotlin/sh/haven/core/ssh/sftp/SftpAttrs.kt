package sh.haven.core.ssh.sftp

/**
 * Attributes for an SFTP directory entry or stat result.
 *
 * Mirrors the subset of `SFTP_FXP_ATTRS` (RFC draft, §5) that Haven's UI uses.
 * Returned by [SftpSession.list]'s callback and by [SftpSession.stat]; lets
 * callers build their own `SftpEntry` representations without seeing
 * `com.jcraft.jsch.ChannelSftp.LsEntry` or `SftpATTRS`.
 *
 * For `list`, [filename] is the bare basename. For `stat`, [filename] is
 * the last segment of the queried path.
 */
data class SftpAttrs(
    val filename: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** Modification time as a UNIX timestamp in seconds. */
    val modifiedTimeSeconds: Int,
    /** Permissions in the `-rwxr-xr-x` rendering; empty if the server did not return them. */
    val permissions: String,
    val uid: Int,
    val gid: Int,
)
