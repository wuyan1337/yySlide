package sh.haven.core.stepca

import org.json.JSONObject

/**
 * Minimal OIDC Discovery (RFC 8414 / OpenID Connect Discovery 1.0) parser.
 *
 * step-ca's provisioner config exposes a `configurationEndpoint` URL
 * — typically a full `.well-known/openid-configuration` URL on the IdP.
 * Bootstrap fetches that document and pulls the three fields Haven needs
 * to drive the authorization code + PKCE flow: `issuer`,
 * `authorization_endpoint`, `token_endpoint`. Everything else is either
 * derivable (registration is out of band) or unused.
 *
 * The HTTP fetch lives in [StepCaApiClient.bootstrap]; this object is
 * pure parsing so it's trivially unit-testable.
 */
object OidcDiscovery {

    private const val WELL_KNOWN_PATH = "/.well-known/openid-configuration"

    data class Endpoints(
        val issuer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
    )

    /**
     * Normalise a step-ca `configurationEndpoint` value into the actual
     * OIDC discovery URL. step-ca admins sometimes paste the **issuer
     * URL** (e.g. Authentik's `https://auth.example.com/application/o/<slug>`)
     * where the full discovery URL belongs; Authentik responds with HTTP 404
     * on that path unless `/.well-known/openid-configuration` is appended.
     * Issue #133 (dionorgua).
     *
     * Conservative rule: if the URL already ends with the well-known
     * suffix (case-insensitive), return it unchanged. Otherwise trim
     * any trailing slash and append `/.well-known/openid-configuration`.
     * Pure string transform — does not validate the URL is reachable.
     */
    fun discoveryUrl(configurationEndpoint: String): String {
        val trimmed = configurationEndpoint.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.lowercase().endsWith(WELL_KNOWN_PATH)) return trimmed
        return trimmed.trimEnd('/') + WELL_KNOWN_PATH
    }

    fun parse(body: String): Endpoints {
        val json = JSONObject(body)
        val issuer = json.optString("issuer").ifBlank {
            throw IllegalArgumentException("OIDC discovery doc missing `issuer`")
        }
        val auth = json.optString("authorization_endpoint").ifBlank {
            throw IllegalArgumentException("OIDC discovery doc missing `authorization_endpoint`")
        }
        val token = json.optString("token_endpoint").ifBlank {
            throw IllegalArgumentException("OIDC discovery doc missing `token_endpoint`")
        }
        return Endpoints(issuer = issuer, authorizationEndpoint = auth, tokenEndpoint = token)
    }
}
