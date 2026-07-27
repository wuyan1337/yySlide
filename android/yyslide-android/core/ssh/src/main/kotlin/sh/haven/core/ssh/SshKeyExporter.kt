package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/**
 * Converts stored private key bytes to PEM format for export.
 *
 * Handles three storage formats:
 * - Already PEM/OpenSSH (imported keys): returned as-is
 * - PKCS#8 DER (generated RSA/ECDSA): wrapped via JSch writePrivateKey
 * - Raw 32-byte Ed25519 seed: encoded to OpenSSH format via JSch
 */
object SshKeyExporter {

    private val PEM_PREAMBLE = "-----BEGIN ".toByteArray()

    fun toPem(privateKeyBytes: ByteArray, keyType: String): ByteArray {
        // If it already looks like a PEM or OpenSSH private key, return
        // as-is. A previous check of "first byte == '-'" produced a
        // 1-in-256 false positive on random Ed25519 seeds (any seed
        // starting with byte 0x2d looked like a PEM and was returned
        // raw, then JSch rejected it with "invalid privatekey"). Match
        // the full "-----BEGIN " preamble instead.
        if (privateKeyBytes.size > PEM_PREAMBLE.size &&
            privateKeyBytes.sliceArray(PEM_PREAMBLE.indices).contentEquals(PEM_PREAMBLE)) {
            return privateKeyBytes
        }

        // Ed25519 key material → OpenSSH private key format
        // 64 bytes = prv_array (32) + pub_array (32) from JSch reflection
        // 32 bytes = raw seed (generated keys) — derive pub via BouncyCastle
        if (keyType == "ssh-ed25519" && privateKeyBytes.size == 64) {
            val prv = privateKeyBytes.copyOfRange(0, 32)
            val pub = privateKeyBytes.copyOfRange(32, 64)
            return encodeOpenSshEd25519(prv, pub)
        }
        if (keyType == "ssh-ed25519" && privateKeyBytes.size == 32) {
            return encodeOpenSshEd25519(privateKeyBytes)
        }

        // PKCS#8 DER (RSA/ECDSA from JCA) → PEM wrapper
        // DER starts with 0x30 (SEQUENCE tag)
        if (privateKeyBytes.isNotEmpty() && privateKeyBytes[0] == 0x30.toByte()) {
            return wrapPkcs8Pem(privateKeyBytes)
        }

        // Try JSch as last resort
        val jsch = JSch()
        val kpair = try {
            KeyPair.load(jsch, privateKeyBytes, null)
        } catch (_: Exception) {
            return privateKeyBytes
        }

        return try {
            val out = ByteArrayOutputStream()
            kpair.writePrivateKey(out)
            out.toByteArray()
        } catch (_: UnsupportedOperationException) {
            privateKeyBytes
        } finally {
            kpair.dispose()
        }
    }

    private fun wrapPkcs8Pem(der: ByteArray): ByteArray {
        val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(der)
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n".toByteArray()
    }

    /**
     * Encode Ed25519 key into OpenSSH private key format using an explicit public key.
     * Used when the public key is extracted from JSch (prv_array may be a clamped
     * scalar rather than the original seed, so BouncyCastle derivation would be wrong).
     */
    private fun encodeOpenSshEd25519(prvBytes: ByteArray, pubKey: ByteArray): ByteArray {
        return buildOpenSshEd25519(prvBytes, pubKey)
    }

    /**
     * Encode a raw Ed25519 32-byte private seed into OpenSSH private key format.
     * Derives the public key via BouncyCastle. Only correct when prvBytes is the
     * original seed (e.g. from key generation), not a clamped scalar.
     */
    private fun encodeOpenSshEd25519(seed: ByteArray): ByteArray {
        val privParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val pubKey = privParams.generatePublicKey().encoded
        return buildOpenSshEd25519(seed, pubKey)
    }

    private fun buildOpenSshEd25519(prvBytes: ByteArray, pubKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // OpenSSH private key format (see PROTOCOL.key in OpenSSH source)
        val authMagic = "openssh-key-v1\u0000".toByteArray()
        val cipherName = "none"
        val kdfName = "none"
        val kdfOptions = ByteArray(0)
        val numKeys = 1

        // Public key section
        val pubSection = ByteArrayOutputStream()
        writeString(pubSection, "ssh-ed25519")
        writeBytes(pubSection, pubKey)
        val pubSectionBytes = pubSection.toByteArray()

        // Private key section (unencrypted)
        val privSection = ByteArrayOutputStream()
        // checkint (random, must match)
        val checkInt = (System.nanoTime() and 0xFFFFFFFFL).toInt()
        writeInt(privSection, checkInt)
        writeInt(privSection, checkInt)
        writeString(privSection, "ssh-ed25519")
        writeBytes(privSection, pubKey)
        // Ed25519 "private key" in OpenSSH = prv || pubkey (64 bytes)
        writeBytes(privSection, prvBytes + pubKey)
        writeString(privSection, "") // comment
        // Padding to block size (8 bytes for "none" cipher)
        var padByte = 1
        while (privSection.size() % 8 != 0) {
            privSection.write(padByte++)
        }
        val privSectionBytes = privSection.toByteArray()

        // Assemble the full key
        out.write(authMagic)
        writeString(out, cipherName)
        writeString(out, kdfName)
        writeBytes(out, kdfOptions)
        writeInt(out, numKeys)
        writeBytes(out, pubSectionBytes)
        writeBytes(out, privSectionBytes)

        val blob = out.toByteArray()
        val b64 = java.util.Base64.getMimeEncoder(70, "\n".toByteArray())
            .encodeToString(blob)

        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
        return pem.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeBytes(out: ByteArrayOutputStream, data: ByteArray) {
        writeInt(out, data.size)
        out.write(data)
    }

    private fun writeString(out: ByteArrayOutputStream, str: String) {
        writeBytes(out, str.toByteArray())
    }
}
