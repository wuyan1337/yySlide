package sh.haven.core.tunnel

import org.json.JSONObject

/**
 * Storage envelope for Cloudflare Tunnel configs (with optional Cloudflare
 * Access auth on top). Encoded into
 * [sh.haven.core.data.db.entities.TunnelConfig.configText] (which is then
 * encrypted at rest by the repository).
 *
 * Each row binds the tunnel to a single Cloudflare Tunnel hostname — the
 * server-side connector decides which upstream SSH host to forward to,
 * either statically (one route → one target) or via the optional
 * [jumpDestination] field which is forwarded as
 * `Cf-Access-Jump-Destination` on the WS upgrade.
 *
 * **JWT is optional**: unprotected Cloudflare Tunnel routes need no auth.
 * If the user signs in via the in-app WebView, the resulting JWT and
 * its expiry are cached here for re-use.
 *
 * Format (UTF-8 JSON):
 * ```
 * {
 *   "hostname": "ssh.example.com",
 *   "teamDomain": "myteam.cloudflareaccess.com",    // optional, only when Access is in front
 *   "jwt": "eyJhbGciOi…",                          // optional, empty when no Access
 *   "jwtExpiresAt": 1737934800,                    // 0 if jwt is empty / unparseable
 *   "jumpDestination": "internal-host:22"          // optional, bastion-mode only
 * }
 * ```
 *
 * Unknown JSON keys are ignored — adding fields (service-token credentials,
 * last-used host) won't break older clients reading newer blobs.
 */
data class CloudflareAccessConfigBlob(
    val hostname: String,
    val teamDomain: String,
    val jwt: String,
    val jwtExpiresAt: Long,
    val jumpDestination: String = "",
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("hostname", hostname)
            put("teamDomain", teamDomain)
            put("jwt", jwt)
            put("jwtExpiresAt", jwtExpiresAt)
            if (jumpDestination.isNotBlank()) {
                put("jumpDestination", jumpDestination)
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun isJwtExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        jwt.isNotBlank() && jwtExpiresAt in 1 until nowEpochSeconds

    companion object {
        /**
         * Decode bytes from [TunnelConfig.configText]. Throws
         * [IllegalArgumentException] on malformed JSON or missing hostname.
         * JWT is optional — blobs from rc4+ may legitimately omit it for
         * unprotected Cloudflare Tunnel routes.
         */
        fun parse(bytes: ByteArray): CloudflareAccessConfigBlob {
            val text = String(bytes, Charsets.UTF_8).trim()
            val json = try {
                JSONObject(text)
            } catch (t: Throwable) {
                throw IllegalArgumentException("CloudflareAccessConfigBlob: not valid JSON", t)
            }
            val hostname = json.optString("hostname").trim()
            val teamDomain = json.optString("teamDomain").trim()
            val jwt = json.optString("jwt").trim()
            val jumpDestination = json.optString("jumpDestination").trim()
            require(hostname.isNotEmpty()) { "CloudflareAccessConfigBlob: missing hostname" }
            return CloudflareAccessConfigBlob(
                hostname = hostname,
                teamDomain = teamDomain,
                jwt = jwt,
                jwtExpiresAt = json.optLong("jwtExpiresAt", 0L),
                jumpDestination = jumpDestination,
            )
        }
    }
}
