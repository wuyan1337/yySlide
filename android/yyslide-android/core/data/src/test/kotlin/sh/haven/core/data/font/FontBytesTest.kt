package sh.haven.core.data.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FontBytesTest {

    private fun hdr(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun `classifies sfnt TrueType`() =
        assertEquals(FontBytes.FontKind.TTF, FontBytes.classify(hdr(0x00, 0x01, 0x00, 0x00, 0, 0)))

    @Test fun `classifies true-tagged TrueType`() =
        assertEquals(FontBytes.FontKind.TTF, FontBytes.classify(ascii("true....")))

    @Test fun `classifies OTTO as OTF`() =
        assertEquals(FontBytes.FontKind.OTF, FontBytes.classify(ascii("OTTO....")))

    @Test fun `classifies ttcf as TTC`() =
        assertEquals(FontBytes.FontKind.TTC, FontBytes.classify(ascii("ttcf....")))

    @Test fun `classifies wOFF as WOFF`() =
        assertEquals(FontBytes.FontKind.WOFF, FontBytes.classify(ascii("wOFF....")))

    @Test fun `classifies wOF2 as WOFF2`() =
        assertEquals(FontBytes.FontKind.WOFF2, FontBytes.classify(ascii("wOF2....")))

    @Test fun `classifies PK header as ZIP`() =
        assertEquals(FontBytes.FontKind.ZIP, FontBytes.classify(hdr(0x50, 0x4B, 0x03, 0x04, 0x14)))

    @Test fun `classifies leading angle bracket as HTML`() =
        assertEquals(FontBytes.FontKind.HTML, FontBytes.classify(ascii("<!DOCTYPE html>")))

    @Test fun `classifies junk as UNKNOWN`() =
        assertEquals(FontBytes.FontKind.UNKNOWN, FontBytes.classify(hdr(0xDE, 0xAD, 0xBE, 0xEF)))

    @Test fun `too-short header is UNKNOWN`() =
        assertEquals(FontBytes.FontKind.UNKNOWN, FontBytes.classify(hdr(0x00, 0x01)))

    // --- pickFontEntry ---

    @Test fun `prefers Regular ttf over style variants`() {
        val names = listOf(
            "MapleMono-TTF/MapleMono-BoldItalic.ttf",
            "MapleMono-TTF/MapleMono-Bold.ttf",
            "MapleMono-TTF/MapleMono-Regular.ttf",
            "MapleMono-TTF/MapleMono-Italic.ttf",
            "LICENSE.txt",
        )
        assertEquals("MapleMono-TTF/MapleMono-Regular.ttf", FontBytes.pickFontEntry(names))
    }

    @Test fun `prefers ttf over otf`() {
        val names = listOf("Family-Regular.otf", "Family-Regular.ttf")
        assertEquals("Family-Regular.ttf", FontBytes.pickFontEntry(names))
    }

    @Test fun `falls back to a plain face when no Regular`() {
        val names = listOf("dir/Bold.ttf", "dir/Family.ttf")
        assertEquals("dir/Family.ttf", FontBytes.pickFontEntry(names))
    }

    @Test fun `falls back to first ttf when only styled faces exist`() {
        val names = listOf("a/Thin.ttf", "a/Black.ttf")
        assertEquals("a/Thin.ttf", FontBytes.pickFontEntry(names))
    }

    @Test fun `picks otf when no ttf present`() {
        val names = listOf("x/Family-Regular.otf", "x/Family-Bold.otf")
        assertEquals("x/Family-Regular.otf", FontBytes.pickFontEntry(names))
    }

    @Test fun `ignores directories and non-fonts`() {
        assertNull(FontBytes.pickFontEntry(listOf("fonts/", "README.md", "preview.png")))
    }

    @Test fun `empty list returns null`() = assertNull(FontBytes.pickFontEntry(emptyList()))
}
