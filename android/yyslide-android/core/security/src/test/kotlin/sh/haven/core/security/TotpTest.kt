package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates [Totp] against the RFC 6238 Appendix-B reference vectors.
 * The seeds are the RFC's ASCII strings, base32-encoded here (TOTP
 * secrets are conventionally base32) via a local encoder that is itself
 * sanity-checked against a known constant.
 */
class TotpTest {

    // RFC 6238 Appendix-B seeds (ASCII), per algorithm.
    private val seedSha1 = "12345678901234567890".toByteArray()
    private val seedSha256 = "12345678901234567890123456789012".toByteArray()
    private val seedSha512 = "1234567890123456789012345678901234567890123456789012345678901234".toByteArray()

    private val secretSha1 = base32Encode(seedSha1)
    private val secretSha256 = base32Encode(seedSha256)
    private val secretSha512 = base32Encode(seedSha512)

    @Test
    fun base32Encoder_matchesKnownConstant() {
        // Sanity-check the test's own encoder before relying on it.
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secretSha1)
    }

    @Test
    fun rfc6238_sha1_vectors() {
        assertEquals("94287082", code(secretSha1, 59L, Totp.Algorithm.SHA1))
        assertEquals("07081804", code(secretSha1, 1111111109L, Totp.Algorithm.SHA1))
        assertEquals("14050471", code(secretSha1, 1111111111L, Totp.Algorithm.SHA1))
        assertEquals("89005924", code(secretSha1, 1234567890L, Totp.Algorithm.SHA1))
        assertEquals("69279037", code(secretSha1, 2000000000L, Totp.Algorithm.SHA1))
        assertEquals("65353130", code(secretSha1, 20000000000L, Totp.Algorithm.SHA1))
    }

    @Test
    fun rfc6238_sha256_vectors() {
        assertEquals("46119246", code(secretSha256, 59L, Totp.Algorithm.SHA256))
        assertEquals("68084774", code(secretSha256, 1111111109L, Totp.Algorithm.SHA256))
        assertEquals("91819424", code(secretSha256, 1234567890L, Totp.Algorithm.SHA256))
    }

    @Test
    fun rfc6238_sha512_vectors() {
        assertEquals("90693936", code(secretSha512, 59L, Totp.Algorithm.SHA512))
        assertEquals("25091201", code(secretSha512, 1111111109L, Totp.Algorithm.SHA512))
        assertEquals("93441116", code(secretSha512, 1234567890L, Totp.Algorithm.SHA512))
    }

    @Test
    fun defaultSixDigitCode_isZeroPaddedToWidth() {
        val c = Totp.generate(secretSha1, atMillis = 1234567890L * 1000L)
        assertEquals(6, c.length)
        assertTrue(c.all { it.isDigit() })
    }

    @Test
    fun tolerates_lowercase_spaces_and_padding() {
        val canonical = Totp.generate(secretSha1, atMillis = 59_000L)
        val messy = Totp.generate(
            secretSha1.lowercase().chunked(4).joinToString(" ") + "===",
            atMillis = 59_000L,
        )
        assertEquals(canonical, messy)
    }

    @Test
    fun isValidSecret_rejectsGarbage() {
        assertTrue(Totp.isValidSecret(secretSha1))
        assertFalse(Totp.isValidSecret("not base 32 !!!"))
        assertFalse(Totp.isValidSecret(""))
    }

    @Test
    fun secondsRemaining_isWithinPeriod() {
        assertEquals(1, Totp.secondsRemaining(atMillis = 59_000L, periodSeconds = 30))
        assertEquals(30, Totp.secondsRemaining(atMillis = 30_000L, periodSeconds = 30))
    }

    private fun code(secret: String, timeSeconds: Long, algo: Totp.Algorithm): String =
        Totp.generate(secret, atMillis = timeSeconds * 1000L, algorithm = algo, digits = 8, periodSeconds = 30)

    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(alphabet[(buffer ushr bitsLeft) and 0x1f])
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1f])
        }
        return sb.toString()
    }
}
