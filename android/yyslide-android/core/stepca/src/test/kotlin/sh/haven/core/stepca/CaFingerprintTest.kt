package sh.haven.core.stepca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaFingerprintTest {

    /**
     * SHA-256 over the DER bytes — independent reference computed via
     * `openssl x509 -in root.pem -outform DER | sha256sum`. The test
     * bytes are a 3-byte fixture (the digest math, not the cert parser,
     * is what we're verifying here).
     */
    @Test
    fun `sha256OfDer bytes matches openssl reference`() {
        // sha256("ABC") = b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78
        val digest = CaFingerprint.sha256OfDer("ABC".toByteArray(Charsets.US_ASCII))
        assertEquals("b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78", digest)
    }

    @Test
    fun `verifyPem accepts colon-separated user input`() {
        // Verify the parser computes the same digest openssl did
        // (`openssl x509 -in fixture.pem -outform DER | sha256sum`) and that
        // verifyPem accepts the hex in either colon-separated or bare form.
        val cert = CaFingerprint.parsePem(FIXTURE_PEM)
        val actual = CaFingerprint.sha256OfDer(cert)
        assertEquals(FIXTURE_SHA256, actual)

        val colon = actual.chunked(2).joinToString(":")
        CaFingerprint.verifyPem(FIXTURE_PEM, colon)
        CaFingerprint.verifyPem(FIXTURE_PEM, actual)
        CaFingerprint.verifyPem(FIXTURE_PEM, actual.uppercase())
    }

    @Test
    fun `verifyPem rejects mismatched fingerprint with both values surfaced`() {
        val wrong = "00".repeat(32)
        val ex = assertThrows(CaFingerprint.FingerprintMismatch::class.java) {
            CaFingerprint.verifyPem(FIXTURE_PEM, wrong)
        }
        assertEquals(wrong, ex.expected)
        val cert = CaFingerprint.parsePem(FIXTURE_PEM)
        assertEquals(CaFingerprint.sha256OfDer(cert), ex.actual)
    }

    @Test
    fun `verifyPem rejects garbage fingerprint`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaFingerprint.verifyPem(FIXTURE_PEM, "not-hex")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaFingerprint.verifyPem(FIXTURE_PEM, "abcd")
        }
    }

    @Test
    fun `parsePem rejects non-PEM input`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaFingerprint.parsePem("just some text")
        }
    }

    companion object {
        // Self-signed ed25519 root cert, generated with
        // `openssl req -new -x509 -key <ed25519.key> -subj /CN=haven-test-ca`.
        // SHA-256(DER) checked via `openssl x509 -in … -outform DER | sha256sum`.
        // Test-only — never trusted by any production system.
        private val FIXTURE_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBRDCB96ADAgECAhQGvXd8xeuL6UbBTXoxPRO9rANzhDAFBgMrZXAwGDEWMBQG
            A1UEAwwNaGF2ZW4tdGVzdC1jYTAeFw0yNjA1MTIyMzEyMDhaFw0zNjA1MDkyMzEy
            MDhaMBgxFjAUBgNVBAMMDWhhdmVuLXRlc3QtY2EwKjAFBgMrZXADIQARW5hoNXIm
            J5pcRLEMhUa55XhUwilcXboB5DBIKXAnMKNTMFEwHQYDVR0OBBYEFHe3nx5jhcii
            r9wbFx38ay+nOuSfMB8GA1UdIwQYMBaAFHe3nx5jhciir9wbFx38ay+nOuSfMA8G
            A1UdEwEB/wQFMAMBAf8wBQYDK2VwA0EA7V2/j83u2L0p0pWkBpqebpbzonTjDsUw
            j+nqjSA5gROOsTWXF4d68JuiVi5uR2WtsjDcPm29TGinSR4ymW9sAg==
            -----END CERTIFICATE-----
        """.trimIndent()

        private const val FIXTURE_SHA256 =
            "b61004a597d5635897ffef63cfdd57e5bb8ce83fe53ee82f4276a88e7f2fb755"
    }
}
