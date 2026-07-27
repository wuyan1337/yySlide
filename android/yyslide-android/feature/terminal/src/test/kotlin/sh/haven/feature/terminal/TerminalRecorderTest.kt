package sh.haven.feature.terminal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TerminalRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `recorder writes framed data`() {
        val file = tempFolder.newFile("test.bin")
        val recorder = TerminalRecorder(file)

        val data1 = "hello".toByteArray()
        val data2 = "world".toByteArray()
        recorder.record(data1, 0, data1.size)
        Thread.sleep(10)
        recorder.record(data2, 0, data2.size)
        recorder.close()

        // Read back and verify framing
        val frames = readFrames(file)
        assertEquals(2, frames.size)
        assertEquals("hello", String(frames[0].second))
        assertEquals("world", String(frames[1].second))
        assertTrue("Second frame should have later timestamp", frames[1].first >= frames[0].first)
    }

    @Test
    fun `recorder handles offset and partial length`() {
        val file = tempFolder.newFile("test.bin")
        val recorder = TerminalRecorder(file)

        val data = "XXhelloXX".toByteArray()
        recorder.record(data, 2, 5) // "hello"
        recorder.close()

        val frames = readFrames(file)
        assertEquals(1, frames.size)
        assertEquals("hello", String(frames[0].second))
    }

    companion object {
        /** Read recording frames: List of (timestampMs, data). */
        fun readFrames(file: File): List<Pair<Int, ByteArray>> {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val frames = mutableListOf<Pair<Int, ByteArray>>()
            while (buf.remaining() >= 8) {
                val timestamp = buf.int
                val length = buf.int
                if (buf.remaining() < length) break
                val data = ByteArray(length)
                buf.get(data)
                frames.add(timestamp to data)
            }
            return frames
        }
    }
}
