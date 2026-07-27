package sh.haven.feature.sftp.transport

import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.feature.sftp.SftpEntry

/**
 * [RemoteFileTransport] backed by Haven's [SftpSession] facade over the
 * underlying SSH SFTP channel. A thin adapter that lifts the SFTP operations
 * out of SftpViewModel so the view model's SSH code path stays
 * transport-agnostic.
 *
 * [sshClient] is optional — it is only consulted for [chown], which
 * needs a shell exec channel because SFTP's chown requires a numeric UID
 * and users usually want to chown by name.
 */
class SftpTransport(
    private val sessionProvider: () -> SftpSession,
    private val sshClient: SshClient? = null,
) : RemoteFileTransport {

    override val label: String = "SFTP"

    override suspend fun list(path: String): List<SftpEntry> {
        val session = sessionProvider()
        val results = mutableListOf<SftpEntry>()
        val symlinkIndices = mutableListOf<Int>()
        session.list(path) { attrs ->
            val fullPath = path.trimEnd('/') + "/" + attrs.filename
            if (attrs.isSymlink) symlinkIndices.add(results.size)
            results.add(
                SftpEntry(
                    name = attrs.filename,
                    path = fullPath,
                    isDirectory = attrs.isDirectory,
                    size = attrs.size,
                    modifiedTime = attrs.modifiedTimeSeconds.toLong(),
                    permissions = attrs.permissions,
                    // SFTP reports UID/GID as integers — no name lookup over
                    // the subsystem. Users can still type a name in the chown
                    // dialog; the server resolves it.
                    owner = attrs.uid.toString(),
                    group = attrs.gid.toString(),
                ),
            )
            ListResult.CONTINUE
        }
        // Resolve symlinks AFTER list() completes — calling stat() inside the
        // list callback corrupts the SFTP read buffer (interleaved requests).
        for (i in symlinkIndices) {
            try {
                if (session.stat(results[i].path).isDirectory) {
                    results[i] = results[i].copy(isDirectory = true)
                }
            } catch (_: Exception) {
                // broken symlink or permission denied
            }
        }
        return results
    }

    override suspend fun upload(
        input: java.io.InputStream,
        sizeHint: Long,
        destPath: String,
        onBytes: (Long, Long) -> Unit,
    ) {
        sessionProvider().upload(input, sizeHint, destPath, onBytes = onBytes)
    }

    override suspend fun download(
        srcPath: String,
        output: java.io.OutputStream,
        sizeHint: Long,
        onBytes: (Long, Long) -> Unit,
    ) {
        // sizeHint is unused — the SFTP server reports the actual size via
        // SFTP_FXP_ATTRS during the open(); SftpSession surfaces it through
        // the (transferred, total) callback.
        sessionProvider().download(srcPath, output, onBytes)
    }

    /**
     * Direct streaming download at an arbitrary byte offset. Used by
     * [SftpStreamServer]'s opener and the MCP `serve_file` tool — both
     * want a fresh InputStream without the [download] progress overhead.
     */
    override suspend fun openInputStream(path: String, offset: Long): java.io.InputStream =
        sessionProvider().openInputStream(path, offset)

    override suspend fun stat(path: String): SftpEntry {
        val attrs = sessionProvider().stat(path)
        return SftpEntry(
            name = attrs.filename,
            path = path,
            isDirectory = attrs.isDirectory,
            size = attrs.size,
            modifiedTime = attrs.modifiedTimeSeconds.toLong(),
            permissions = attrs.permissions,
        )
    }

    override suspend fun mkdir(path: String) {
        sessionProvider().mkdir(path)
    }

    override suspend fun rename(from: String, to: String) {
        sessionProvider().rename(from, to)
    }

    override suspend fun delete(path: String, isDirectory: Boolean) {
        val session = sessionProvider()
        if (!isDirectory) {
            session.rm(path)
            return
        }
        val attrs = session.lstat(path)
        if (attrs.isSymlink) {
            session.rm(path)
            return
        }
        deleteDirectoryRecursive(session, path)
    }

    override suspend fun chmod(path: String, mode: Int) {
        sessionProvider().chmod(path, mode)
    }

    override suspend fun chown(path: String, owner: String) {
        val ssh = sshClient
            ?: throw UnsupportedOperationException("chown requires a shell channel — not available on this session")
        val cmd = "chown ${shellQuote(owner)} -- ${shellQuote(path)}"
        val r = ssh.execCommand(cmd)
        if (r.exitStatus != 0) throw java.io.IOException("chown failed: ${r.stderr.trim()}")
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    private suspend fun deleteDirectoryRecursive(session: SftpSession, path: String) {
        val children = mutableListOf<Pair<String, Boolean>>()
        session.list(path) { attrs ->
            val childPath = joinRemotePath(path, attrs.filename)
            children.add(childPath to (attrs.isDirectory && !attrs.isSymlink))
            ListResult.CONTINUE
        }
        for ((childPath, childIsDirectory) in children) {
            if (childIsDirectory) {
                deleteDirectoryRecursive(session, childPath)
            } else {
                session.rm(childPath)
            }
        }
        session.rmdir(path)
    }

    private fun joinRemotePath(parent: String, name: String): String =
        if (parent.isEmpty() || parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
}
