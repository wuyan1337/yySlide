package sh.haven.core.ssh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Parses OpenSSH user certificate files (`-cert.pub`) and extracts metadata
 * for validation and display.
 *
 * Wire format (RFC draft-miller-ssh-cert):
 *   string  cert_type (e.g. "ssh-ed25519-cert-v01@openssh.com")
 *   string  nonce
 *   ...key-specific fields...
 *   uint64  serial
 *   uint32  type (1=user, 2=host)
 *   string  key_id
 *   string  valid_principals (packed list)
 *   uint64  valid_after
 *   uint64  valid_before
 *   string  critical_options
 *   string  extensions
 *   string  reserved
 *   string  signature_key
 *   string  signature
 *
 * Used at attach time to validate the cert really belongs to the chosen
 * key (via [matchesKey]) and to surface principals / validity to the user.
 */
object SshCertificateParser {

    private const val CERT_SUFFIX = "-cert-v01@openssh.com"

    data class CertificateInfo(
        val certKeyType: String,
        val serial: Long,
        val keyId: String,
        val validPrincipals: List<String>,
        val validAfter: Long,
        val validBefore: Long,
        val rawBlob: ByteArray,
        val embeddedPublicKeyFingerprint: String,
    )

    /** Parse a `-cert.pub` file (text format: "type base64 [comment]"). Returns null if not a cert. */
    fun parse(fileBytes: ByteArray): CertificateInfo? {
        val text = fileBytes.decodeToString().trim()
        val parts = text.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        val certKeyType = parts[0]
        if (!certKeyType.endsWith(CERT_SUFFIX)) return null

        val blob = try {
            Base64.getDecoder().decode(parts[1])
        } catch (_: Exception) {
            return null
        }

        return parseBlob(certKeyType, blob)
    }

    /** Cheap check: does the file look like an SSH cert? Used before invoking the full parser. */
    fun isCertificateFile(fileBytes: ByteArray): Boolean {
        val text = fileBytes.decodeToString().trim()
        val firstSpace = text.indexOf(' ')
        if (firstSpace <= 0) return false
        return text.substring(0, firstSpace).endsWith(CERT_SUFFIX)
    }

    /** Strip `-cert-v01@openssh.com` to get the base key type. */
    fun getBaseKeyType(certKeyType: String): String =
        if (certKeyType.endsWith(CERT_SUFFIX)) certKeyType.removeSuffix(CERT_SUFFIX) else certKeyType

    /** Append `-cert-v01@openssh.com` if not already present. */
    fun getCertKeyType(baseKeyType: String): String =
        if (baseKeyType.endsWith(CERT_SUFFIX)) baseKeyType else "$baseKeyType$CERT_SUFFIX"

    /** True when the cert's embedded public key matches the supplied SHA-256 fingerprint. */
    fun matchesKey(cert: CertificateInfo, keyFingerprintSha256: String): Boolean =
        cert.embeddedPublicKeyFingerprint == keyFingerprintSha256

    /** True when the cert is currently within its validity window. `0` / `-1L` (forever) treated as unbounded. */
    fun isCurrentlyValid(cert: CertificateInfo): Boolean {
        val now = System.currentTimeMillis() / 1000
        val afterOk = cert.validAfter == 0L || now >= cert.validAfter
        val beforeOk = cert.validBefore == 0L ||
            cert.validBefore == -1L ||
            now <= cert.validBefore
        return afterOk && beforeOk
    }

    private fun parseBlob(certKeyType: String, blob: ByteArray): CertificateInfo? {
        val buf = ByteBuffer.wrap(blob)
        buf.order(ByteOrder.BIG_ENDIAN)

        try {
            val encodedType = readString(buf)
            if (encodedType != certKeyType) return null

            // Nonce — read and discard.
            readBytes(buf)

            // Key-specific fields are needed twice: once to reconstruct the
            // public-key blob (for fingerprinting), once to advance the
            // cursor past them so we can read serial/type/principals next.
            val publicKeyBlob = extractPublicKeyBlob(certKeyType, blob)
            skipKeyFields(buf, certKeyType)

            val serial = buf.long
            @Suppress("UNUSED_VARIABLE")
            val type = buf.int  // 1=user, 2=host — we accept both, the auth path won't accept hosts anyway.
            val keyId = readString(buf)
            val principalsBlob = readBytes(buf)
            val principals = parsePrincipalsList(principalsBlob)
            val validAfter = buf.long
            val validBefore = buf.long

            val fingerprint = if (publicKeyBlob != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
                "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
            } else ""

            return CertificateInfo(
                certKeyType = certKeyType,
                serial = serial,
                keyId = keyId,
                validPrincipals = principals,
                validAfter = validAfter,
                validBefore = validBefore,
                rawBlob = blob,
                embeddedPublicKeyFingerprint = fingerprint,
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Reconstruct the SSH wire-format public key blob (type + key data, the
     * same shape that appears in `authorized_keys`) so we can fingerprint it
     * and compare against the [SshKey.fingerprintSha256].
     */
    private fun extractPublicKeyBlob(certKeyType: String, certBlob: ByteArray): ByteArray? {
        val baseType = getBaseKeyType(certKeyType)
        val buf = ByteBuffer.wrap(certBlob)
        buf.order(ByteOrder.BIG_ENDIAN)

        try {
            readBytes(buf) // cert type
            readBytes(buf) // nonce

            val pubKeyBuf = java.io.ByteArrayOutputStream()
            writeBytes(pubKeyBuf, baseType.toByteArray())

            when {
                baseType.contains("ed25519") -> {
                    val pk = readBytes(buf)
                    writeBytes(pubKeyBuf, pk)
                }
                baseType.contains("ecdsa") -> {
                    val curve = readBytes(buf)
                    val point = readBytes(buf)
                    writeBytes(pubKeyBuf, curve)
                    writeBytes(pubKeyBuf, point)
                }
                baseType.contains("rsa") -> {
                    val e = readBytes(buf)
                    val n = readBytes(buf)
                    writeBytes(pubKeyBuf, e)
                    writeBytes(pubKeyBuf, n)
                }
                else -> return null
            }
            return pubKeyBuf.toByteArray()
        } catch (_: Exception) {
            return null
        }
    }

    private fun skipKeyFields(buf: ByteBuffer, certKeyType: String) {
        val baseType = getBaseKeyType(certKeyType)
        when {
            baseType.contains("ed25519") -> {
                readBytes(buf) // public key (32 bytes)
            }
            baseType.contains("ecdsa") -> {
                readBytes(buf) // curve name
                readBytes(buf) // EC point
            }
            baseType.contains("rsa") -> {
                readBytes(buf) // e
                readBytes(buf) // n
            }
            baseType.contains("dss") || baseType.contains("dsa") -> {
                readBytes(buf) // p
                readBytes(buf) // q
                readBytes(buf) // g
                readBytes(buf) // y
            }
        }
    }

    private fun parsePrincipalsList(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        val result = mutableListOf<String>()
        while (buf.hasRemaining()) {
            result.add(readString(buf))
        }
        return result
    }

    private fun readString(buf: ByteBuffer): String = String(readBytes(buf))

    private fun readBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        require(len in 0..buf.remaining()) { "Invalid length: $len (remaining: ${buf.remaining()})" }
        val data = ByteArray(len)
        buf.get(data)
        return data
    }

    private fun writeBytes(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(data.size)
        out.write(lenBuf.array())
        out.write(data)
    }
}
