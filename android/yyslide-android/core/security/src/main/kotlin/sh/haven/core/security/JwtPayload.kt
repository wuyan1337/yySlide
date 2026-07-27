package sh.haven.core.security

import org.json.JSONObject
import java.util.Base64

/**
 * Minimal JWT payload parser — extracts the `exp` and `email` claims
 * from a compact-serialised JWT (`header.payload.signature`).
 *
 * We deliberately don't verify the signature. The only consumer is the
 * Cloudflare Access flow, where the JWT comes either from the user's
 * own authenticated browser session (CookieManager) or is pasted from a
 * trusted `cloudflared` CLI run — in both cases we treat it as opaque
 * bearer material. Verification happens upstream when Cloudflare's edge
 * accepts or rejects the token on the WebSocket upgrade.
 *
 * Avoids pulling in a JWT library (jjwt, nimbus-jose) — both add several
 * MB of transitive deps for one base64-decode + JSON read.
 */
object JwtPayload {

    data class Payload(
        /** Unix epoch second from the `exp` claim, or `0` if missing/unparseable. */
        val expiresAtSeconds: Long,
        /** Email claim if present (Cloudflare Access tokens usually carry one). */
        val email: String?,
    )

    /**
     * Parse [jwt] and return its payload claims, or `null` if the token
     * isn't a well-formed JWS compact serialisation. Never throws —
     * callers handle the null branch as "treat token as opaque, no
     * expiry-aware UX".
     */
    fun parse(jwt: String): Payload? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        val payloadBytes = try {
            // JWS uses base64url with padding omitted.
            Base64.getUrlDecoder().decode(parts[1].trimEnd('='))
        } catch (_: IllegalArgumentException) {
            return null
        }
        val json = try {
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (_: Throwable) {
            return null
        }
        return Payload(
            expiresAtSeconds = json.optLong("exp", 0L),
            email = json.optString("email").takeIf { it.isNotBlank() },
        )
    }
}
