package sh.haven.feature.sftp.attach

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.rclone.RcloneClient
import sh.haven.feature.sftp.transport.TransportSelector
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AttachCoordinator"

/**
 * Mediates the terminal's "attach (paperclip)" flow:
 *
 *  1. The terminal screen launches a SAF picker, then calls [attach] with
 *     the resulting URI. [attach] suspends and publishes a [pendingRequest]
 *     so the SFTP screen can render a folder-picker mode.
 *  2. The user navigates to a destination folder and confirms via
 *     [confirmFolder], or backs out via [cancel].
 *  3. On confirm, [attach] uploads the file (SSH or rclone) and returns
 *     the shell-quoted reference (absolute path or share URL) for the
 *     terminal to inject at the cursor. On cancel, [attach] returns null.
 *
 * Singleton-scoped so the SFTP and terminal screens — which live in
 * separate ViewModels — both observe the same [pendingRequest] state.
 */
@Singleton
class TerminalAttachCoordinator @Inject constructor(
    private val transportSelector: TransportSelector,
    private val rcloneClient: RcloneClient,
    private val connectionRepository: ConnectionRepository,
    @ApplicationContext private val appContext: Context,
) {

    /** Single attach in flight; null when no picker is active. */
    data class AttachRequest(
        val sourceUri: Uri,
        val fileName: String,
        val fileSize: Long,
        /** Profile to pre-select in the picker (the terminal's active session). */
        val initialProfileId: String?,
        internal val deferred: CompletableDeferred<DestinationChoice?>,
    )

    /** What the picker resolved to. */
    data class DestinationChoice(
        val profileId: String,
        val isRclone: Boolean,
        /** Non-null iff [isRclone]. The rclone backend name (e.g. "gdrive"). */
        val rcloneRemote: String?,
        val folderPath: String,
    )

    /** Coarse upload progress, surfaced to the SFTP screen for a snackbar. */
    data class Progress(val fileName: String, val totalBytes: Long, val transferredBytes: Long)

    private val _pendingRequest = MutableStateFlow<AttachRequest?>(null)
    val pendingRequest: StateFlow<AttachRequest?> = _pendingRequest.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /**
     * Run the picker → upload pipeline. Returns the shell-quoted paste
     * payload, or null if the user cancelled.
     */
    suspend fun attach(
        sourceUri: Uri,
        fileName: String,
        fileSize: Long,
        initialProfileId: String?,
    ): String? {
        val deferred = CompletableDeferred<DestinationChoice?>()
        val request = AttachRequest(sourceUri, fileName, fileSize, initialProfileId, deferred)
        _pendingRequest.value = request
        val choice = try {
            deferred.await()
        } finally {
            _pendingRequest.value = null
        } ?: return null
        return runCatching { performUpload(choice, fileName, sourceUri, fileSize) }
            .onFailure { Log.e(TAG, "Upload failed", it) }
            .getOrElse { return null }
    }

    /** Called by the SFTP screen when the user taps "Use this folder". */
    fun confirmFolder(profileId: String, isRclone: Boolean, rcloneRemote: String?, folderPath: String) {
        val req = _pendingRequest.value ?: return
        req.deferred.complete(DestinationChoice(profileId, isRclone, rcloneRemote, folderPath))
    }

    /** Called by the SFTP screen when the user backs out of pick mode. */
    fun cancel() {
        val req = _pendingRequest.value ?: return
        req.deferred.complete(null)
    }

    /**
     * Read the display name + size for a SAF URI. Falls back to the URI's
     * last segment when the provider doesn't supply a [OpenableColumns]
     * cursor.
     */
    fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        val resolvedName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
        return resolvedName to size
    }

    private suspend fun performUpload(
        choice: DestinationChoice,
        fileName: String,
        sourceUri: Uri,
        fileSize: Long,
    ): String = withContext(Dispatchers.IO) {
        val destPath = choice.folderPath.trimEnd('/') + "/" + fileName
        if (choice.isRclone) {
            val remote = choice.rcloneRemote
                ?: error("Rclone destination missing remote name")
            uploadToRclone(remote, destPath, fileName, sourceUri, fileSize)
        } else {
            uploadToSsh(choice.profileId, destPath, fileName, sourceUri, fileSize)
        }
    }

    private suspend fun uploadToSsh(
        profileId: String,
        destPath: String,
        fileName: String,
        sourceUri: Uri,
        fileSize: Long,
    ): String {
        val profile = connectionRepository.getById(profileId)
            ?: error("Profile not found: $profileId")
        val resolution = transportSelector.resolve(profile)
            ?: error("Transport unavailable for ${profile.label}")
        val input = appContext.contentResolver.openInputStream(sourceUri)
            ?: error("Cannot read source $sourceUri")
        try {
            _progress.value = Progress(fileName, fileSize, 0)
            resolution.transport.upload(input, fileSize, destPath) { transferred, total ->
                _progress.value = Progress(fileName, total, transferred)
            }
        } finally {
            runCatching { input.close() }
            _progress.value = null
        }
        return shellQuote(destPath)
    }

    private fun uploadToRclone(
        remote: String,
        destPath: String,
        fileName: String,
        sourceUri: Uri,
        fileSize: Long,
    ): String {
        // rclone copyfile takes a srcFs/srcRemote pair. For a SAF URI we
        // first stage the bytes to the cache dir so the local filesystem
        // backend can address it as <cacheDir>/<fileName>.
        val tempFile = File(appContext.cacheDir, "attach_${System.currentTimeMillis()}_$fileName")
        try {
            _progress.value = Progress(fileName, fileSize, 0)
            appContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot read source $sourceUri")
            rcloneClient.copyFile(tempFile.parent!!, tempFile.name, remote, destPath)
            _progress.value = Progress(fileName, fileSize, fileSize)
        } finally {
            tempFile.delete()
            _progress.value = null
        }

        val supportsLink = runCatching { rcloneClient.getCapabilities(remote).publicLink }
            .getOrDefault(false)
        if (supportsLink) {
            runCatching { rcloneClient.publicLink(remote, destPath) }
                .onSuccess { return shellQuote(it) }
                .onFailure { Log.w(TAG, "publicLink($remote, $destPath) failed", it) }
        }
        return shellQuote("$remote:$destPath")
    }
}
