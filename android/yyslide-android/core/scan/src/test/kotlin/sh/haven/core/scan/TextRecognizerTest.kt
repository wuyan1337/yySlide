package sh.haven.core.scan

import android.graphics.Bitmap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Validates the orchestration TextRecognizer wraps around the native engine
 * without touching JNI. The real Tesseract engine is exercised on-device;
 * here we substitute a fake [TextRecognizer.TessEngineFactory] and assert
 * setImage / getUtf8Text / clear are called in the expected order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextRecognizerTest {

    private val bitmap: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun `recognize delegates to the engine and returns trimmed text`() = runTest {
        val engine = mockk<TextRecognizer.TessEngine>(relaxed = true) {
            every { getUtf8Text() } returns "  hello world\n"
        }
        val factory = TextRecognizer.TessEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureEnglish() } returns File("/tmp/fake")
        }

        val recognizer = TextRecognizer(trainedData, factory)
        val result = recognizer.recognize(bitmap)

        assertEquals("hello world", result)
        val bmpSlot = slot<Bitmap>()
        verify { engine.setImage(capture(bmpSlot)) }
        verify { engine.clear() }
        assertEquals(bitmap, bmpSlot.captured)
    }

    @Test
    fun `recognize returns null when the engine produces no text`() = runTest {
        val engine = mockk<TextRecognizer.TessEngine>(relaxed = true) {
            every { getUtf8Text() } returns "   \n  \t"
        }
        val factory = TextRecognizer.TessEngineFactory { _, _ -> engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureEnglish() } returns File("/tmp/fake")
        }

        val recognizer = TextRecognizer(trainedData, factory)

        assertNull(recognizer.recognize(bitmap))
    }

    @Test
    fun `engine is initialised once across calls`() = runTest {
        var created = 0
        val engine = mockk<TextRecognizer.TessEngine>(relaxed = true) {
            every { getUtf8Text() } returns "x"
        }
        val factory = TextRecognizer.TessEngineFactory { _, _ -> created++; engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureEnglish() } returns File("/tmp/fake")
        }

        val recognizer = TextRecognizer(trainedData, factory)
        recognizer.recognize(bitmap)
        recognizer.recognize(bitmap)
        recognizer.recognize(bitmap)

        assertEquals(1, created)
        coVerify(exactly = 3) { trainedData.ensureEnglish() }
    }

    @Test
    fun `shutdown ends the engine and frees it for the next call`() = runTest {
        val engine = mockk<TextRecognizer.TessEngine>(relaxed = true) {
            every { getUtf8Text() } returns "x"
        }
        var created = 0
        val factory = TextRecognizer.TessEngineFactory { _, _ -> created++; engine }
        val trainedData = mockk<TrainedDataManager> {
            coEvery { ensureEnglish() } returns File("/tmp/fake")
        }

        val recognizer = TextRecognizer(trainedData, factory)
        recognizer.recognize(bitmap)
        recognizer.shutdown()
        recognizer.recognize(bitmap)

        verify { engine.end() }
        assertEquals(2, created)
    }
}
