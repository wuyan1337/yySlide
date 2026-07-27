package sh.haven.core.scan

/**
 * Outcome of a single recognition pass over a still image. The terminal's
 * attach flow turns [Success.text] into bytes and sends it at the cursor;
 * [NoMatch] and [Error] surface as a transient toast.
 */
sealed interface ScanResult {
    /** Recognition produced usable text. May be multi-line for OCR. */
    data class Success(val text: String, val source: Source) : ScanResult

    /**
     * The image was readable but no barcode / no text was found. Distinct
     * from [Error] so the UI can phrase it gently ("No code in this image")
     * rather than alarmingly.
     */
    data class NoMatch(val source: Source) : ScanResult

    /** Decoding itself failed — bitmap unreadable, native lib missing, etc. */
    data class Error(val source: Source, val message: String, val cause: Throwable? = null) : ScanResult

    enum class Source { BARCODE, OCR }
}
