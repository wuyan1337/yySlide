package sh.haven.feature.sftp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.smb.SmbClient
import sh.haven.feature.sftp.SftpEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * [FileBackend] over a connected [SmbClient]. Constructed per-resolution
 * with the client baked in — the selector looks up the active client for
 * the profile via [sh.haven.core.smb.SmbSessionManager] and hands it here.
 */
class SmbFileBackend(
    private val client: SmbClient,
) : FileBackend {

    override val label: String = "SMB"

    override suspend fun list(path: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        client.listDirectory(path).map { entry ->
            SftpEntry(
                name = entry.name,
                path = entry.path,
                isDirectory = entry.isDirectory,
                size = entry.size,
                modifiedTime = entry.modifiedTime,
                permissions = entry.permissions,
            )
        }
    }

    override suspend fun delete(path: String, isDirectory: Boolean) = withContext(Dispatchers.IO) {
        client.delete(path, isDirectory)
    }

    override suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        client.mkdir(path)
    }

    override suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        client.rename(from, to)
    }

    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArrayOutputStream()
        client.download(path, buffer) { _, _ -> }
        buffer.toByteArray()
    }

    override suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        ByteArrayInputStream(data).use { input ->
            client.upload(input, path, data.size.toLong()) { _, _ -> }
        }
    }

    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        withContext(Dispatchers.IO) {
            client.openInputStream(path, offset)
        }

    override suspend fun stat(path: String): SftpEntry = withContext(Dispatchers.IO) {
        // smbj doesn't ship a single-entry stat, but listing the parent
        // and filtering by name is a single round-trip server-side. SMB
        // paths use forward slashes after toSmbPath normalisation.
        val parent = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty {
            throw java.io.FileNotFoundException("Cannot derive name from path: $path")
        }
        client.listDirectory(parent).firstOrNull { it.name == name }?.let { entry ->
            SftpEntry(
                name = entry.name,
                path = entry.path,
                isDirectory = entry.isDirectory,
                size = entry.size,
                modifiedTime = entry.modifiedTime,
                permissions = entry.permissions,
            )
        } ?: throw java.io.FileNotFoundException(path)
    }
}
