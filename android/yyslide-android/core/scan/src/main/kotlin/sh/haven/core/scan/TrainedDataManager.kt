package sh.haven.core.scan

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Tesseract trained-data files on internal storage.
 *
 * Tesseract's `init()` takes a parent directory that must contain a
 * `tessdata/` subdir with `<lang>.traineddata` inside. We ship the English
 * `_fast` LSTM model in `assets/tessdata/` and copy it to
 * `filesDir/tessdata/` on first OCR use. Subsequent calls return the
 * cached path without I/O.
 *
 * Bundled asset is the `eng.traineddata` from
 * https://github.com/tesseract-ocr/tessdata_fast (Apache 2.0, ~4 MB).
 * The "fast" variant trades a few percent of accuracy for a ~3× speedup
 * and much smaller download — the right trade for terminal-screenshot
 * style input on a phone CPU.
 */
@Singleton
class TrainedDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val tessdataDir: File = File(context.filesDir, "tessdata")

    /**
     * @return the parent directory to hand to Tesseract's `init(parent, "eng")`,
     *   i.e. the directory that contains the `tessdata/` folder.
     * @throws TrainedDataException if the asset is missing or the copy fails.
     */
    suspend fun ensureEnglish(): File = ensureLanguage("eng")

    /**
     * Returns the parent directory containing tessdata/<language>.traineddata,
     * extracting the bundled asset if necessary.
     */
    suspend fun ensureLanguage(language: String): File = withContext(Dispatchers.IO) {
        val target = File(tessdataDir, "$language.traineddata")
        if (target.exists() && target.length() > 0) {
            return@withContext context.filesDir
        }
        mutex.withLock {
            // Re-check after acquiring the lock — another caller may have
            // landed the file while we were waiting.
            if (target.exists() && target.length() > 0) return@withLock
            if (!tessdataDir.exists() && !tessdataDir.mkdirs()) {
                throw TrainedDataException(
                    "Could not create tessdata directory at ${tessdataDir.absolutePath}",
                )
            }
            val assetPath = "tessdata/$language.traineddata"
            try {
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                target.delete()
                throw TrainedDataException(
                    "Failed to extract tessdata asset '$assetPath': ${e.message}",
                    e,
                )
            }
            Log.i(TAG, "Extracted $assetPath (${target.length()} bytes) → $target")
        }
        context.filesDir
    }

    private companion object {
        const val TAG = "TrainedDataManager"
    }
}

class TrainedDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
