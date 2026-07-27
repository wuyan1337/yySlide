package sh.haven.core.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * Converts Dropbear private key format to OpenSSH format.
 *
 * Dropbear uses a simple binary format: key type string followed by
 * key-type-specific fields in SSH wire format (length-prefixed).
 * This converter parses the binary format and produces an OpenSSH
 * private key file that JSch can load.
 */
object DropbearKeyConverter {

    private val KNOWN_KEY_TYPES = setOf(
        "ssh-rsa", "ssh-dss", "ssh-ed25519",
        "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
    )

    /**
     * Returns true if the bytes look like a Dropbear private key.
     * Checks for a valid SSH key type string at the start.
     */
    fun isDropbearKey(data: ByteArray): Boolean {
        if (data.size < 10) return false
        // Check it's not already an OpenSSH/PEM key
        val prefix = String(data, 0, minOf(30, data.size), Charsets.US_ASCII)
        if (prefix.startsWith("-----") || prefix.startsWith("openssh-key-v1")) return false
        if (prefix.startsWith("PuTTY-User-Key-File")) return false

        return try {
            val buf = ByteBuffer.wrap(data)
            val keyType = readString(buf)
            keyType in KNOWN_KEY_TYPES
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Convert Dropbear private key bytes to OpenSSH format.
     * @throws IllegalArgumentException if the key format is invalid
     */
    fun toOpenSsh(data: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(data)
        val keyType = readString(buf)

        return when (keyType) {
            "ssh-rsa" -> convertRsa(buf)
            "ssh-ed25519" -> convertEd25519(buf)
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521" -> convertEcdsa(buf, keyType)
            "ssh-dss" -> convertDss(buf)
            else -> throw IllegalArgumentException("Unsupported Dropbear key type: $keyType")
        }
    }

    // ---- RSA ----

    private fun convertRsa(buf: ByteBuffer): ByteArray {
        // Dropbear RSA format: e, n, d, p, q
        val e = readMpint(buf)
        val n = readMpint(buf)
        val d = readMpint(buf)
        val p = readMpint(buf)
        val q = readMpint(buf)

        // Derive CRT parameters
        val dp = d.mod(p.subtract(BigInteger.ONE))
        val dq = d.mod(q.subtract(BigInteger.ONE))
        val iqmp = q.modInverse(p)

        // Build public key blob: ssh-rsa || e || n
        val pubBlob = buildSshBlob {
            writeString("ssh-rsa")
            writeMpint(e)
            writeMpint(n)
        }

        // Build private section: keytype || n || e || d || iqmp || p || q || comment
        return buildOpenSshKey("ssh-rsa", pubBlob) {
            writeString("ssh-rsa")
            writeMpint(n)
            writeMpint(e)
            writeMpint(d)
            writeMpint(iqmp)
            writeMpint(p)
            writeMpint(q)
            writeString("") // comment
        }
    }

    // ---- Ed25519 ----

    private fun convertEd25519(buf: ByteBuffer): ByteArray {
        // Dropbear Ed25519: 64 bytes (32 seed + 32 pub) as one string field
        val keyMaterial = readBytes(buf)
        if (keyMaterial.size != 64) {
            throw IllegalArgumentException("Ed25519 key material must be 64 bytes, got ${keyMaterial.size}")
        }
        val seed = keyMaterial.copyOfRange(0, 32)
        val pub = keyMaterial.copyOfRange(32, 64)

        val pubBlob = buildSshBlob {
            writeString("ssh-ed25519")
            writeBytes(pub)
        }

        return buildOpenSshKey("ssh-ed25519", pubBlob) {
            writeString("ssh-ed25519")
            writeBytes(pub)
            writeBytes(seed + pub) // OpenSSH stores 64-byte expanded key (seed || pub)
            writeString("") // comment
        }
    }

    // ---- ECDSA ----

    private fun convertEcdsa(buf: ByteBuffer, keyType: String): ByteArray {
        // Dropbear ECDSA: curve_name, Q (public point), d (private scalar)
        val curveName = readString(buf)
        val publicPoint = readBytes(buf)
        val privateScalar = readMpint(buf)

        val pubBlob = buildSshBlob {
            writeString(keyType)
            writeString(curveName)
            writeBytes(publicPoint)
        }

        return buildOpenSshKey(keyType, pubBlob) {
            writeString(keyType)
            writeString(curveName)
            writeBytes(publicPoint)
            writeMpint(privateScalar)
            writeString("") // comment
        }
    }

    // ---- DSS ----

    private fun convertDss(buf: ByteBuffer): ByteArray {
        // Dropbear DSS: p, q, g, y, x
        val p = readMpint(buf)
        val q = readMpint(buf)
        val g = readMpint(buf)
        val y = readMpint(buf)
        val x = readMpint(buf)

        val pubBlob = buildSshBlob {
            writeString("ssh-dss")
            writeMpint(p)
            writeMpint(q)
            writeMpint(g)
            writeMpint(y)
        }

        return buildOpenSshKey("ssh-dss", pubBlob) {
            writeString("ssh-dss")
            writeMpint(p)
            writeMpint(q)
            writeMpint(g)
            writeMpint(y)
            writeMpint(x)
            writeString("") // comment
        }
    }

    // ---- OpenSSH format builder ----

    private fun buildOpenSshKey(
        keyType: String,
        pubBlob: ByteArray,
        privateSection: SshBlobBuilder.() -> Unit,
    ): ByteArray {
        val checkInt = SecureRandom().nextInt()

        // Build private section
        val privPayload = buildSshBlob {
            writeInt(checkInt)
            writeInt(checkInt)
            privateSection()
        }

        // Pad to 8-byte boundary
        val padded = ByteArrayOutputStream()
        padded.write(privPayload)
        var i = 1
        while (padded.size() % 8 != 0) {
            padded.write(i++ and 0xFF)
        }
        val paddedBytes = padded.toByteArray()

        // Assemble full OpenSSH key
        val out = ByteArrayOutputStream()
        out.write("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII))

        val wrapper = SshBlobBuilder(out)
        wrapper.writeString("none")    // cipher
        wrapper.writeString("none")    // kdfname
        wrapper.writeBytes(byteArrayOf()) // kdfoptions
        wrapper.writeInt(1)            // number of keys
        wrapper.writeBytes(pubBlob)    // public key
        wrapper.writeBytes(paddedBytes) // private section

        val raw = out.toByteArray()
        val b64 = java.util.Base64.getMimeEncoder(70, "\n".toByteArray()).encodeToString(raw)

        return ("-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n").toByteArray()
    }

    // ---- SSH wire format helpers ----

    private fun readString(buf: ByteBuffer): String {
        val bytes = readBytes(buf)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readBytes(buf: ByteBuffer): ByteArray {
        if (buf.remaining() < 4) throw IllegalArgumentException("Truncated key data")
        val len = buf.int
        if (len < 0 || len > buf.remaining()) {
            throw IllegalArgumentException("Invalid length $len (remaining: ${buf.remaining()})")
        }
        val bytes = ByteArray(len)
        buf.get(bytes)
        return bytes
    }

    private fun readMpint(buf: ByteBuffer): BigInteger {
        val bytes = readBytes(buf)
        return if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(1, bytes)
    }

    private fun buildSshBlob(block: SshBlobBuilder.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        SshBlobBuilder(out).block()
        return out.toByteArray()
    }

    private class SshBlobBuilder(private val out: ByteArrayOutputStream) {
        fun writeInt(value: Int) {
            out.write((value shr 24) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun writeString(s: String) {
            writeBytes(s.toByteArray(Charsets.US_ASCII))
        }

        fun writeBytes(b: ByteArray) {
            writeInt(b.size)
            out.write(b)
        }

        fun writeMpint(value: BigInteger) {
            val bytes = value.toByteArray()
            writeBytes(bytes)
        }
    }
}
