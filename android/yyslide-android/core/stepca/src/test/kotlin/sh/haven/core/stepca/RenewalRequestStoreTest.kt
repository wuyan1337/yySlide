package sh.haven.core.stepca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RenewalRequestStoreTest {

    private fun store() = RenewalRequestStore(RuntimeEnvironment.getApplication())

    @Test
    fun `nonce is bound to key id and can be consumed once`() {
        val store = store()
        val nonce = store.issue("key-a", nowMillis = 1_000L)

        assertTrue(store.consume("key-a", nonce, nowMillis = 2_000L))
        assertFalse(store.consume("key-a", nonce, nowMillis = 2_001L))
    }

    @Test
    fun `wrong key id invalidates presented nonce`() {
        val store = store()
        val nonce = store.issue("key-b", nowMillis = 1_000L)

        assertFalse(store.consume("key-c", nonce, nowMillis = 2_000L))
        assertFalse(store.consume("key-b", nonce, nowMillis = 2_001L))
    }

    @Test
    fun `expired nonce is rejected`() {
        val store = store()
        val nonce = store.issue("key-d", nowMillis = 1_000L)

        assertFalse(
            store.consume(
                "key-d",
                nonce,
                nowMillis = 1_000L + RenewalRequestStore.MAX_AGE_MILLIS + 1,
            ),
        )
    }
}
