package sh.haven.core.spa

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds an fwknop SPA packet (AES-256-CBC + HMAC-SHA256, the fwknop defaults).
 *
 * The wire format is reproduced from libfko (`fko_encode.c`, `cipher_funcs.c`,
 * `fko_funcs.c`) and is interoperable with a stock `fwknopd`:
 *
 * 1. Plaintext, colon-joined (base64 fields have `=` stripped):
 *    `{rand16}:b64(user):{timestamp}:{version}:{msgType}:b64(accessMsg)`
 * 2. Append the SHA-256 message digest: `plaintext = encoded + ":" + b64(SHA256(encoded))`
 * 3. AES-256-CBC encrypt with an OpenSSL `Salted__` header. Key+IV derive from the
 *    key bytes and an 8-byte random salt via EVP_BytesToKey (MD5, 1 iteration).
 *    `raw = "Salted__" + salt + ciphertext`; `b64ct = base64(raw)` with `=` stripped.
 * 4. HMAC-SHA256 over the **full** `b64ct` (including the constant `U2FsdGVkX1`
 *    prefix); base64 it (`=` stripped) and append directly, no separator.
 * 5. Transmit `b64ct` with the constant 10-char salt prefix removed, then the HMAC:
 *    `packet = b64ct.substring(10) + hmac`.
 *
 * Everything is deterministic given [now], [salt] and [rand], so the builder is
 * unit-testable against fwknop reference vectors.
 */
object FwknopPacket {

    /** FKO protocol version advertised in the message (fwknopd is lenient; ≤ 8 chars). */
    const val VERSION = "3.0.0"

    /** `FKO_ACCESS_MSG` — "open these ports". */
    const val MSG_TYPE_ACCESS = 1

    /** Client username field; fwknopd does not check it in AES mode. */
    const val DEFAULT_USER = "haven"

    /** `FKO_RAND_VAL_SIZE` — the leading random field is 16 decimal digits. */
    private const val RAND_DIGITS = 16

    private const val SALT_LEN = 8
    private const val AES_KEY_LEN = 32
    private const val AES_IV_LEN = 16

    /** `B64_RIJNDAEL_SALT` — base64("Salted__"…) always starts with these 10 chars. */
    private const val B64_SALT_PREFIX = "U2FsdGVkX1"

    private val rng = SecureRandom()

    /**
     * Build the ASCII SPA packet for [config]. [resolvedIp] supplies the egress IP
     * for [SpaConfig.AllowMode.RESOLVE]. [now], [salt] and [rand] are injectable
     * for testing; production callers omit them.
     */
    fun build(
        config: SpaConfig,
        resolvedIp: String? = null,
        username: String = DEFAULT_USER,
        now: Long = System.currentTimeMillis(),
        salt: ByteArray = randomSalt(),
        rand: String = randomRand(),
    ): String {
        val accessMsg = config.accessMessage(resolvedIp)
        val plaintext = buildPlaintext(rand, username, now / 1000, accessMsg)
        val b64ct = encrypt(plaintext.toByteArray(Charsets.US_ASCII), config.keyBytes, salt)
        val hmac = config.hmacKeyBytes?.let { hmacSha256(b64ct, it) } ?: ""
        check(b64ct.startsWith(B64_SALT_PREFIX)) { "unexpected ciphertext encoding" }
        return b64ct.substring(B64_SALT_PREFIX.length) + hmac
    }

    /** The colon-joined message with its trailing SHA-256 digest appended. */
    internal fun buildPlaintext(rand: String, username: String, epochSeconds: Long, accessMsg: String): String {
        val encoded = buildString {
            append(rand); append(':')
            append(b64(username.toByteArray(Charsets.US_ASCII))); append(':')
            append(epochSeconds); append(':')
            append(VERSION); append(':')
            append(MSG_TYPE_ACCESS); append(':')
            append(b64(accessMsg.toByteArray(Charsets.US_ASCII)))
        }
        val digest = b64(sha256(encoded.toByteArray(Charsets.US_ASCII)))
        return "$encoded:$digest"
    }

    /** AES-256-CBC encrypt with OpenSSL Salted__ framing; returns base64 (`=` stripped). */
    internal fun encrypt(plaintext: ByteArray, keyBytes: ByteArray, salt: ByteArray): String {
        val (key, iv) = evpBytesToKey(keyBytes, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext)
        val raw = ByteArray(SALT_LEN + SALT_LEN + ct.size)
        SALTED_MAGIC.copyInto(raw, 0)
        salt.copyInto(raw, SALT_LEN)
        ct.copyInto(raw, SALT_LEN + SALT_LEN)
        return b64(raw)
    }

    /**
     * OpenSSL EVP_BytesToKey, MD5, count = 1 — matches libfko `rij_salt_and_iv`.
     * `D1 = MD5(pw‖salt)`, `Dn = MD5(D(n-1)‖pw‖salt)`; key = first 32 bytes, IV = next 16.
     */
    internal fun evpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val out = ByteArray(AES_KEY_LEN + AES_IV_LEN)
        var generated = 0
        var prev = ByteArray(0)
        while (generated < out.size) {
            md5.reset()
            md5.update(prev)
            md5.update(password)
            md5.update(salt)
            prev = md5.digest()
            val n = minOf(prev.size, out.size - generated)
            prev.copyInto(out, generated, 0, n)
            generated += n
        }
        return out.copyOfRange(0, AES_KEY_LEN) to out.copyOfRange(AES_KEY_LEN, AES_KEY_LEN + AES_IV_LEN)
    }

    private fun hmacSha256(b64ct: String, hmacKey: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return b64(mac.doFinal(b64ct.toByteArray(Charsets.US_ASCII)))
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** Standard base64 with trailing `=` stripped, matching libfko's `strip_b64_eq`. */
    private fun b64(data: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(data)

    private fun randomSalt(): ByteArray = ByteArray(SALT_LEN).also { rng.nextBytes(it) }

    private fun randomRand(): String = buildString(RAND_DIGITS) {
        repeat(RAND_DIGITS) { append(rng.nextInt(10)) }
    }

    private val SALTED_MAGIC = "Salted__".toByteArray(Charsets.US_ASCII)
}
