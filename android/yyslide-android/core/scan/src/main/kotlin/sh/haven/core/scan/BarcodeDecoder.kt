package sh.haven.core.scan

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decodes 1D / 2D barcodes from a still [Bitmap] using ZXing core.
 *
 * Kept Bitmap-in / String-out so the wrapper is JVM-testable: tests
 * construct an `RGBLuminanceSource` directly without any Android plumbing.
 *
 * Tries the photo at native resolution first; if no code is found and the
 * image is large, downscales and tries again. Real-world camera photos
 * routinely fail at native res because the QR fills only a fraction of the
 * frame and the binarizer chokes on the surrounding noise.
 */
@Singleton
class BarcodeDecoder @Inject constructor() {

    /**
     * @return the decoded payload string, or null if no supported code
     *   was found. Throws [BarcodeDecodeException] on unexpected errors.
     */
    fun decode(bitmap: Bitmap): String? {
        val firstPass = tryDecode(bitmap)
        if (firstPass != null) return firstPass

        // Downscale large images. Cameras commonly produce 12 MP frames
        // where a 1 cm QR ends up as a tiny square — ZXing's binarizer
        // does better when the code is a larger fraction of the image.
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= DOWNSCALE_TARGET) return null
        val scale = DOWNSCALE_TARGET.toFloat() / maxDim
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            /* filter = */ true,
        )
        return try {
            tryDecode(scaled)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun tryDecode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }
        return try {
            reader.decodeWithState(binary).text
        } catch (_: NotFoundException) {
            null
        } catch (t: Throwable) {
            throw BarcodeDecodeException("ZXing decode failed: ${t.message}", t)
        }
    }

    private companion object {
        /** Long-edge target for the retry pass. */
        const val DOWNSCALE_TARGET = 1024
    }
}

class BarcodeDecodeException(message: String, cause: Throwable?) : RuntimeException(message, cause)
