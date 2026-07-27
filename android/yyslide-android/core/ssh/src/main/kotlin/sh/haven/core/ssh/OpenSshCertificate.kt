package sh.haven.core.ssh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Parsed view of an OpenSSH certificate blob (the contents of a
 * `id_xxx-cert.pub` file). Format per OpenSSH's `PROTOCOL.certkeys` —
 * a wire-format binary that's a superset of the underlying public-key
 * blob. Phase 2b of #133 uses this to:
 *  - surface accurate `valid_before` on the Keys screen badge
 *  - drive lazy-renewal (`CertRenewalGate`) — compares [validBefore]
 *    against `now + leeway`
 *  - power CA-signed host-key trust (`HostKeyVerifier` matches
 *    [signatureKey] against `StepCaConfig.sshHostCaPublicKey` rows).
 *
 * We don't verify the trailing signature here — that's the SSH server's
 * job, not ours. We only need the metadata.
 */
data class OpenSshCertificate(
    val typeName: String,
    val nonce: ByteArray,
    /**
     * Algorithm-specific public-key bytes (e.g. for ed25519, the
     * 32-byte raw key wrapped in its wire-format string envelope).
     * Captured so callers that want to derive an OpenSSH single-line
     * form for the inner key can do so without a separate parse pass.
     */
    val publicKey: ByteArray,
    val serial: Long,
    val certType: Int,
    val keyId: String,
    val validPrincipals: List<String>,
    /** Unix seconds. 0 = "always valid from epoch". */
    val validAfter: Long,
    /**
     * Unix seconds. step-ca's spec uses uint64; UInt64.MAX (i.e.
     * 2^64 - 1) means "no expiry" — represented here as
     * [Long.MAX_VALUE] for convenience.
     */
    val validBefore: Long,
    /**
     * Wire-format public key of the issuing CA (type-name string +
     * algorithm-specific key blob). Hash it for `signatureKeyFingerprintSha256`
     * to look up the matching `StepCaConfig.sshHostCaPublicKey`.
     */
    val signatureKey: ByteArray,
) {
    fun isValidAt(nowSeconds: Long): Boolean =
        nowSeconds in validAfter..validBefore

    /** Negative when already expired. */
    fun secondsUntilExpiry(nowSeconds: Long): Long = validBefore - nowSeconds

    /**
     * SHA-256 fingerprint of the CA's signature key, in the unpadded
     * base64 form ssh-keygen emits (`SHA256:<...>` minus the prefix).
     * Used to match against a registered CA's host-CA pubkey, which
     * we can store/compare in the same format.
     */
    fun signatureKeyFingerprintSha256(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(signatureKey)
        return Base64.getEncoder().withoutPadding().encodeToString(hash)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenSshCertificate) return false
        return typeName == other.typeName &&
            nonce.contentEquals(other.nonce) &&
            publicKey.contentEquals(other.publicKey) &&
            serial == other.serial &&
            certType == other.certType &&
            keyId == other.keyId &&
            validPrincipals == other.validPrincipals &&
            validAfter == other.validAfter &&
            validBefore == other.validBefore &&
            signatureKey.contentEquals(other.signatureKey)
    }

    override fun hashCode(): Int {
        var r = typeName.hashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + publicKey.contentHashCode()
        r = 31 * r + serial.hashCode()
        r = 31 * r + certType
        r = 31 * r + keyId.hashCode()
        r = 31 * r + validPrincipals.hashCode()
        r = 31 * r + validAfter.hashCode()
        r = 31 * r + validBefore.hashCode()
        r = 31 * r + signatureKey.contentHashCode()
        return r
    }

    companion object {
        const val USER_CERT_TYPE = 1
        const val HOST_CERT_TYPE = 2

        /**
         * Convenience: try [parse] and swallow any parse failure as
         * null. Callers that treat malformed certs as a soft failure
         * (e.g. UI badge rendering — fall back to "Certificate
         * attached" without an expiry) prefer this over try/catch.
         */
        fun parseOrNull(input: ByteArray): OpenSshCertificate? = try {
            parse(input)
        } catch (_: Throwable) {
            null
        }

        /**
         * Parse a cert from any of the three forms it ships in:
         *  - OpenSSH text: `ssh-ed25519-cert-v01@openssh.com AAAA… user@host`
         *  - Bare base64 blob (no type prefix)
         *  - Raw decoded binary
         */
        fun parse(input: ByteArray): OpenSshCertificate {
            val text = runCatching { String(input, Charsets.US_ASCII).trim() }
                .getOrDefault("")
            val binary: ByteArray = if (looksLikeOpenSshLine(text)) {
                val parts = text.split(Regex("\\s+"))
                require(parts.size >= 2) { "Cert text missing base64 blob" }
                Base64.getDecoder().decode(parts[1])
            } else {
                runCatching { Base64.getDecoder().decode(text) }
                    .getOrElse { input }
            }
            return parseBinary(binary)
        }

        private fun looksLikeOpenSshLine(text: String): Boolean =
            text.startsWith("ssh-") ||
                text.startsWith("ecdsa-") ||
                text.startsWith("sk-")

        private fun parseBinary(blob: ByteArray): OpenSshCertificate {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
            val typeName = readString(buf)
            require(typeName.endsWith("-cert-v01@openssh.com")) {
                "Not an OpenSSH cert: $typeName"
            }
            val nonce = readBytes(buf)
            val pubKeyStart = buf.position()
            skipPublicKeyFields(buf, typeName)
            val publicKey = blob.copyOfRange(pubKeyStart, buf.position())

            val serial = buf.long
            val certType = buf.int
            val keyId = readString(buf)
            val validPrincipals = parsePrincipals(readBytes(buf))
            val validAfter = clampUInt64(buf.long)
            val validBefore = clampUInt64(buf.long)
            readBytes(buf) // critical options blob
            readBytes(buf) // extensions blob
            readBytes(buf) // reserved
            val signatureKey = readBytes(buf)
            // Trailing signature blob is not consumed.

            return OpenSshCertificate(
                typeName = typeName,
                nonce = nonce,
                publicKey = publicKey,
                serial = serial,
                certType = certType,
                keyId = keyId,
                validPrincipals = validPrincipals,
                validAfter = validAfter,
                validBefore = validBefore,
                signatureKey = signatureKey,
            )
        }

        /**
         * Skip the algorithm-specific public-key fields between [nonce]
         * and `serial`. Layout per PROTOCOL.certkeys §2.1.
         */
        private fun skipPublicKeyFields(buf: ByteBuffer, typeName: String) {
            when (typeName) {
                "ssh-ed25519-cert-v01@openssh.com" -> {
                    readBytes(buf) // 32-byte key
                }
                "sk-ssh-ed25519-cert-v01@openssh.com" -> {
                    readBytes(buf) // key
                    readString(buf) // application
                }
                "ssh-rsa-cert-v01@openssh.com" -> {
                    readBytes(buf); readBytes(buf) // e, n
                }
                "ssh-dss-cert-v01@openssh.com" -> {
                    repeat(4) { readBytes(buf) } // p, q, g, y
                }
                "sk-ecdsa-sha2-nistp256-cert-v01@openssh.com" -> {
                    readString(buf) // curve
                    readBytes(buf) // Q
                    readString(buf) // application
                }
                else -> {
                    if (typeName.startsWith("ecdsa-sha2-") &&
                        typeName.endsWith("-cert-v01@openssh.com")
                    ) {
                        readString(buf) // curve name
                        readBytes(buf) // Q
                    } else {
                        error("Unsupported cert type: $typeName")
                    }
                }
            }
        }

        /** `valid principals` is a wire-format list of strings. */
        private fun parsePrincipals(blob: ByteArray): List<String> {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.BIG_ENDIAN)
            val out = mutableListOf<String>()
            while (buf.hasRemaining()) {
                out += readString(buf)
            }
            return out
        }

        /**
         * OpenSSH encodes `valid_before = uint64::MAX` for
         * "no expiry". Java has no UInt64; treat any value with the
         * sign bit set as "no expiry" → [Long.MAX_VALUE]. Anything
         * positive is a literal unix-seconds timestamp.
         */
        private fun clampUInt64(raw: Long): Long =
            if (raw < 0) Long.MAX_VALUE else raw

        private fun readString(buf: ByteBuffer): String =
            String(readBytes(buf), Charsets.US_ASCII)

        private fun readBytes(buf: ByteBuffer): ByteArray {
            require(buf.remaining() >= 4) { "Truncated cert blob" }
            val len = buf.int
            require(len in 0..buf.remaining()) { "Bad length $len" }
            val out = ByteArray(len)
            buf.get(out)
            return out
        }
    }
}
