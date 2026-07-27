package sh.haven.core.stepca

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests that exercise the public-key extraction logic without
 * needing a live step-ca instance. The HTTP path is covered by the
 * end-to-end device test (#133 phase 2 verification step).
 */
class StepCaApiClientTest {

    private val client = StepCaApiClient()

    private fun extract(input: String): String? = invokeExtract(client, input)

    @Test
    fun `accepts ed25519 public key with comment`() {
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIabcdef alice@laptop"
        assertEquals("AAAAC3NzaC1lZDI1NTE5AAAAIabcdef", extract(key))
    }

    @Test
    fun `accepts ed25519 public key without comment`() {
        val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIabcdef"
        assertEquals("AAAAC3NzaC1lZDI1NTE5AAAAIabcdef", extract(key))
    }

    @Test
    fun `rejects single-token input`() {
        assertNull(extract("ssh-ed25519"))
    }

    @Test
    fun `rejects empty string`() {
        assertNull(extract(""))
    }

    @Test
    fun `tolerates extra whitespace`() {
        val key = "  ssh-ed25519   AAAAC3NzaC1lZDI1NTE5AAAAIabcdef   alice  "
        assertEquals("AAAAC3NzaC1lZDI1NTE5AAAAIabcdef", extract(key))
    }

    private fun invokeExtract(client: StepCaApiClient, openSsh: String): String? {
        // Reflectively reach the private helper rather than expose it
        // — the helper has no other public surface so this test is the
        // only legitimate caller.
        val m = StepCaApiClient::class.java
            .getDeclaredMethod("extractOpenSshPublicKeyBase64", String::class.java)
        m.isAccessible = true
        return m.invoke(client, openSsh) as String?
    }
}
