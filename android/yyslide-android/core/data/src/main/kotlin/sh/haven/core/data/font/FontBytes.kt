package sh.haven.core.data.font

/**
 * Pure helpers for identifying what a downloaded "font" actually is, so
 * [TerminalFontInstaller] can extract a usable face from an archive and
 * give an actionable error for formats Android can't render (#177).
 *
 * No Android dependencies — unit-testable on the JVM.
 */
object FontBytes {

    enum class FontKind {
        /** TrueType outlines (sfnt `0x00010000` or `true`). */
        TTF,

        /** OpenType/CFF outlines (`OTTO`). */
        OTF,

        /** TrueType Collection (`ttcf`) — multiple faces; Android takes the first. */
        TTC,

        /** Web Open Font Format 1 (`wOFF`) — not decodable by android.graphics. */
        WOFF,

        /** Web Open Font Format 2 (`wOF2`) — not decodable by android.graphics. */
        WOFF2,

        /** ZIP archive (`PK\x03\x04`) — likely a release bundle of fonts. */
        ZIP,

        /** An HTML page (leading `<`) — usually a wrong/landing-page URL. */
        HTML,

        /** Anything we don't recognise. */
        UNKNOWN,
    }

    /**
     * Classify by the leading magic bytes. Needs at least 4 bytes; fewer
     * returns [FontKind.UNKNOWN].
     */
    fun classify(header: ByteArray): FontKind {
        if (header.size < 4) return FontKind.UNKNOWN
        fun tag(vararg c: Char) = c.withIndex().all { (i, ch) -> header[i] == ch.code.toByte() }
        fun bytes(vararg b: Int) = b.withIndex().all { (i, v) -> header[i] == v.toByte() }
        return when {
            bytes(0x00, 0x01, 0x00, 0x00) -> FontKind.TTF
            tag('t', 'r', 'u', 'e') -> FontKind.TTF
            tag('O', 'T', 'T', 'O') -> FontKind.OTF
            tag('t', 't', 'c', 'f') -> FontKind.TTC
            tag('w', 'O', 'F', 'F') -> FontKind.WOFF
            tag('w', 'O', 'F', '2') -> FontKind.WOFF2
            // ZIP local-file-header signature "PK\x03\x04".
            bytes(0x50, 0x4B, 0x03, 0x04) -> FontKind.ZIP
            header[0] == '<'.code.toByte() -> FontKind.HTML
            else -> FontKind.UNKNOWN
        }
    }

    /**
     * Pick the best font entry from a ZIP's entry names. Prefers a Regular
     * upright `.ttf`, then any `.ttf`, then any `.otf`. Directory entries and
     * non-font files are ignored. Returns null if there's no `.ttf`/`.otf`.
     *
     * "Regular" excludes obvious style variants (italic/oblique/bold/thin/…)
     * so e.g. a Maple `MapleMono-TTF.zip` resolves to `MapleMono-Regular.ttf`
     * rather than `MapleMono-BoldItalic.ttf`.
     */
    fun pickFontEntry(names: List<String>): String? {
        val fonts = names.filter { !it.endsWith("/") }.filter {
            val lower = it.lowercase()
            lower.endsWith(".ttf") || lower.endsWith(".otf")
        }
        if (fonts.isEmpty()) return null

        fun base(n: String) = n.substringAfterLast('/').lowercase()
        val styleWords = listOf(
            "italic", "oblique", "bold", "thin", "light", "medium",
            "semibold", "extrabold", "black", "heavy", "extralight",
        )
        fun isRegular(n: String): Boolean {
            val b = base(n)
            return b.contains("regular") && styleWords.none { b.contains(it) }
        }
        // Plain "no descriptor" faces (e.g. "Family.ttf") also count as regular.
        fun isPlain(n: String): Boolean = styleWords.none { base(n).contains(it) }

        val ttf = fonts.filter { base(it).endsWith(".ttf") }
        val otf = fonts.filter { base(it).endsWith(".otf") }
        return ttf.firstOrNull { isRegular(it) }
            ?: ttf.firstOrNull { isPlain(it) }
            ?: ttf.firstOrNull()
            ?: otf.firstOrNull { isRegular(it) }
            ?: otf.firstOrNull { isPlain(it) }
            ?: otf.firstOrNull()
    }
}
