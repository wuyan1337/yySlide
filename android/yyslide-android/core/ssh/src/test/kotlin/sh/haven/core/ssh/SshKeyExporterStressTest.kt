package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.security.SecureRandom

class SshKeyExporterStressTest {

    /**
     * Regression: a previous PEM-detection heuristic compared only the
     * first byte to '-' (0x2d), so any random Ed25519 seed beginning
     * with 0x2d was returned raw and JSch rejected it with "invalid
     * privatekey." Hits ~1/256 of generated keys — flaked CI for two
     * releases. This seed was captured from the CI failure log.
     */
    @Test
    fun `seed starting with 0x2d still produces a valid PEM`() {
        val seed = "2d89ce623882114875f4a27853e36853b49d1e74b447ece4de32af7fcf7e2c43".hexToBytes()
        val pem = SshKeyExporter.toPem(seed, "ssh-ed25519")
        assertTrue(
            "PEM must carry the OpenSSH preamble, not the raw seed",
            pem.decodeToString().startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"),
        )
        val kp = KeyPair.load(JSch(), pem, null)
        assertNotNull(kp)
        kp.dispose()
    }

    /** Brute-force coverage: 200 random seeds, all must round-trip through JSch. */
    @Test
    fun `200 random seeds all produce JSch-loadable PEMs`() {
        val failures = mutableListOf<String>()
        repeat(200) { i ->
            val seed = generateEd25519Seed()
            val pem = SshKeyExporter.toPem(seed, "ssh-ed25519")
            try {
                val kp = KeyPair.load(JSch(), pem, null)
                kp.dispose()
            } catch (e: Exception) {
                failures += "iter $i seed=${seed.toHex()}: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            failures.take(5).forEach(::println)
            throw AssertionError("${failures.size}/200 seeds failed JSch round-trip")
        }
    }

    private fun generateEd25519Seed(): ByteArray {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val priv = gen.generateKeyPair().private as Ed25519PrivateKeyParameters
        val encoded = priv.encoded
        return if (encoded.size == 32) encoded
        else ByteArray(32).also { priv.encode(it, 0) }
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
