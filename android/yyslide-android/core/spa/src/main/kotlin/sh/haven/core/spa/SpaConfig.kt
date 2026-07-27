package sh.haven.core.spa

import java.util.Base64

/**
 * Per-connection-profile configuration for fwknop Single Packet Authorization.
 *
 * SPA is the cryptographic successor to port knocking ([sh.haven.core.knock]):
 * instead of an observable sequence of SYNs, the client sends one AES-encrypted,
 * HMAC-authenticated UDP packet that a passive observer can neither read nor
 * replay. Haven builds and sends this packet itself, just before the real
 * connect, via the same `preConnect` hook the knock uses.
 *
 * A blank [key] or [accessSpec] means "SPA disabled for this profile" — see
 * [parse], which returns `null` in that case.
 */
data class SpaConfig(
    /** Rijndael/AES key. Used as the OpenSSL passphrase (or base64-decoded when [keyIsBase64]). */
    val key: String,
    val keyIsBase64: Boolean = false,
    /** Optional HMAC-SHA256 key (encrypt-then-MAC). Blank/null = legacy no-HMAC mode. */
    val hmacKey: String? = null,
    val hmacKeyIsBase64: Boolean = false,
    /**
     * Access request, everything after the allow-IP: `proto/port` token(s),
     * comma-separated, e.g. `"tcp/22"` or `"tcp/22,tcp/80"`.
     */
    val accessSpec: String,
    /** How the allow-IP embedded in the SPA message is chosen. */
    val allowMode: AllowMode = AllowMode.SOURCE,
    /** Allow-IP/CIDR for [AllowMode.EXPLICIT]. */
    val explicitIp: String? = null,
    /** Destination UDP port for the SPA packet (fwknopd's `PCAP_FILTER`, default 62201). */
    val spaPort: Int = DEFAULT_SPA_PORT,
) {
    enum class AllowMode {
        /** Send `0.0.0.0`; fwknopd opens for the SPA packet's observed source IP. */
        SOURCE,

        /** Resolve our public egress IP (like `fwknop -R`) and embed it. */
        RESOLVE,

        /** Embed a fixed [explicitIp]. */
        EXPLICIT,
    }

    init {
        require(key.isNotEmpty()) { "SPA key is required" }
        require(accessSpec.isNotBlank()) { "SPA access spec is required" }
        require(spaPort in 1..65535) { "SPA port out of range: $spaPort" }
        if (allowMode == AllowMode.EXPLICIT) {
            require(!explicitIp.isNullOrBlank()) { "explicit allow-IP required for EXPLICIT mode" }
        }
        if (keyIsBase64) decodeB64(key) { "SPA key is not valid base64" }
        hmacKey?.takeIf { it.isNotEmpty() }?.let {
            if (hmacKeyIsBase64) decodeB64(it) { "HMAC key is not valid base64" }
        }
    }

    /** Raw AES passphrase bytes (base64-decoded when [keyIsBase64]). */
    val keyBytes: ByteArray
        get() = if (keyIsBase64) decodeB64(key) { "SPA key" } else key.toByteArray(Charsets.US_ASCII)

    /** Raw HMAC key bytes, or null when no HMAC key is configured. */
    val hmacKeyBytes: ByteArray?
        get() = hmacKey?.takeIf { it.isNotEmpty() }?.let {
            if (hmacKeyIsBase64) decodeB64(it) { "HMAC key" } else it.toByteArray(Charsets.US_ASCII)
        }

    /**
     * The fwknop access message — `"{allowIP},{accessSpec}"`. [resolvedIp] is the
     * egress IP fetched by [SpaSender] for [AllowMode.RESOLVE]; ignored otherwise.
     */
    fun accessMessage(resolvedIp: String? = null): String {
        val allowIp = when (allowMode) {
            AllowMode.SOURCE -> "0.0.0.0"
            AllowMode.EXPLICIT -> explicitIp!!.trim()
            AllowMode.RESOLVE -> requireNotNull(resolvedIp?.trim()?.ifEmpty { null }) {
                "RESOLVE mode requires a resolved egress IP"
            }
        }
        return "$allowIp,$accessSpec"
    }

    companion object {
        /** fwknopd's default SPA UDP port. */
        const val DEFAULT_SPA_PORT = 62201

        /**
         * Build a [SpaConfig] from per-profile fields. Returns:
         * - `Result.success(null)` when SPA is disabled (blank key or blank access spec),
         * - `Result.success(config)` for a valid config,
         * - `Result.failure(IllegalArgumentException)` for malformed input.
         */
        fun parse(
            key: String?,
            keyIsBase64: Boolean,
            hmacKey: String?,
            hmacKeyIsBase64: Boolean,
            accessSpec: String?,
            allowMode: String?,
            explicitIp: String?,
            spaPort: Int,
        ): Result<SpaConfig?> {
            val k = key?.trim().orEmpty()
            val spec = accessSpec?.trim().orEmpty()
            if (k.isEmpty() || spec.isEmpty()) return Result.success(null)
            return runCatching {
                validateAccessSpec(spec)
                val mode = parseAllowMode(allowMode)
                SpaConfig(
                    key = k,
                    keyIsBase64 = keyIsBase64,
                    hmacKey = hmacKey?.trim()?.ifEmpty { null },
                    hmacKeyIsBase64 = hmacKeyIsBase64,
                    accessSpec = spec,
                    allowMode = mode,
                    explicitIp = explicitIp?.trim()?.ifEmpty { null },
                    spaPort = if (spaPort in 1..65535) spaPort else DEFAULT_SPA_PORT,
                )
            }
        }

        private fun parseAllowMode(raw: String?): AllowMode =
            when (raw?.trim()?.uppercase()) {
                null, "", "SOURCE" -> AllowMode.SOURCE
                "RESOLVE" -> AllowMode.RESOLVE
                "EXPLICIT" -> AllowMode.EXPLICIT
                else -> throw IllegalArgumentException("unknown allow mode '$raw'")
            }

        /** Validate `proto/port[,proto/port...]` — each token is tcp|udp and port 1..65535. */
        private fun validateAccessSpec(spec: String) {
            val tokens = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            require(tokens.isNotEmpty()) { "empty access spec" }
            for (t in tokens) {
                val parts = t.split('/')
                require(parts.size == 2) { "access token must be proto/port: '$t'" }
                val proto = parts[0].lowercase()
                require(proto == "tcp" || proto == "udp") {
                    "unknown protocol '${parts[0]}' in '$t' (expected tcp or udp)"
                }
                val port = parts[1].toIntOrNull()
                    ?: throw IllegalArgumentException("not a port: '${parts[1]}' in '$t'")
                require(port in 1..65535) { "port out of range: $port (token '$t')" }
            }
        }

        private inline fun decodeB64(s: String, msg: () -> String): ByteArray =
            try {
                Base64.getDecoder().decode(s.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("${msg()}: ${e.message}", e)
            }
    }
}
