package sh.haven.core.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServedFileTrackerTest {

    @Test
    fun `register adds an entry to active flow`() {
        val tracker = ServedFileTracker()
        tracker.register("local", "/sdcard/foo.png")

        val snapshot = tracker.active.value
        assertEquals(1, snapshot.size)
        val entry = snapshot.single()
        assertEquals("local", entry.profileId)
        assertEquals("/sdcard/foo.png", entry.path)
        assertTrue(tracker.isServed("local", "/sdcard/foo.png"))
    }

    @Test
    fun `re-register replaces rather than duplicates`() {
        val tracker = ServedFileTracker()
        tracker.register("local", "/foo")
        val firstExpiry = tracker.active.value.single().expiresAtMillis
        Thread.sleep(5)
        tracker.register("local", "/foo")
        val snapshot = tracker.active.value
        assertEquals(1, snapshot.size)
        val refreshed = snapshot.single().expiresAtMillis
        assertTrue("expiry should refresh on re-register", refreshed >= firstExpiry)
    }

    @Test
    fun `unregister removes the entry`() {
        val tracker = ServedFileTracker()
        tracker.register("local", "/foo")
        tracker.unregister("local", "/foo")
        assertEquals(0, tracker.active.value.size)
        assertFalse(tracker.isServed("local", "/foo"))
    }

    @Test
    fun `different profileId on same path is distinct`() {
        val tracker = ServedFileTracker()
        tracker.register("profile-a", "/foo")
        tracker.register("profile-b", "/foo")
        assertEquals(2, tracker.active.value.size)
        assertTrue(tracker.isServed("profile-a", "/foo"))
        assertTrue(tracker.isServed("profile-b", "/foo"))
    }
}
