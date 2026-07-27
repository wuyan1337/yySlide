package sh.haven.core.scan

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips a known string through ZXing's encoder, hands the resulting
 * pixel matrix to [BarcodeDecoder] as a Bitmap, and checks the decoder
 * recovers the original. Robolectric provides a real-enough Bitmap so
 * `getPixels` works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BarcodeDecoderTest {

    private val decoder = BarcodeDecoder()

    @Test
    fun `decode round-trips a QR payload`() {
        val payload = "ssh://droid@10.0.2.2:22"
        val bitmap = encode(payload, BarcodeFormat.QR_CODE, size = 256)

        val decoded = decoder.decode(bitmap)

        assertEquals(payload, decoded)
    }

    @Test
    fun `decode handles a longer Code128 payload`() {
        val payload = "kubectl -n haven get pods -o wide"
        val bitmap = encode(payload, BarcodeFormat.CODE_128, width = 600, height = 120)

        val decoded = decoder.decode(bitmap)

        assertEquals(payload, decoded)
    }

    @Test
    fun `decode returns null when no code is present`() {
        val blank = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertNull(decoder.decode(blank))
    }

    private fun encode(
        contents: String,
        format: BarcodeFormat,
        size: Int = 256,
        width: Int = size,
        height: Int = size,
    ): Bitmap {
        val matrix = MultiFormatWriter().encode(contents, format, width, height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return out
    }
}
