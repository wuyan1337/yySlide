package sh.haven.core.security

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time password generator (RFC 6238, built on the HOTP
 * truncation of RFC 4226). Pure JVM — uses [javax.crypto.Mac] for the
 * HMAC and a hand-rolled RFC 4648 base32 decode, so it adds no
 * dependency and is unit-testable off-device against the published
 * RFC 6238 Appendix-B test vectors.
 *
 * The only consumer is the SSH keyboard-interactive auto-fill path
 * (#178): when a profile's auth chain carries a TOTP secret, the live
 * code is generated at the moment the server issues the "Verification
 * code:" prompt and fed back to JSch.
 */
object Totp {

    /** Hash backing the HMAC. The vast majority of authenticators use [SHA1]. */
    enum class Algorithm(internal val macName: String) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512"),
        ;

        companion object {
            /**
             * Parse an `otpauth://` `algorithm` value (case-insensitive),
             * defaulting to [SHA1] for null / blank / unrecognised input —
             * matching the otpauth de-facto default.
             */
            fun fromLabel(label: String?): Algorithm = when (label?.trim()?.uppercase()) {
                "SHA256" -> SHA256
                "SHA512" -> SHA512
                else -> SHA1
            }
        }
    }

    /**
     * Generate the TOTP code for [secretBase32] at [atMillis].
     *
     * @param secretBase32 the shared secret, RFC 4648 base32 (padding and
     *   ASCII whitespace tolerated, case-insensitive).
     * @param digits code length (otpauth default 6; 8 also common).
     * @param periodSeconds time step (otpauth default 30).
     * @throws IllegalArgumentException if the secret isn't valid base32 or
     *   [digits] is out of the supported 1..9 range.
     */
    fun generate(
        secretBase32: String,
        atMillis: Long = System.currentTimeMillis(),
        algorithm: Algorithm = Algorithm.SHA1,
        digits: Int = 6,
        periodSeconds: Int = 30,
    ): String {
        require(digits in 1..9) { "digits must be 1..9, was $digits" }
        require(periodSeconds > 0) { "periodSeconds must be positive, was $periodSeconds" }
        val key = decodeBase32(secretBase32)
        require(key.isNotEmpty()) { "TOTP secret is empty" }

        val counter = (atMillis / 1000L) / periodSeconds
        val counterBytes = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (c and 0xff).toByte()
            c = c ushr 8
        }

        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        val hash = mac.doFinal(counterBytes)

        // RFC 4226 §5.3 dynamic truncation.
        val offset = (hash[hash.size - 1].toInt() and 0x0f)
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)

        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** Seconds remaining in the current [periodSeconds] window at [atMillis]. */
    fun secondsRemaining(
        atMillis: Long = System.currentTimeMillis(),
        periodSeconds: Int = 30,
    ): Int {
        val intoStep = (atMillis / 1000L) % periodSeconds
        return (periodSeconds - intoStep).toInt()
    }

    /**
     * Validate that [secretBase32] decodes to non-empty key material —
     * used to reject bad pastes before persisting. Never throws.
     */
    fun isValidSecret(secretBase32: String): Boolean = try {
        decodeBase32(secretBase32).isNotEmpty()
    } catch (_: IllegalArgumentException) {
        false
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32 decode. Tolerates whitespace, lowercase, and `=` padding. */
    private fun decodeBase32(input: String): ByteArray {
        val cleaned = input.trim()
            .replace(" ", "")
            .replace("-", "")
            .trimEnd('=')
            .uppercase()
        if (cleaned.isEmpty()) return ByteArray(0)

        val out = ArrayList<Byte>(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in cleaned) {
            val value = BASE32_ALPHABET.indexOf(ch)
            require(value >= 0) { "invalid base32 character: '$ch'" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}
