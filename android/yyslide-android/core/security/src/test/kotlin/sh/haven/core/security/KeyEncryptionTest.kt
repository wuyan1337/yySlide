package sh.haven.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyEncryptionTest {

    // ---- isEncrypted() ----

    @Test
    fun `isEncrypted returns false for PEM key starting with dash`() {
        // PEM headers start with '-' (0x2D), e.g. "-----BEGIN OPENSSH PRIVATE KEY-----"
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n...".toByteArray()
        assertFalse(KeyEncryption.isEncrypted(pem))
    }

    @Test
    fun `isEncrypted returns false for DER key starting with 0x30`() {
        // DER keys start with 0x30 (ASN.1 SEQUENCE tag)
        val der = byteArrayOf(0x30, 0x82.toByte(), 0x00, 0x00)
        assertFalse(KeyEncryption.isEncrypted(der))
    }

    @Test
    fun `isEncrypted returns true for Tink ciphertext starting with 0x01`() {
        // Tink AEAD ciphertext starts with version byte 0x01 followed by a 4-byte key ID
        val tinkCiphertext = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x42, 0x99.toByte(), 0xAB.toByte())
        assertTrue(KeyEncryption.isEncrypted(tinkCiphertext))
    }

    @Test
    fun `isEncrypted returns true for bytes that are not PEM or DER`() {
        // Any first byte that is neither '-' (0x2D) nor 0x30 is treated as encrypted
        val arbitrary = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertTrue(KeyEncryption.isEncrypted(arbitrary))
    }

    @Test
    fun `isEncrypted returns false for empty array`() {
        assertFalse(KeyEncryption.isEncrypted(byteArrayOf()))
    }

    @Test
    fun `isEncrypted returns false for single dash byte`() {
        val singleDash = byteArrayOf('-'.code.toByte())
        assertFalse(KeyEncryption.isEncrypted(singleDash))
    }

    @Test
    fun `isEncrypted returns false for single DER sequence byte`() {
        val singleDer = byteArrayOf(0x30)
        assertFalse(KeyEncryption.isEncrypted(singleDer))
    }
}
