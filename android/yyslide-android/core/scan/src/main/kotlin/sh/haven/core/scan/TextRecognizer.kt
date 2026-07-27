package sh.haven.core.scan

import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs Tesseract OCR over a still [Bitmap].
 *
 * The native engine is initialised lazily on the first call so app start-up
 * stays cheap — Tesseract's init reads a ~4 MB trained-data file and
 * allocates its leptonica state. We hold a single [TessBaseAPI] instance
 * across calls (init is the expensive bit) and serialise access with a
 * Mutex; Tesseract's C++ object is not thread-safe.
 *
 * The [TessEngine] seam keeps this class JVM-testable: the production
 * implementation wraps [TessBaseAPI]; tests provide a fake.
 */
@Singleton
class TextRecognizer @Inject constructor(
    private val trainedData: TrainedDataManager,
    private val engineFactory: TessEngineFactory,
) {
    private val mutex = Mutex()
    @Volatile private var engine: TessEngine? = null

    /**
     * @param bitmap source image. Caller retains ownership — we do not
     *   recycle it.
     * @return recognised text trimmed of leading/trailing whitespace, or
     *   null when Tesseract produced an empty string (no glyphs detected).
     * @throws TrainedDataException if the bundled traineddata is missing.
     * @throws TextRecognizeException on native engine failure.
     */
    suspend fun recognize(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        val dataDir: File = trainedData.ensureEnglish()
        mutex.withLock {
            val eng = engine ?: try {
                engineFactory.create(dataDir, "eng").also { engine = it }
            } catch (t: Throwable) {
                throw TextRecognizeException("Tesseract init failed: ${t.message}", t)
            }
            try {
                eng.setImage(bitmap)
                val raw = eng.getUtf8Text().trim()
                raw.takeIf { it.isNotEmpty() }
            } catch (t: Throwable) {
                throw TextRecognizeException("Tesseract recognise failed: ${t.message}", t)
            } finally {
                // Don't end() the engine — keeping it alive avoids re-reading
                // the trained-data file on the next call. We clear the image
                // so the bitmap can be GC'd, though.
                runCatching { eng.clear() }
            }
        }
    }

    /** Release the native engine. Call from a logout / settings flow. */
    suspend fun shutdown() {
        mutex.withLock {
            engine?.let { runCatching { it.end() } }
            engine = null
        }
    }

    /** Minimal seam over [TessBaseAPI] so the orchestration is testable. */
    interface TessEngine {
        fun setImage(bitmap: Bitmap)
        fun getUtf8Text(): String
        fun clear()
        fun end()
    }

    /** Factory so tests can swap in a fake engine. */
    fun interface TessEngineFactory {
        fun create(dataParentDir: File, language: String): TessEngine
    }

    private companion object {
        const val TAG = "TextRecognizer"
    }
}

/**
 * Production factory: wraps a real [TessBaseAPI]. Provided as a separate
 * binding (see [ScanModule]) so the recognizer's constructor stays
 * Hilt-friendly and so tests can substitute the factory without touching
 * the JNI library.
 */
@Singleton
class DefaultTessEngineFactory @Inject constructor() : TextRecognizer.TessEngineFactory {
    override fun create(dataParentDir: File, language: String): TextRecognizer.TessEngine {
        val api = TessBaseAPI()
        val ok = api.init(dataParentDir.absolutePath, language)
        if (!ok) {
            api.recycle()
            error("TessBaseAPI.init returned false for ${dataParentDir.absolutePath}/$language")
        }
        Log.i("TextRecognizer", "TessBaseAPI initialised (data=$dataParentDir, lang=$language)")
        return object : TextRecognizer.TessEngine {
            override fun setImage(bitmap: Bitmap) = api.setImage(bitmap)
            override fun getUtf8Text(): String = api.utF8Text ?: ""
            override fun clear() = api.clear()
            override fun end() = api.recycle()
        }
    }
}

class TextRecognizeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
