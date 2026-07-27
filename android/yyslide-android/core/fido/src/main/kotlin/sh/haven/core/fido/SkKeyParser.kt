package sh.haven.core.fido

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

private const val TAG = "SkKeyParser"

/**
 * Parses OpenSSH private key files for sk-ssh-ed25519@openssh.com and
 * sk-ecdsa-sha2-nistp256@openssh.com key types.
 *
 * The OpenSSH key v1 format is:
 *   "openssh-key-v1\0" || cipher || kdf || kdf_options || num_keys
 *   || public_key_section || private_key_section
 */
object SkKeyParser {

    private const val AUTH_MAGIC = "openssh-key-v1\u0000"

    /** Quick check if file bytes look like an SK key. */
    fun isSkKeyFile(fileBytes: ByteArray): Boolean {
        val text = fileBytes.decodeToString()
        if (!text.contains("BEGIN OPENSSH PRIVATE KEY")) return false
        val b64 = extractBase64(text) ?: return false
        val raw = Base64.getDecoder().decode(b64)
        if (raw.size < AUTH_MAGIC.length + 20) return false
        val magic = String(raw, 0, AUTH_MAGIC.length)
        if (magic != AUTH_MAGIC) return false
        // Read far enough to get the key type from the public key section
        val buf = ByteBuffer.wrap(raw)
        buf.position(AUTH_MAGIC.length)
        try {
            readString(buf) // ciphername
            readString(buf) // kdfname
            readBytes(buf)  // kdfoptions
            buf.int         // number of keys
            val pubSection = readBytes(buf) // public key section
            val pubBuf = ByteBuffer.wrap(pubSection)
            val keyType = readString(pubBuf)
            return keyType.startsWith("sk-")
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Parse an OpenSSH SK private key file and extract the credential data.
     *
     * @throws IllegalArgumentException if the file is not a valid SK key
     */
    fun parse(fileBytes: ByteArray): SkKeyData {
        val text = fileBytes.decodeToString()
        val b64 = extractBase64(text)
            ?: throw IllegalArgumentException("Not an OpenSSH private key")
        val raw = Base64.getDecoder().decode(b64)

        val buf = ByteBuffer.wrap(raw)
        buf.order(ByteOrder.BIG_ENDIAN)

        // Verify magic
        val magic = ByteArray(AUTH_MAGIC.length)
        buf.get(magic)
        require(String(magic) == AUTH_MAGIC) { "Invalid OpenSSH key magic" }

        val cipherName = readString(buf)
        val kdfName = readString(buf)
        val kdfOptions = readBytes(buf)
        val numKeys = buf.int

        Log.d(TAG, "SK key: cipher=$cipherName, kdf=$kdfName, keys=$numKeys")

        require(numKeys == 1) { "Expected 1 key, got $numKeys" }

        if (cipherName != "none") {
            throw IllegalArgumentException(
                "Encrypted SK key files are not yet supported. " +
                "Re-export without a passphrase: ssh-keygen -p -N '' -f <keyfile>"
            )
        }

        // Parse public key section
        val pubSection = readBytes(buf)
        val pubBuf = ByteBuffer.wrap(pubSection)
        val keyType = readString(pubBuf)
        Log.d(TAG, "SK key type: $keyType")

        require(keyType.startsWith("sk-")) {
            "Expected sk- key type, got: $keyType"
        }

        // The full public key section IS the public key blob (SSH wire format)
        val publicKeyBlob = pubSection

        // Parse private key section
        val privSection = readBytes(buf)
        val privBuf = ByteBuffer.wrap(privSection)

        // Check ints (both must match for unencrypted keys)
        val check1 = privBuf.int
        val check2 = privBuf.int
        require(check1 == check2) {
            "Check integers don't match (key may be encrypted)"
        }

        // Read private key fields
        val privKeyType = readString(privBuf)
        require(privKeyType == keyType) { "Key type mismatch: $privKeyType vs $keyType" }

        // Parse based on key type
        val (application, credentialId, flags) = when {
            keyType.contains("ed25519") -> parseEd25519Sk(privBuf)
            keyType.contains("ecdsa") -> parseEcdsaSk(privBuf)
            else -> throw IllegalArgumentException("Unknown SK key type: $keyType")
        }

        Log.d(TAG, "SK key parsed: app=$application, credentialId=${credentialId.size} bytes, flags=$flags")

        return SkKeyData(
            algorithmName = keyType,
            publicKeyBlob = publicKeyBlob,
            application = application,
            credentialId = credentialId,
            flags = flags,
        )
    }

    /**
     * Build the OpenSSH public key line (e.g. "sk-ssh-ed25519@openssh.com AAAA... comment")
     */
    fun formatPublicKeyLine(data: SkKeyData): String {
        val b64 = Base64.getEncoder().encodeToString(data.publicKeyBlob)
        return "${data.algorithmName} $b64"
    }

    fun fingerprintSha256(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    /**
     * Build an [SkKeyData] from a CTAP `authenticatorCredentialManagement`
     * `enumerateCredentials*` row plus the RP id it belongs to. Used by the
     * "Discover from security key" import path so the user can add resident
     * SSH-SK keys without ever generating a local stub file.
     *
     * The synthesised [SkKeyData.publicKeyBlob] follows the same OpenSSH
     * SSH-wire format as a parsed stub file's blob — algorithm name +
     * algorithm-specific fields + application. Downstream code (export,
     * fingerprinting, host-side authorized_keys handling) treats both
     * paths identically.
     *
     * [flags] defaults to `0x01` (user-presence bit), matching the SSH
     * USER_PRESENCE_REQUIRED constant. Callers that know the credential
     * was registered with verify-required should OR in `0x04`
     * (user-verification) so [FidoAuthenticator.getAssertion] triggers
     * the PIN/UV exchange when the key is used.
     */
    fun buildFromCtapCredential(
        credential: Ctap2Cbor.CredentialEntry,
        rpId: String,
        flags: Byte = 0x01,
    ): SkKeyData {
        return when (val pk = credential.publicKey) {
            is Ctap2Cbor.CosePublicKey.Ed25519 -> SkKeyData(
                algorithmName = "sk-ssh-ed25519@openssh.com",
                publicKeyBlob = buildEd25519SshPublicKeyBlob(pk.rawKey, rpId),
                application = rpId,
                credentialId = credential.credentialId,
                flags = flags,
            )
            is Ctap2Cbor.CosePublicKey.EcdsaP256 -> SkKeyData(
                algorithmName = "sk-ecdsa-sha2-nistp256@openssh.com",
                publicKeyBlob = buildEcdsaSshPublicKeyBlob(pk.x, pk.y, rpId),
                application = rpId,
                credentialId = credential.credentialId,
                flags = flags,
            )
        }
    }

    /**
     * SSH wire format for sk-ssh-ed25519@openssh.com public key:
     *   string  "sk-ssh-ed25519@openssh.com"
     *   string  32-byte Ed25519 public key
     *   string  application (e.g. "ssh:")
     */
    private fun buildEd25519SshPublicKeyBlob(rawKey: ByteArray, application: String): ByteArray {
        require(rawKey.size == 32) { "Ed25519 public key must be 32 bytes, got ${rawKey.size}" }
        val algorithm = "sk-ssh-ed25519@openssh.com".toByteArray()
        val app = application.toByteArray()
        val buf = ByteBuffer.allocate(4 + algorithm.size + 4 + rawKey.size + 4 + app.size)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(algorithm.size); buf.put(algorithm)
        buf.putInt(rawKey.size); buf.put(rawKey)
        buf.putInt(app.size); buf.put(app)
        return buf.array()
    }

    /**
     * SSH wire format for sk-ecdsa-sha2-nistp256@openssh.com public key:
     *   string  "sk-ecdsa-sha2-nistp256@openssh.com"
     *   string  "nistp256"
     *   string  65-byte uncompressed EC point (0x04 || X || Y)
     *   string  application
     */
    private fun buildEcdsaSshPublicKeyBlob(x: ByteArray, y: ByteArray, application: String): ByteArray {
        require(x.size == 32 && y.size == 32) {
            "ECDSA P-256 coords must be 32 bytes each, got x=${x.size} y=${y.size}"
        }
        val algorithm = "sk-ecdsa-sha2-nistp256@openssh.com".toByteArray()
        val curve = "nistp256".toByteArray()
        val ecPoint = ByteArray(1 + 32 + 32).apply {
            this[0] = 0x04 // uncompressed
            System.arraycopy(x, 0, this, 1, 32)
            System.arraycopy(y, 0, this, 33, 32)
        }
        val app = application.toByteArray()
        val total = 4 + algorithm.size + 4 + curve.size + 4 + ecPoint.size + 4 + app.size
        val buf = ByteBuffer.allocate(total)
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putInt(algorithm.size); buf.put(algorithm)
        buf.putInt(curve.size); buf.put(curve)
        buf.putInt(ecPoint.size); buf.put(ecPoint)
        buf.putInt(app.size); buf.put(app)
        return buf.array()
    }

    // Private section format for sk-ssh-ed25519@openssh.com:
    //   string  key_type ("sk-ssh-ed25519@openssh.com")  — already consumed
    //   string  public_key (32 bytes)
    //   string  application
    //   byte    flags
    //   string  key_handle (credential ID)
    //   string  reserved (empty)
    //   string  comment
    private fun parseEd25519Sk(buf: ByteBuffer): Triple<String, ByteArray, Byte> {
        val pubKey = readBytes(buf)     // 32-byte Ed25519 public key
        val application = readString(buf)
        val flags = buf.get()
        val keyHandle = readBytes(buf)  // credential ID
        val reserved = readBytes(buf)   // typically empty
        // comment follows but we don't need it
        Log.d(TAG, "Ed25519-SK: pubKey=${pubKey.size}b, app=$application, handle=${keyHandle.size}b")
        return Triple(application, keyHandle, flags)
    }

    // Private section format for sk-ecdsa-sha2-nistp256@openssh.com:
    //   string  key_type — already consumed
    //   string  curve_name ("nistp256")
    //   ec_point Q (public key point)
    //   string  application
    //   byte    flags
    //   string  key_handle (credential ID)
    //   string  reserved
    //   string  comment
    private fun parseEcdsaSk(buf: ByteBuffer): Triple<String, ByteArray, Byte> {
        val curveName = readString(buf)
        val ecPoint = readBytes(buf)    // uncompressed EC point
        val application = readString(buf)
        val flags = buf.get()
        val keyHandle = readBytes(buf)  // credential ID
        val reserved = readBytes(buf)   // typically empty
        Log.d(TAG, "ECDSA-SK: curve=$curveName, point=${ecPoint.size}b, app=$application, handle=${keyHandle.size}b")
        return Triple(application, keyHandle, flags)
    }

    private fun extractBase64(text: String): String? {
        val start = text.indexOf("-----BEGIN OPENSSH PRIVATE KEY-----")
        val end = text.indexOf("-----END OPENSSH PRIVATE KEY-----")
        if (start < 0 || end < 0) return null
        val body = text.substring(start + "-----BEGIN OPENSSH PRIVATE KEY-----".length, end)
        return body.replace(Regex("\\s+"), "")
    }

    private fun readString(buf: ByteBuffer): String = String(readBytes(buf))

    private fun readBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        require(len >= 0 && len <= buf.remaining()) {
            "Invalid length: $len (remaining: ${buf.remaining()})"
        }
        val data = ByteArray(len)
        buf.get(data)
        return data
    }
}
