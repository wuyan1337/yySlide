package sh.haven.core.stepca

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 PKCE pair generator. Used by [OidcAuthClient] to bind the
 * authorization request to the token-exchange request without a client
 * secret — necessary for public mobile clients.
 */
internal object Pkce {

    /**
     * @return (verifier, challenge) where challenge = BASE64URL(SHA256(verifier)).
     *   The verifier is 43 chars of base64url-encoded random bytes
     *   (the spec allows 43–128 — 43 is the minimum and avoids any
     *   browser address-bar truncation concern).
     */
    fun generate(): Pair<String, String> {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val verifier = URL_ENCODER.encodeToString(random)
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = URL_ENCODER.encodeToString(sha256)
        return verifier to challenge
    }

    private val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
}
