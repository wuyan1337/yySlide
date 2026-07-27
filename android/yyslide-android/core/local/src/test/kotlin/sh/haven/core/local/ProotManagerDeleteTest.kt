package sh.haven.core.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files

/**
 * Regression coverage for #174: a broken Arch rootfs can't be recovered
 * because `ca-certificates`/`update-ca-trust` leaves
 * `etc/ca-certificates/extracted/cadir/` mode 0555, and plain
 * [File.deleteRecursively] can't unlink files inside a read-only dir.
 */
class ProotManagerDeleteTest {

    private fun buildTrappedTree(): File {
        assumeTrue(
            "read-only directory deletion semantics require a POSIX file system",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val root = Files.createTempDirectory("proot-del-test").toFile()
        val cadir = File(root, "etc/ca-certificates/extracted/cadir").apply { mkdirs() }
        File(cadir, "AffirmTrust_Commercial.pem").writeText("dummy cert")
        // Mirror the on-device state: the parent dir is read-only, so its
        // entries can't be unlinked without first restoring the write bit.
        assertTrue("test setup: clear write bit on cadir", cadir.setWritable(false, false))
        return root
    }

    @Test
    fun `forceDeleteRecursively removes a tree containing a read-only 0555 directory`() {
        val root = buildTrappedTree()

        val removed = forceDeleteRecursively(root)

        assertTrue("forceDeleteRecursively should report success", removed)
        assertFalse("tree should be gone", root.exists())
    }

    @Test
    fun `plain deleteRecursively fails on the same trapped tree (documents the bug)`() {
        val root = buildTrappedTree()

        val removed = root.deleteRecursively()

        assertFalse("stdlib deleteRecursively cannot clear a read-only dir", removed)
        assertTrue("the trapped tree is left behind", root.exists())

        // Restore perms so the temp dir can be cleaned up by the OS later.
        forceDeleteRecursively(root)
    }

    @Test
    fun `forceDeleteRecursively is a no-op on a missing path`() {
        val missing = File(Files.createTempDirectory("proot-del-missing").toFile(), "nope")
        assertTrue(forceDeleteRecursively(missing))
    }
}
