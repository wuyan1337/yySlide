package sh.haven.core.fido

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Minimal CBOR encoder/decoder for the CTAP2 commands Haven uses:
 * - authenticatorGetInfo (0x04) — capabilities, supported PIN protocols.
 * - authenticatorClientPIN (0x06) — keyAgreement and PIN/UV token retrieval.
 * - authenticatorGetAssertion (0x02) — the actual SSH signing operation.
 *
 * Implements the subset of CBOR (RFC 8949) needed by these commands:
 * unsigned ints, negative ints, byte strings, text strings, arrays, maps,
 * and booleans. Integer keys can be positive or negative (COSE_Key uses both).
 */
object Ctap2Cbor {

    // CTAP2 command bytes
    const val CMD_GET_ASSERTION: Byte = 0x02
    const val CMD_GET_INFO: Byte = 0x04
    const val CMD_CLIENT_PIN: Byte = 0x06
    const val CMD_CREDENTIAL_MANAGEMENT: Byte = 0x0A

    // CTAP2 status codes
    const val STATUS_OK: Byte = 0x00
    const val STATUS_PIN_INVALID: Byte = 0x31
    const val STATUS_PIN_BLOCKED: Byte = 0x32
    const val STATUS_PIN_AUTH_INVALID: Byte = 0x33
    const val STATUS_PIN_AUTH_BLOCKED: Byte = 0x34
    const val STATUS_PIN_NOT_SET: Byte = 0x35
    const val STATUS_PIN_REQUIRED: Byte = 0x36
    const val STATUS_PIN_POLICY_VIOLATION: Byte = 0x37
    const val STATUS_PIN_TOKEN_EXPIRED: Byte = 0x38
    const val STATUS_NO_CREDENTIALS: Byte = 0x2E
    const val STATUS_ACTION_TIMEOUT: Byte = 0x27

    // clientPIN subcommand codes
    const val PIN_SUB_GET_KEY_AGREEMENT = 2
    const val PIN_SUB_GET_PIN_TOKEN_LEGACY = 5
    const val PIN_SUB_GET_PIN_TOKEN_WITH_PERMS = 9

    // PIN/UV permission bits (FIDO2 §6.5.5.7)
    const val PERMISSION_GET_ASSERTION = 0x02
    const val PERMISSION_CREDENTIAL_MANAGEMENT = 0x04

    // authenticatorCredentialManagement subcommands (CTAP 2.1 §6.8.2)
    const val CM_SUB_GET_CREDS_METADATA = 1
    const val CM_SUB_ENUMERATE_RPS_BEGIN = 2
    const val CM_SUB_ENUMERATE_RPS_GET_NEXT = 3
    const val CM_SUB_ENUMERATE_CREDS_BEGIN = 4
    const val CM_SUB_ENUMERATE_CREDS_GET_NEXT = 5

    data class AssertionResponse(
        val authData: ByteArray,
        val signature: ByteArray,
    )

    /** Parsed authenticatorGetInfo response (only the fields Haven uses). */
    data class GetInfoResponse(
        val pinUvAuthProtocols: List<Int>,
        val clientPinSet: Boolean,
        /** Built-in user verification (biometric) is configured. */
        val uvBuiltIn: Boolean,
        /**
         * `pinUvAuthToken: true` in GetInfo options means the authenticator
         * implements CTAP 2.1 `getPinUvAuthTokenUsingPinWithPermissions`
         * (subCommand 0x09). Older CTAP 2.0 / 2.1-PRE keys (e.g. YubiKey 5
         * shipped before mid-2021) only support the legacy `getPinToken`
         * (subCommand 0x05); calling 0x09 on them returns INVALID_COMMAND.
         */
        val pinUvAuthTokenSupported: Boolean,
    )

    /** Authenticator's COSE_Key from clientPIN getKeyAgreement (P-256 only). */
    data class CoseEcdhPubKey(val x: ByteArray, val y: ByteArray)

    // ---------- authenticatorGetAssertion ----------

    /**
     * Encode authenticatorGetAssertion. When [pinUvAuthParam] is non-null,
     * the request also carries the PIN/UV auth proof under keys 6 and 7.
     */
    fun encodeGetAssertionCommand(
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        pinUvAuthParam: ByteArray? = null,
        pinUvAuthProtocol: Int? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_GET_ASSERTION.toInt())

        val mapEntries = if (pinUvAuthParam != null) 6 else 4
        encodeMapHeader(out, mapEntries)

        // 1: rpId
        encodeUint(out, 1)
        encodeTextString(out, rpId)

        // 2: clientDataHash
        encodeUint(out, 2)
        encodeByteString(out, clientDataHash)

        // 3: allowList[{id, type}]
        encodeUint(out, 3)
        encodeArrayHeader(out, 1)
        encodeMapHeader(out, 2)
        encodeTextString(out, "id")
        encodeByteString(out, credentialId)
        encodeTextString(out, "type")
        encodeTextString(out, "public-key")

        // 5: options {up: true}
        encodeUint(out, 5)
        encodeMapHeader(out, 1)
        encodeTextString(out, "up")
        encodeBoolean(out, true)

        // 6, 7: pinUvAuthParam + protocol (only when UV path is in use)
        if (pinUvAuthParam != null) {
            requireNotNull(pinUvAuthProtocol) { "pinUvAuthProtocol required when pinUvAuthParam set" }
            encodeUint(out, 6)
            encodeByteString(out, pinUvAuthParam)
            encodeUint(out, 7)
            encodeUint(out, pinUvAuthProtocol)
        }

        return out.toByteArray()
    }

    fun decodeGetAssertionResponse(data: ByteArray): AssertionResponse {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)

        var authData: ByteArray? = null
        var signature: ByteArray? = null

        for (i in 0 until mapSize) {
            val key = readSignedInt(buf)
            when (key) {
                1 -> skipValue(buf)
                2 -> authData = readByteString(buf)
                3 -> signature = readByteString(buf)
                else -> skipValue(buf)
            }
        }

        requireNotNull(authData) { "GetAssertion response missing authData (key 2)" }
        requireNotNull(signature) { "GetAssertion response missing signature (key 3)" }
        return AssertionResponse(authData, signature)
    }

    // ---------- authenticatorGetInfo ----------

    /** GetInfo has no payload — just the command byte. */
    fun encodeGetInfoCommand(): ByteArray = byteArrayOf(CMD_GET_INFO)

    fun decodeGetInfoResponse(data: ByteArray): GetInfoResponse {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)

        var pinProtocols: List<Int> = emptyList()
        var clientPin = false
        var uv = false
        var pinUvAuthToken = false

        for (i in 0 until mapSize) {
            val key = readSignedInt(buf)
            when (key) {
                4 -> { // options: map<text, bool>
                    val optSize = readMapHeader(buf)
                    for (j in 0 until optSize) {
                        val optKey = readTextString(buf)
                        val optVal = readBoolean(buf)
                        when (optKey) {
                            "clientPin" -> clientPin = optVal
                            "uv" -> uv = optVal
                            "pinUvAuthToken" -> pinUvAuthToken = optVal
                        }
                    }
                }
                6 -> { // pinUvAuthProtocols: array of uint
                    val n = readArrayHeader(buf)
                    pinProtocols = (0 until n).map { readSignedInt(buf) }
                }
                else -> skipValue(buf)
            }
        }

        return GetInfoResponse(
            pinUvAuthProtocols = pinProtocols,
            clientPinSet = clientPin,
            uvBuiltIn = uv,
            pinUvAuthTokenSupported = pinUvAuthToken,
        )
    }

    // ---------- authenticatorClientPIN ----------

    /** clientPIN subcommand 2 (getKeyAgreement) — body { 1: protocol, 2: 2 }. */
    fun encodeClientPinGetKeyAgreement(protocol: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_CLIENT_PIN.toInt())
        encodeMapHeader(out, 2)
        encodeUint(out, 1); encodeUint(out, protocol)
        encodeUint(out, 2); encodeUint(out, PIN_SUB_GET_KEY_AGREEMENT)
        return out.toByteArray()
    }

    /**
     * clientPIN subcommand 5 (getPinToken — legacy CTAP 2.0):
     *   { 1: protocol, 2: 5, 3: platformKeyAgreement (COSE_Key), 6: pinHashEnc }
     *
     * Returned token is implicitly bound to GetAssertion + MakeCredential
     * permissions on every rpId — the per-permission/per-rpId scoping that
     * subCommand 9 introduced is unavailable here. Use this when GetInfo's
     * `options.pinUvAuthToken` is absent or false.
     */
    fun encodeClientPinGetTokenLegacy(
        protocol: Int,
        platformKeyAgreement: CoseEcdhPubKey,
        pinHashEnc: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_CLIENT_PIN.toInt())
        encodeMapHeader(out, 4)
        encodeUint(out, 1); encodeUint(out, protocol)
        encodeUint(out, 2); encodeUint(out, PIN_SUB_GET_PIN_TOKEN_LEGACY)
        encodeUint(out, 3); encodeCoseEcdhKey(out, platformKeyAgreement)
        encodeUint(out, 6); encodeByteString(out, pinHashEnc)
        return out.toByteArray()
    }

    /**
     * clientPIN subcommand 9 (getPinUvAuthTokenUsingPinWithPermissions):
     *   { 1: protocol, 2: 9, 3: platformKeyAgreement (COSE_Key),
     *     6: pinHashEnc, 9: permissions, 10: rpId }
     */
    fun encodeClientPinGetTokenWithPermissions(
        protocol: Int,
        platformKeyAgreement: CoseEcdhPubKey,
        pinHashEnc: ByteArray,
        permissions: Int,
        rpId: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_CLIENT_PIN.toInt())

        val entries = if (rpId != null) 6 else 5
        encodeMapHeader(out, entries)

        encodeUint(out, 1); encodeUint(out, protocol)
        encodeUint(out, 2); encodeUint(out, PIN_SUB_GET_PIN_TOKEN_WITH_PERMS)
        encodeUint(out, 3); encodeCoseEcdhKey(out, platformKeyAgreement)
        encodeUint(out, 6); encodeByteString(out, pinHashEnc)
        encodeUint(out, 9); encodeUint(out, permissions)
        if (rpId != null) {
            encodeUint(out, 10); encodeTextString(out, rpId)
        }
        return out.toByteArray()
    }

    /**
     * Decode the COSE_Key from a getKeyAgreement response. The response map
     * has key 1 carrying the COSE_Key sub-map.
     */
    fun decodeClientPinKeyAgreementResponse(data: ByteArray): CoseEcdhPubKey {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)
        var key: CoseEcdhPubKey? = null
        for (i in 0 until mapSize) {
            val k = readSignedInt(buf)
            if (k == 1) {
                key = decodeCoseEcdhKey(buf)
            } else {
                skipValue(buf)
            }
        }
        return requireNotNull(key) { "clientPIN keyAgreement response missing key 1" }
    }

    /** Decode the encrypted pinUvAuthToken (response key 2) from a getToken reply. */
    fun decodeClientPinTokenResponse(data: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)
        var token: ByteArray? = null
        for (i in 0 until mapSize) {
            val k = readSignedInt(buf)
            if (k == 2) {
                token = readByteString(buf)
            } else {
                skipValue(buf)
            }
        }
        return requireNotNull(token) { "clientPIN token response missing key 2" }
    }

    /**
     * Encode a COSE_Key in CTAP2 canonical form for an ECDH P-256 key:
     *   { 1: 2 (EC2), 3: -25 (ECDH-ES+HKDF-256), -1: 1 (P-256), -2: x, -3: y }
     */
    private fun encodeCoseEcdhKey(out: ByteArrayOutputStream, key: CoseEcdhPubKey) {
        encodeMapHeader(out, 5)
        encodeUint(out, 1); encodeUint(out, 2)        // kty = EC2
        encodeUint(out, 3); encodeNint(out, -25)      // alg = ECDH-ES+HKDF-256
        encodeNint(out, -1); encodeUint(out, 1)       // crv = P-256
        encodeNint(out, -2); encodeByteString(out, key.x)
        encodeNint(out, -3); encodeByteString(out, key.y)
    }

    /** Decode a COSE_Key from `buf` and return the (x, y) coordinates. */
    private fun decodeCoseEcdhKey(buf: ByteBuffer): CoseEcdhPubKey {
        val n = readMapHeader(buf)
        var x: ByteArray? = null
        var y: ByteArray? = null
        for (i in 0 until n) {
            val k = readSignedInt(buf)
            when (k) {
                -2 -> x = readByteString(buf)
                -3 -> y = readByteString(buf)
                else -> skipValue(buf) // kty, alg, crv — not needed once we know the shape
            }
        }
        requireNotNull(x) { "COSE_Key missing x (-2)" }
        requireNotNull(y) { "COSE_Key missing y (-3)" }
        return CoseEcdhPubKey(x, y)
    }

    // ---------- authenticatorCredentialManagement (CTAP 2.1 §6.8) ----------

    /**
     * COSE_Key shapes we care about for SSH-SK enumeration.
     * Ed25519 (OKP, alg = -8 EdDSA) carries the 32-byte public key at COSE
     * label -2 (`x`). ECDSA P-256 (EC2, alg = -7 ES256) carries `x` and `y`
     * at COSE labels -2 and -3.
     */
    sealed class CosePublicKey {
        data class Ed25519(val rawKey: ByteArray) : CosePublicKey()
        data class EcdsaP256(val x: ByteArray, val y: ByteArray) : CosePublicKey()
    }

    /** One row from authenticatorCredentialManagement.enumerateRPs. */
    data class RpEntity(
        val id: String,
        val rpIdHash: ByteArray,
        /** Only present on the Begin response; null on subsequent GetNext rows. */
        val totalRPs: Int? = null,
    )

    /** One row from authenticatorCredentialManagement.enumerateCredentials. */
    data class CredentialEntry(
        val credentialId: ByteArray,
        val publicKey: CosePublicKey,
        val userId: ByteArray?,
        val userName: String?,
        val userDisplayName: String?,
        /** Only present on the Begin response; null on subsequent GetNext rows. */
        val totalCredentials: Int? = null,
    )

    /**
     * Encode authenticatorCredentialManagement (0x0A).
     * @param subCommand one of `CM_SUB_*`.
     * @param subCommandParams CBOR-encoded params map (without the major-5
     *   wrapper byte — i.e. the bytes you'd get from encoding the params
     *   as a fresh CBOR map). Required for ENUMERATE_CREDS_BEGIN; null for
     *   GET_CREDS_METADATA, ENUMERATE_RPS_*, and the GET_NEXT subcommands.
     * @param pinUvAuthProtocol the PIN/UV protocol version (1 or 2).
     *   Required for any subCommand that takes a token (1, 2, 4); the
     *   GET_NEXT subcommands (3, 5) carry no auth.
     * @param pinUvAuthParam LEFT(16, HMAC-SHA-256(token, subCommand-byte ||
     *   subCommandParams)). Required for the same subcommands as protocol.
     */
    fun encodeCredentialManagementCommand(
        subCommand: Int,
        subCommandParams: ByteArray? = null,
        pinUvAuthProtocol: Int? = null,
        pinUvAuthParam: ByteArray? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(CMD_CREDENTIAL_MANAGEMENT.toInt())

        var entries = 1 // subCommand always
        if (subCommandParams != null) entries++
        if (pinUvAuthProtocol != null) entries++
        if (pinUvAuthParam != null) entries++
        encodeMapHeader(out, entries)

        encodeUint(out, 1); encodeUint(out, subCommand)
        if (subCommandParams != null) {
            encodeUint(out, 2)
            // subCommandParams is already a valid CBOR map; write it as-is.
            out.write(subCommandParams)
        }
        if (pinUvAuthProtocol != null) {
            encodeUint(out, 3); encodeUint(out, pinUvAuthProtocol)
        }
        if (pinUvAuthParam != null) {
            encodeUint(out, 4); encodeByteString(out, pinUvAuthParam)
        }
        return out.toByteArray()
    }

    /**
     * Encode the `subCommandParams` map for ENUMERATE_CREDS_BEGIN:
     *   { 0x01 (rpIDHash): bstr }
     * Returned bytes are what should be hashed (alongside the subCommand
     * byte) for the pinUvAuthParam derivation, AND what should be passed
     * as the `subCommandParams` argument to
     * [encodeCredentialManagementCommand].
     */
    fun encodeEnumerateCredentialsParams(rpIdHash: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        encodeMapHeader(out, 1)
        encodeUint(out, 1); encodeByteString(out, rpIdHash)
        return out.toByteArray()
    }

    /** Decode an enumerateRPsBegin or enumerateRPsGetNextRP response. */
    fun decodeEnumerateRPsResponse(data: ByteArray): RpEntity {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)
        var rpId: String? = null
        var rpIdHash: ByteArray? = null
        var totalRPs: Int? = null
        for (i in 0 until mapSize) {
            val key = readSignedInt(buf)
            when (key) {
                3 -> { // rp: PublicKeyCredentialRpEntity { "id": text, ... }
                    val n = readMapHeader(buf)
                    for (j in 0 until n) {
                        val sub = readTextString(buf)
                        if (sub == "id") rpId = readTextString(buf) else skipValue(buf)
                    }
                }
                4 -> rpIdHash = readByteString(buf)
                5 -> totalRPs = readSignedInt(buf)
                else -> skipValue(buf)
            }
        }
        requireNotNull(rpId) { "enumerateRPs response missing rp.id (key 3)" }
        requireNotNull(rpIdHash) { "enumerateRPs response missing rpIDHash (key 4)" }
        return RpEntity(id = rpId, rpIdHash = rpIdHash, totalRPs = totalRPs)
    }

    /**
     * Decode an enumerateCredentialsBegin or enumerateCredentialsGetNext
     * response. The publicKey field is parsed to [CosePublicKey] —
     * Ed25519 (OKP -8) or ECDSA P-256 (EC2 -7); other algorithms throw.
     */
    fun decodeEnumerateCredentialsResponse(data: ByteArray): CredentialEntry {
        val buf = ByteBuffer.wrap(data)
        val mapSize = readMapHeader(buf)
        var userId: ByteArray? = null
        var userName: String? = null
        var userDisplay: String? = null
        var credentialId: ByteArray? = null
        var publicKey: CosePublicKey? = null
        var totalCreds: Int? = null
        for (i in 0 until mapSize) {
            val key = readSignedInt(buf)
            when (key) {
                6 -> { // user: PublicKeyCredentialUserEntity { id, name?, displayName? }
                    val n = readMapHeader(buf)
                    for (j in 0 until n) {
                        val sub = readTextString(buf)
                        when (sub) {
                            "id" -> userId = readByteString(buf)
                            "name" -> userName = readTextString(buf)
                            "displayName" -> userDisplay = readTextString(buf)
                            else -> skipValue(buf)
                        }
                    }
                }
                7 -> { // credentialID: PublicKeyCredentialDescriptor { id, type, transports? }
                    val n = readMapHeader(buf)
                    for (j in 0 until n) {
                        val sub = readTextString(buf)
                        if (sub == "id") credentialId = readByteString(buf) else skipValue(buf)
                    }
                }
                8 -> publicKey = decodeCoseAuthenticatorPublicKey(buf)
                9 -> totalCreds = readSignedInt(buf)
                else -> skipValue(buf)
            }
        }
        requireNotNull(credentialId) { "enumerateCredentials response missing credentialID (key 7)" }
        requireNotNull(publicKey) { "enumerateCredentials response missing publicKey (key 8)" }
        return CredentialEntry(
            credentialId = credentialId,
            publicKey = publicKey,
            userId = userId,
            userName = userName,
            userDisplayName = userDisplay,
            totalCredentials = totalCreds,
        )
    }

    /**
     * Decode a credential's COSE_Key into [CosePublicKey]. Handles the two
     * algorithms SSH-SK actually uses:
     *   - Ed25519 (kty=1 OKP, alg=-8): -1 = crv (6 = Ed25519), -2 = x (raw key).
     *   - ECDSA P-256 (kty=2 EC2, alg=-7): -1 = crv (1 = P-256), -2 = x, -3 = y.
     */
    private fun decodeCoseAuthenticatorPublicKey(buf: ByteBuffer): CosePublicKey {
        val n = readMapHeader(buf)
        var kty: Int? = null
        var alg: Int? = null
        var x: ByteArray? = null
        var y: ByteArray? = null
        for (i in 0 until n) {
            val k = readSignedInt(buf)
            when (k) {
                1 -> kty = readSignedInt(buf)
                3 -> alg = readSignedInt(buf)
                -1 -> skipValue(buf) // crv — implied by alg
                -2 -> x = readByteString(buf)
                -3 -> y = readByteString(buf)
                else -> skipValue(buf)
            }
        }
        return when {
            kty == 1 && alg == -8 && x != null && x.size == 32 -> CosePublicKey.Ed25519(x)
            kty == 2 && alg == -7 && x != null && y != null && x.size == 32 && y.size == 32 ->
                CosePublicKey.EcdsaP256(x, y)
            else -> throw IllegalArgumentException(
                "Unsupported COSE_Key for SSH-SK: kty=$kty alg=$alg x=${x?.size} y=${y?.size}"
            )
        }
    }

    // ---------- CBOR encoding helpers ----------

    private fun encodeUint(out: ByteArrayOutputStream, value: Int) {
        encodeMajor(out, 0, value)
    }

    private fun encodeNint(out: ByteArrayOutputStream, value: Int) {
        require(value < 0) { "encodeNint requires negative value, got $value" }
        encodeMajor(out, 1, -1 - value)
    }

    private fun encodeByteString(out: ByteArrayOutputStream, data: ByteArray) {
        encodeMajor(out, 2, data.size)
        out.write(data)
    }

    private fun encodeTextString(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        encodeMajor(out, 3, bytes.size)
        out.write(bytes)
    }

    private fun encodeMapHeader(out: ByteArrayOutputStream, size: Int) {
        encodeMajor(out, 5, size)
    }

    private fun encodeArrayHeader(out: ByteArrayOutputStream, size: Int) {
        encodeMajor(out, 4, size)
    }

    private fun encodeBoolean(out: ByteArrayOutputStream, value: Boolean) {
        out.write(if (value) 0xF5 else 0xF4)
    }

    private fun encodeMajor(out: ByteArrayOutputStream, majorType: Int, value: Int) {
        val mt = majorType shl 5
        when {
            value < 24 -> out.write(mt or value)
            value < 256 -> { out.write(mt or 24); out.write(value) }
            value < 65536 -> {
                out.write(mt or 25)
                out.write(value shr 8)
                out.write(value and 0xFF)
            }
            else -> {
                out.write(mt or 26)
                out.write(value shr 24)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write(value and 0xFF)
            }
        }
    }

    // ---------- CBOR decoding helpers ----------

    private fun readMapHeader(buf: ByteBuffer): Int {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 5) { "Expected CBOR map, got major type $major" }
        return readAdditionalInfo(buf, initial and 0x1F)
    }

    private fun readArrayHeader(buf: ByteBuffer): Int {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 4) { "Expected CBOR array, got major type $major" }
        return readAdditionalInfo(buf, initial and 0x1F)
    }

    /** Read an int that may be positive (major 0) or negative (major 1). */
    private fun readSignedInt(buf: ByteBuffer): Int {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        val info = readAdditionalInfo(buf, initial and 0x1F)
        return when (major) {
            0 -> info
            1 -> -1 - info
            else -> throw IllegalArgumentException("Expected CBOR int, got major type $major")
        }
    }

    private fun readByteString(buf: ByteBuffer): ByteArray {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 2) { "Expected CBOR byte string, got major type $major" }
        val len = readAdditionalInfo(buf, initial and 0x1F)
        val data = ByteArray(len)
        buf.get(data)
        return data
    }

    private fun readTextString(buf: ByteBuffer): String {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        require(major == 3) { "Expected CBOR text string, got major type $major" }
        val len = readAdditionalInfo(buf, initial and 0x1F)
        val data = ByteArray(len)
        buf.get(data)
        return String(data, Charsets.UTF_8)
    }

    private fun readBoolean(buf: ByteBuffer): Boolean {
        val b = buf.get().toInt() and 0xFF
        return when (b) {
            0xF5 -> true
            0xF4 -> false
            else -> throw IllegalArgumentException("Expected CBOR boolean, got 0x${"%02x".format(b)}")
        }
    }

    private fun readAdditionalInfo(buf: ByteBuffer, info: Int): Int = when {
        info < 24 -> info
        info == 24 -> buf.get().toInt() and 0xFF
        info == 25 -> ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
        info == 26 -> ((buf.get().toInt() and 0xFF) shl 24) or
            ((buf.get().toInt() and 0xFF) shl 16) or
            ((buf.get().toInt() and 0xFF) shl 8) or
            (buf.get().toInt() and 0xFF)
        else -> throw IllegalArgumentException("Unsupported CBOR additional info: $info")
    }

    private fun skipValue(buf: ByteBuffer) {
        val initial = buf.get().toInt() and 0xFF
        val major = initial shr 5
        val info = initial and 0x1F
        when (major) {
            0, 1 -> readAdditionalInfo(buf, info)
            2, 3 -> {
                val len = readAdditionalInfo(buf, info)
                buf.position(buf.position() + len)
            }
            4 -> {
                val count = readAdditionalInfo(buf, info)
                repeat(count) { skipValue(buf) }
            }
            5 -> {
                val count = readAdditionalInfo(buf, info)
                repeat(count) { skipValue(buf); skipValue(buf) }
            }
            7 -> {} // simple values (true/false/null) — already consumed
        }
    }
}
