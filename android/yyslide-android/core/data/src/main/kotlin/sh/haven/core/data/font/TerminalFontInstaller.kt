package sh.haven.core.data.font

import android.content.Context
import android.graphics.Typeface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.preferences.UserPreferencesRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard cap on a downloaded font (or font archive) so a misdirected URL
 * can't fill the FS. Generous because multi-weight / Nerd-Font zips are
 * legitimately large; the single extracted+validated face is what's kept.
 */
private const val MAX_FONT_BYTES = 32L * 1024 * 1024

/** Buffer for streamed download. 8 KiB is a comfortable middle ground. */
private const val DOWNLOAD_BUFFER_BYTES = 8 * 1024

/**
 * Single source of truth for installing a custom terminal font (#123).
 * Both the user-facing Settings flow and the agent-facing MCP tool call
 * the same routines here, so the two surfaces can never diverge — VISION
 * §85's "shared viewport" rule applies to the install path too, not just
 * the read/observe path.
 *
 * Owns:
 *  - URL download / SAF copy with size cap and timeout.
 *  - Format sniffing: extracts a `.ttf`/`.otf` from a downloaded `.zip`
 *    (font repos like Maple ship only zips, #177) and gives an actionable
 *    error for web-font formats (WOFF/WOFF2) Android can't render.
 *  - Typeface decode validation, so a bad input never poisons the
 *    Settings store with a path that would crash rendering.
 *  - Path persistence via [UserPreferencesRepository.setTerminalFontPath].
 *  - Cleanup of stale sibling files left behind by a prior import.
 */
@Singleton
class TerminalFontInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UserPreferencesRepository,
) {

    /**
     * Outcome of an install attempt. Modeled as a sealed interface so
     * call sites can pattern-match instead of stringly checking.
     * [Failure.message] is suitable for direct surfacing in a Toast or
     * an MCP error code.
     */
    sealed interface Result {
        data class Success(val path: String, val bytesInstalled: Long) : Result
        data class Failure(val message: String) : Result
    }

    /**
     * Download a font from [urlString] over http(s), then install it. The
     * URL may resolve to a `.ttf`/`.otf`, or a `.zip` containing them — an
     * HTML landing page or a WOFF/WOFF2 web font is rejected with a clear
     * message.
     */
    suspend fun installFromUrl(urlString: String): Result = withContext(Dispatchers.IO) {
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            return@withContext Result.Failure("Invalid URL: ${e.message}")
        }
        if (url.protocol !in setOf("http", "https")) {
            return@withContext Result.Failure("Only http(s) URLs are supported (got ${url.protocol})")
        }
        val tmp = prepareTempFile()
        try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "yySlide/1.0 (TerminalFontInstaller)")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext failAndClean(tmp, "HTTP ${conn.responseCode} from ${url.host}")
            }
            try {
                val capped = conn.inputStream.use { input -> streamToFile(input, tmp) }
                if (capped != null) return@withContext failAndClean(tmp, capped)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return@withContext failAndClean(tmp, "Download failed: ${e.message}")
        }
        materializeFont(tmp)
    }

    /**
     * Copy a font chosen via the Storage Access Framework into Haven's
     * private files dir and install it. Owning the file (rather than the
     * source content URI) means the chosen font keeps working across phone
     * reboots, source-app uninstalls, and SAF permission revocations.
     * Accepts a `.ttf`/`.otf` or a `.zip` of them.
     */
    suspend fun installFromContentUri(uri: android.net.Uri, displayName: String): Result = withContext(Dispatchers.IO) {
        val tmp = prepareTempFile()
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext failAndClean(tmp, "Could not open the selected file")
            val capped = input.use { stream -> streamToFile(stream, tmp) }
            if (capped != null) return@withContext failAndClean(tmp, capped)
        } catch (e: Exception) {
            return@withContext failAndClean(tmp, "Import failed: ${e.message}")
        }
        materializeFont(tmp)
    }

    /** Reset to the bundled default font. Idempotent. */
    suspend fun reset() {
        preferencesRepository.setTerminalFontPath(null)
        runCatching {
            File(context.filesDir, "fonts").listFiles()?.forEach { it.delete() }
        }
    }

    // --- internals ---

    private fun fontsDir(): File = File(context.filesDir, "fonts").apply { mkdirs() }

    private fun prepareTempFile(): File = File(fontsDir(), "download.tmp")

    private fun prepareTargetFile(ext: String): File = File(fontsDir(), "terminal.$ext")

    /**
     * Stream [input] into [dest], enforcing [MAX_FONT_BYTES]. Returns null
     * on success, or an error message when the cap is exceeded.
     */
    private fun streamToFile(input: java.io.InputStream, dest: File): String? {
        dest.outputStream().use { output ->
            val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FONT_BYTES) {
                    return "Font exceeds ${MAX_FONT_BYTES / (1024 * 1024)} MiB cap"
                }
                output.write(buf, 0, n)
            }
        }
        return null
    }

    /**
     * Identify what landed in [tmp] and turn it into an installed font, or a
     * clear failure. Deletes [tmp] before returning.
     */
    private suspend fun materializeFont(tmp: File): Result {
        val header = ByteArray(16)
        val read = try {
            tmp.inputStream().use { it.read(header) }
        } catch (e: Exception) {
            return failAndClean(tmp, "Could not read the downloaded file: ${e.message}")
        }
        val kind = FontBytes.classify(if (read >= header.size) header else header.copyOf(maxOf(read, 0)))
        return when (kind) {
            FontBytes.FontKind.TTF, FontBytes.FontKind.TTC -> finalizeFromTemp(tmp, "ttf")
            FontBytes.FontKind.OTF -> finalizeFromTemp(tmp, "otf")
            FontBytes.FontKind.ZIP -> extractFromZip(tmp)
            FontBytes.FontKind.WOFF, FontBytes.FontKind.WOFF2 -> failAndClean(
                tmp,
                "That's a WOFF/WOFF2 web font, which Android can't render. " +
                    "Download the .ttf or .otf instead (e.g. MapleMono-TTF.zip).",
            )
            FontBytes.FontKind.HTML -> failAndClean(
                tmp,
                "That URL returned a web page, not a font — use a direct link to " +
                    "a .ttf/.otf or a .zip of them.",
            )
            FontBytes.FontKind.UNKNOWN -> failAndClean(
                tmp,
                "Couldn't recognise that as a font (.ttf/.otf) or a font archive (.zip).",
            )
        }
    }

    private suspend fun finalizeFromTemp(tmp: File, ext: String): Result {
        val target = prepareTargetFile(ext)
        if (target.absolutePath != tmp.absolutePath) {
            runCatching { tmp.copyTo(target, overwrite = true) }
                .onFailure { return failAndClean(tmp, "Could not store the font: ${it.message}") }
        }
        runCatching { tmp.delete() }
        return finishInstall(target, target.length())
    }

    /**
     * Pull the best `.ttf`/`.otf` out of the ZIP at [tmp] and install it.
     * Font repos (Maple, many Nerd Fonts) distribute only archives. (#177)
     */
    private suspend fun extractFromZip(tmp: File): Result {
        val names = try {
            ZipFile(tmp).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
        } catch (e: Exception) {
            return failAndClean(tmp, "That looked like a .zip but couldn't be read: ${e.message}")
        }
        val chosen = FontBytes.pickFontEntry(names)
            ?: return failAndClean(tmp, "That .zip has no .ttf/.otf font inside.")
        val ext = if (chosen.lowercase().endsWith(".otf")) "otf" else "ttf"
        val target = prepareTargetFile(ext)
        try {
            ZipFile(tmp).use { zf ->
                val entry = zf.getEntry(chosen)
                    ?: return failAndClean(tmp, "Could not find $chosen in the .zip")
                zf.getInputStream(entry).use { input ->
                    val capped = streamToFile(input, target)
                    if (capped != null) {
                        runCatching { tmp.delete() }
                        return failAndClean(target, capped)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return failAndClean(target, "Failed to extract the font from the .zip: ${e.message}")
        }
        runCatching { tmp.delete() }
        return finishInstall(target, target.length())
    }

    private suspend fun finishInstall(target: File, written: Long): Result {
        val decoded = runCatching { Typeface.createFromFile(target) }.getOrNull() != null
        if (!decoded) {
            target.delete()
            return Result.Failure(
                "Downloaded $written bytes but Android could not decode them as a Typeface " +
                    "(corrupt or unsupported font?)",
            )
        }
        // Drop sibling files left from a prior import (or the download temp)
        // so the path preference points at exactly one live file and orphans
        // don't leak storage.
        runCatching {
            target.parentFile?.listFiles()?.forEach {
                if (it.absolutePath != target.absolutePath) it.delete()
            }
        }
        preferencesRepository.setTerminalFontPath(target.absolutePath)
        return Result.Success(path = target.absolutePath, bytesInstalled = written)
    }

    private fun failAndClean(target: File, message: String): Result {
        runCatching { target.delete() }
        return Result.Failure(message)
    }
}
