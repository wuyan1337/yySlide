package sh.haven.feature.sftp.transport

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

/**
 * Covers the streaming primitive on [LocalFileBackend.openInputStream]
 * plus the single-entry [LocalFileBackend.stat] used by the MCP
 * `serve_file` tool. Pure-JVM — no Android Environment lookup needed.
 */
class LocalFileBackendStreamingTest {

    private lateinit var backend: LocalFileBackend
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("haven-localfb-stream-").toFile()
        backend = LocalFileBackend(mockk<Context>(relaxed = true))
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun `openInputStream reads the full file at offset 0`() = runTest {
        val payload = ByteArray(8192) { (it and 0xFF).toByte() }
        val f = File(tempRoot, "blob.bin").apply { writeBytes(payload) }

        val readBack = backend.openInputStream(f.absolutePath, 0).use { it.readBytes() }
        assertArrayEquals(payload, readBack)
    }

    @Test
    fun `openInputStream honours non-zero offset`() = runTest {
        val payload = ByteArray(1024) { (it and 0xFF).toByte() }
        val f = File(tempRoot, "blob.bin").apply { writeBytes(payload) }

        val readBack = backend.openInputStream(f.absolutePath, 256).use { it.readBytes() }
        assertEquals(768, readBack.size)
        // The byte at index 0 of the read should be the byte at index 256 of the source.
        assertArrayEquals(payload.copyOfRange(256, 1024), readBack)
    }

    @Test
    fun `stat returns size for a file`() = runTest {
        val payload = ByteArray(4242) { (it and 0xFF).toByte() }
        val f = File(tempRoot, "blob.bin").apply { writeBytes(payload) }

        val entry = backend.stat(f.absolutePath)
        assertEquals(4242L, entry.size)
        assertEquals(false, entry.isDirectory)
        assertEquals("blob.bin", entry.name)
        assertEquals(f.absolutePath, entry.path)
    }

    @Test
    fun `stat marks directories with isDirectory true and size zero`() = runTest {
        val d = File(tempRoot, "subdir").apply { mkdir() }
        val entry = backend.stat(d.absolutePath)
        assertEquals(true, entry.isDirectory)
        assertEquals(0L, entry.size)
    }

    @Test
    fun `stat throws FileNotFoundException for a missing path`() = runTest {
        val missing = File(tempRoot, "does-not-exist").absolutePath
        assertThrows(FileNotFoundException::class.java) {
            runBlockingForTest { backend.stat(missing) }
        }
    }

    /** Small helper since assertThrows is a Java thing that doesn't await suspend. */
    private fun runBlockingForTest(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
