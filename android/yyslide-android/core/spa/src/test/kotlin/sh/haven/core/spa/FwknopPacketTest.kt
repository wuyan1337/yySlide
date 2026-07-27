package sh.haven.core.spa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Validates [FwknopPacket] against a reference vector produced by the real
 * fwknop client (`fwknop 2.6.11`, FKO protocol 3.0.0):
 *
 * ```
 * fwknop -A tcp/22 -a 0.0.0.0 -D 127.0.0.1 \
 *   --key-base64-rijndael VGyQKiiQC25MzkbuIZvrgQ== \
 *   --key-base64-hmac /q0iwIDKH0RsMmFXKwcvKp5Cq4bGOmfeBc/+pYZtDDQ= \
 *   --use-hmac --test -v -v
 * ```
 */
class FwknopPacketTest {

    private val keyB64 = "VGyQKiiQC25MzkbuIZvrgQ=="
    private val hmacKeyB64 = "/q0iwIDKH0RsMmFXKwcvKp5Cq4bGOmfeBc/+pYZtDDQ="

    // Fields fwknop printed for the captured packet.
    private val rand = "8003520420295423"
    private val username = "ian"
    private val timestamp = 1779533765L
    private val encodedData = "8003520420295423:aWFu:1779533765:3.0.0:1:MC4wLjAuMCx0Y3AvMjI"
    private val digest = "qnW9Lq9rdUVuK1uKSwgIp0jvaSaB4G+Vh5svqH5rZ8o"
    private val refHmac = "bb4coANLBDIaAbQWtQTzBndZdrORdozMoffnXvki5Io"

    // The transmitted ciphertext (salt prefix "U2FsdGVkX1" already stripped).
    private val ctPart =
        "/JzAKGM8AnoIUZd+WyEYrqzqAHeFx/Fl6M+49wg0eh+GOsNA7rv2bPNvVslWYWd4pkugOyweoB6tZeZ" +
            "7DzkrisXWFXT9rCrdylAFtGojgdAXy1ULr0XTfSWP2nsvFNZnp9I7kBgC/b8ZSNqngKZknCLG2Yoiig" +
            "PsY"

    private val saltPrefix = "U2FsdGVkX1"

    private fun config() = SpaConfig(
        key = keyB64,
        keyIsBase64 = true,
        hmacKey = hmacKeyB64,
        hmacKeyIsBase64 = true,
        accessSpec = "tcp/22",
        allowMode = SpaConfig.AllowMode.SOURCE,
        spaPort = SpaConfig.DEFAULT_SPA_PORT,
    )

    /** Lenient base64 decode that tolerates fwknop's stripped padding. */
    private fun b64decode(s: String): ByteArray {
        val pad = (4 - s.length % 4) % 4
        return Base64.getDecoder().decode(s + "=".repeat(pad))
    }

    @Test
    fun `encoded message and digest match fwknop`() {
        val plaintext = FwknopPacket.buildPlaintext(rand, username, timestamp, "0.0.0.0,tcp/22")
        assertEquals("$encodedData:$digest", plaintext)
    }

    @Test
    fun `byte-exact reproduction of a real fwknop packet`() {
        // Recover the random salt fwknop used, then feed it back to the builder.
        val fullB64ct = saltPrefix + ctPart
        val raw = b64decode(fullB64ct)
        val salt = raw.copyOfRange(8, 16) // "Salted__"(8) | salt(8) | ciphertext

        val packet = FwknopPacket.build(
            config(),
            username = username,
            now = timestamp * 1000,
            salt = salt,
            rand = rand,
        )
        assertEquals(ctPart + refHmac, packet)
    }

    @Test
    fun `decrypting the fwknop packet recovers the plaintext`() {
        val raw = b64decode(saltPrefix + ctPart)
        val salt = raw.copyOfRange(8, 16)
        val cipherBytes = raw.copyOfRange(16, raw.size)
        val (key, iv) = FwknopPacket.evpBytesToKey(b64decode(keyB64), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val recovered = String(cipher.doFinal(cipherBytes), Charsets.US_ASCII)
        assertEquals("$encodedData:$digest", recovered)
    }

    @Test
    fun `self round-trip decrypts to the original plaintext`() {
        val cfg = config()
        val salt = ByteArray(8) { (it + 1).toByte() }
        val packet = FwknopPacket.build(cfg, username = username, now = timestamp * 1000, salt = salt, rand = rand)

        // Strip the HMAC (last 43 chars of base64 SHA-256), re-add salt prefix, decrypt.
        val ctOnly = packet.dropLast(refHmac.length)
        val raw = b64decode(saltPrefix + ctOnly)
        val cipherBytes = raw.copyOfRange(16, raw.size)
        val (key, iv) = FwknopPacket.evpBytesToKey(b64decode(keyB64), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val recovered = String(cipher.doFinal(cipherBytes), Charsets.US_ASCII)
        assertEquals("$encodedData:$digest", recovered)
    }

    @Test
    fun `no-HMAC mode omits the trailing mac`() {
        val cfg = config().copy(hmacKey = null)
        val salt = ByteArray(8) { 7 }
        val withHmac = FwknopPacket.build(config(), now = timestamp * 1000, salt = salt, rand = rand)
        val noHmac = FwknopPacket.build(cfg, now = timestamp * 1000, salt = salt, rand = rand)
        // The HMAC variant is exactly 43 base64 chars longer (SHA-256 digest, stripped).
        assertEquals(43, withHmac.length - noHmac.length)
        assertTrue(withHmac.startsWith(noHmac))
    }

    @Test
    fun `generated random field is 16 decimal digits`() {
        val cfg = config()
        repeat(50) {
            val packet = FwknopPacket.build(cfg)
            // Decrypt to inspect the rand field is unnecessary; assert the builder's own rand.
            assertTrue(packet.isNotEmpty())
        }
        // Inspect the rand directly via a fixed-salt build + decrypt.
        val salt = ByteArray(8) { 3 }
        val packet = FwknopPacket.build(cfg, now = timestamp * 1000, salt = salt)
        val ctOnly = packet.dropLast(refHmac.length)
        val raw = b64decode(saltPrefix + ctOnly)
        val (key, iv) = FwknopPacket.evpBytesToKey(b64decode(keyB64), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val recovered = String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.US_ASCII)
        val randField = recovered.substringBefore(':')
        assertEquals(16, randField.length)
        assertTrue(randField.all { it.isDigit() })
    }

    @Test
    fun `evpBytesToKey produces 32-byte key and 16-byte iv`() {
        val (key, iv) = FwknopPacket.evpBytesToKey(b64decode(keyB64), ByteArray(8))
        assertEquals(32, key.size)
        assertEquals(16, iv.size)
    }

    @Test
    fun `source mode embeds 0_0_0_0 as the allow ip`() {
        assertEquals("0.0.0.0,tcp/22", config().accessMessage())
        assertFalse(config().accessMessage().contains("U2FsdGVkX1"))
    }
}
