package sh.haven.core.stepca

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Computes and verifies SHA-256 fingerprints over the DER encoding of an
 * X.509 certificate, matching the format that `step certificate fingerprint`
 * and `step ca bootstrap` use. Users typically paste this value from a
 * trusted side-channel (chat, password manager, MDM profile) so the
 * downloaded `/roots.pem` can be verified before TLS pinning kicks in.
 *
 * Accepts the user's input in either of two shapes:
 *  - lower- or upper-case hex with optional `:` separators (`AA:BB:CC...`),
 *    the form `step certificate fingerprint` prints.
 *  - bare hex (`aabbcc...`), the form many tools log.
 *
 * Empty input or any non-hex character → throws `IllegalArgumentException`.
 */
internal object CaFingerprint {

    private val HEX_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    /**
     * SHA-256(certificate DER) as a 64-char lower-case hex string with
     * no separators. Strips the `:` colons from `step certificate
     * fingerprint` output during the round-trip.
     */
    fun sha256OfDer(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Same as [sha256OfDer] but takes the DER bytes directly — handy for tests. */
    fun sha256OfDer(derBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(derBytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify that [pem] (a single X.509 certificate in PEM form, as
     * returned by step-ca's `/roots.pem`) has the SHA-256 fingerprint
     * encoded by [userFingerprint]. On mismatch, throws with both
     * expected and actual values so the UI can surface the diff.
     */
    fun verifyPem(pem: String, userFingerprint: String) {
        val expected = normaliseHex(userFingerprint)
        val cert = parsePem(pem)
        val actual = sha256OfDer(cert)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw FingerprintMismatch(expected = expected, actual = actual)
        }
    }

    /**
     * Parse a single PEM block. step-ca's `/roots.pem` returns exactly
     * one root cert; if a chain ever appears the factory returns the
     * first.
     */
    fun parsePem(pem: String): X509Certificate {
        val cleaned = pem.trim()
        require(cleaned.startsWith("-----BEGIN CERTIFICATE-----")) {
            "Expected PEM-encoded certificate beginning with -----BEGIN CERTIFICATE-----"
        }
        val cf = CertificateFactory.getInstance("X.509")
        return cleaned.byteInputStream(Charsets.US_ASCII).use { input ->
            cf.generateCertificate(input) as X509Certificate
        }
    }

    private fun normaliseHex(raw: String): String {
        val stripped = raw.trim().replace(":", "").replace(" ", "").lowercase()
        require(HEX_PATTERN.matches(stripped)) {
            "Fingerprint must be 64 hex characters (SHA-256). Got: $raw"
        }
        return stripped
    }

    class FingerprintMismatch(val expected: String, val actual: String) :
        IllegalStateException("Root cert fingerprint mismatch. Expected $expected, got $actual.")
}
