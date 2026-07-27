package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class OpenSshCertificateTest {

    @Test
    fun `parses synthetic ed25519 user cert with two principals`() {
        val blob = buildEd25519UserCert(
            keyId = "alice@phone",
            principals = listOf("alice", "alice@example.com"),
            validAfter = 1_700_000_000L,
            validBefore = 1_700_086_400L, // 24h later
            serial = 42L,
        )

        val cert = OpenSshCertificate.parse(blob)

        assertEquals("ssh-ed25519-cert-v01@openssh.com", cert.typeName)
        assertEquals(42L, cert.serial)
        assertEquals(OpenSshCertificate.USER_CERT_TYPE, cert.certType)
        assertEquals("alice@phone", cert.keyId)
        assertEquals(listOf("alice", "alice@example.com"), cert.validPrincipals)
        assertEquals(1_700_000_000L, cert.validAfter)
        assertEquals(1_700_086_400L, cert.validBefore)
        assertEquals(SYNTHETIC_NONCE.size, cert.nonce.size)
        assertEquals(SYNTHETIC_PUBKEY_BLOB.size + 4, cert.publicKey.size)
    }

    @Test
    fun `parses cert from OpenSSH text form`() {
        val blob = buildEd25519UserCert(
            keyId = "bob",
            principals = listOf("bob"),
            validAfter = 0L,
            validBefore = 1L,
        )
        val text = "ssh-ed25519-cert-v01@openssh.com " +
            Base64.getEncoder().encodeToString(blob) +
            " bob@laptop\n"
        val cert = OpenSshCertificate.parse(text.toByteArray(Charsets.US_ASCII))
        assertEquals("bob", cert.keyId)
        assertEquals(listOf("bob"), cert.validPrincipals)
    }

    @Test
    fun `parses cert from bare base64`() {
        val blob = buildEd25519UserCert(keyId = "x", principals = emptyList())
        val cert = OpenSshCertificate.parse(
            Base64.getEncoder().encodeToString(blob).toByteArray(Charsets.US_ASCII),
        )
        assertEquals("x", cert.keyId)
        assertTrue(cert.validPrincipals.isEmpty())
    }

    @Test
    fun `validity bounds are inclusive`() {
        val cert = OpenSshCertificate.parse(
            buildEd25519UserCert(
                keyId = "k",
                principals = emptyList(),
                validAfter = 100L,
                validBefore = 200L,
            ),
        )
        assertTrue(cert.isValidAt(100))
        assertTrue(cert.isValidAt(150))
        assertTrue(cert.isValidAt(200))
        assertFalse(cert.isValidAt(99))
        assertFalse(cert.isValidAt(201))
    }

    @Test
    fun `secondsUntilExpiry goes negative once expired`() {
        val cert = OpenSshCertificate.parse(
            buildEd25519UserCert(
                keyId = "k",
                principals = emptyList(),
                validAfter = 100L,
                validBefore = 200L,
            ),
        )
        assertEquals(50L, cert.secondsUntilExpiry(150))
        assertEquals(-50L, cert.secondsUntilExpiry(250))
    }

    @Test
    fun `uint64 max validBefore clamps to Long MAX_VALUE`() {
        val blob = buildEd25519UserCert(
            keyId = "noexp",
            principals = listOf("nobody"),
            validAfter = 0L,
            validBefore = -1L, // raw uint64::MAX in two's complement
        )
        val cert = OpenSshCertificate.parse(blob)
        assertEquals(Long.MAX_VALUE, cert.validBefore)
        assertTrue(cert.isValidAt(Long.MAX_VALUE / 2))
    }

    @Test
    fun `parseOrNull returns null for malformed input`() {
        assertNull(OpenSshCertificate.parseOrNull("not a cert".toByteArray()))
        assertNull(OpenSshCertificate.parseOrNull(byteArrayOf(0, 0, 0, 4, 1, 2, 3, 4)))
        assertNull(OpenSshCertificate.parseOrNull(byteArrayOf()))
    }

    @Test
    fun `parse throws on wrong type name`() {
        // Build a blob whose first wire-format string is "ssh-ed25519"
        // (a key, not a cert). parser must reject.
        val out = ByteArrayOutputStream()
        SshWire.writeString(out, "ssh-ed25519")
        try {
            OpenSshCertificate.parse(out.toByteArray())
            fail("Expected exception")
        } catch (_: IllegalArgumentException) {
            // expected
        } catch (_: IllegalStateException) {
            // also acceptable
        }
    }

    @Test
    fun `signatureKeyFingerprintSha256 is stable and matches OpenSSL`() {
        // sha256 of a one-byte 0x00 signatureKey is gOuMDPLD9G7e9OkrzU0…
        val blob = buildEd25519UserCert(
            keyId = "k",
            principals = emptyList(),
            signatureKey = byteArrayOf(0),
        )
        val cert = OpenSshCertificate.parse(blob)
        // Independently computed: echo -n -e '\x00' | openssl dgst -sha256 -binary
        //   | base64 | tr -d '='
        val expected = "bjQLnP+zepicpUTmu3gKLHiQHT+zNzh2hRGjBhevoB0"
        assertEquals(expected, cert.signatureKeyFingerprintSha256())
    }

    @Test
    fun `host cert type is preserved`() {
        val blob = buildEd25519UserCert(
            keyId = "h",
            principals = listOf("host.example.com"),
            certType = OpenSshCertificate.HOST_CERT_TYPE,
        )
        val cert = OpenSshCertificate.parse(blob)
        assertEquals(OpenSshCertificate.HOST_CERT_TYPE, cert.certType)
        assertEquals(listOf("host.example.com"), cert.validPrincipals)
    }

    @Test
    fun `parseOrNull is the safe variant of parse`() {
        val good = buildEd25519UserCert(keyId = "ok", principals = emptyList())
        assertNotNull(OpenSshCertificate.parseOrNull(good))
    }

    // ---- helpers ----

    /** Deterministic 32-byte nonce. Real certs use random; tests don't care. */
    private val SYNTHETIC_NONCE: ByteArray = ByteArray(32) { (it + 1).toByte() }

    /** Synthetic ed25519 public key (32 bytes), filled with 0xAA. */
    private val SYNTHETIC_PUBKEY_BLOB: ByteArray = ByteArray(32) { 0xAA.toByte() }

    /** Synthetic CA signature key wire blob: type "ssh-ed25519" + 32-byte key. */
    private val SYNTHETIC_CA_KEY_BLOB: ByteArray =
        ByteArrayOutputStream().also { o ->
            SshWire.writeString(o, "ssh-ed25519")
            SshWire.writeBytes(o, ByteArray(32) { 0xCC.toByte() })
        }.toByteArray()

    /** Synthetic ed25519 signature blob: type "ssh-ed25519" + 64-byte sig. */
    private val SYNTHETIC_SIG_BLOB: ByteArray =
        ByteArrayOutputStream().also { o ->
            SshWire.writeString(o, "ssh-ed25519")
            SshWire.writeBytes(o, ByteArray(64) { 0xDD.toByte() })
        }.toByteArray()

    /**
     * Build a synthetic ed25519 user/host cert blob. Uses defaults for
     * everything not overridden by the caller. The wire format matches
     * OpenSSH PROTOCOL.certkeys §2.1.1.
     */
    private fun buildEd25519UserCert(
        keyId: String,
        principals: List<String>,
        validAfter: Long = 0L,
        validBefore: Long = Long.MAX_VALUE,
        serial: Long = 1L,
        certType: Int = OpenSshCertificate.USER_CERT_TYPE,
        signatureKey: ByteArray = SYNTHETIC_CA_KEY_BLOB,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        SshWire.writeString(out, "ssh-ed25519-cert-v01@openssh.com")
        SshWire.writeBytes(out, SYNTHETIC_NONCE)
        // ed25519 pubkey is one wire-format string of the 32 bytes.
        SshWire.writeBytes(out, SYNTHETIC_PUBKEY_BLOB)
        SshWire.writeUint64(out, serial)
        SshWire.writeUint32(out, certType)
        SshWire.writeString(out, keyId)
        // principals: a wire-format list of strings, encoded as a string
        val principalsBlob = ByteArrayOutputStream().also { p ->
            principals.forEach { SshWire.writeString(p, it) }
        }.toByteArray()
        SshWire.writeBytes(out, principalsBlob)
        SshWire.writeUint64(out, validAfter)
        SshWire.writeUint64(out, validBefore)
        SshWire.writeBytes(out, byteArrayOf()) // critical options
        SshWire.writeBytes(out, byteArrayOf()) // extensions
        SshWire.writeBytes(out, byteArrayOf()) // reserved
        SshWire.writeBytes(out, signatureKey)
        SshWire.writeBytes(out, SYNTHETIC_SIG_BLOB)
        return out.toByteArray()
    }

    /** Minimal SSH wire-format writer for test fixtures. */
    private object SshWire {
        fun writeUint32(out: ByteArrayOutputStream, v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        fun writeUint64(out: ByteArrayOutputStream, v: Long) {
            for (i in 7 downTo 0) {
                out.write(((v ushr (i * 8)) and 0xFF).toInt())
            }
        }

        fun writeBytes(out: ByteArrayOutputStream, b: ByteArray) {
            writeUint32(out, b.size)
            out.write(b)
        }

        fun writeString(out: ByteArrayOutputStream, s: String) {
            writeBytes(out, s.toByteArray(Charsets.US_ASCII))
        }
    }
}
