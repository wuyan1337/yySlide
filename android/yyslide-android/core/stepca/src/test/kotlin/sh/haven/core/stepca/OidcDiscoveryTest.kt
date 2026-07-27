package sh.haven.core.stepca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OidcDiscoveryTest {

    /**
     * Realistic shape from Authentik's `.well-known/openid-configuration`
     * trimmed to the fields Haven actually uses. Test ensures we pull the
     * three endpoints and ignore everything else without crashing on the
     * larger doc real IdPs return.
     */
    @Test
    fun `parses issuer + authorization_endpoint + token_endpoint`() {
        val body = """
            {
              "issuer": "https://auth.example.com/application/o/step-ca/",
              "authorization_endpoint": "https://auth.example.com/application/o/authorize/",
              "token_endpoint": "https://auth.example.com/application/o/token/",
              "userinfo_endpoint": "https://auth.example.com/application/o/userinfo/",
              "scopes_supported": ["openid","email","profile"],
              "response_types_supported": ["code"],
              "grant_types_supported": ["authorization_code","refresh_token"]
            }
        """.trimIndent()

        val out = OidcDiscovery.parse(body)
        assertEquals("https://auth.example.com/application/o/step-ca/", out.issuer)
        assertEquals(
            "https://auth.example.com/application/o/authorize/",
            out.authorizationEndpoint,
        )
        assertEquals("https://auth.example.com/application/o/token/", out.tokenEndpoint)
    }

    /** #133 — Authentik's `configurationEndpoint` is just the issuer
     *  URL, not the full discovery URL. Haven used to fetch it
     *  verbatim and Authentik 404'd. discoveryUrl() must append
     *  `/.well-known/openid-configuration` in that case. */
    @Test
    fun `discoveryUrl appends well-known path for issuer URLs`() {
        assertEquals(
            "https://auth.example.com/application/o/step-ca/.well-known/openid-configuration",
            OidcDiscovery.discoveryUrl("https://auth.example.com/application/o/step-ca"),
        )
        // Trailing slash on the issuer is also legal — strip it before append.
        assertEquals(
            "https://auth.example.com/application/o/step-ca/.well-known/openid-configuration",
            OidcDiscovery.discoveryUrl("https://auth.example.com/application/o/step-ca/"),
        )
    }

    /** When the admin pasted the full discovery URL (Google's default,
     *  Keycloak's docs example), leave it alone — appending would
     *  produce `…/.well-known/openid-configuration/.well-known/openid-configuration`
     *  which 404s on every IdP. */
    @Test
    fun `discoveryUrl leaves a complete discovery URL untouched`() {
        val full = "https://accounts.google.com/.well-known/openid-configuration"
        assertEquals(full, OidcDiscovery.discoveryUrl(full))
        // Case-insensitive match — admins occasionally paste mixed-case URLs.
        val mixed = "https://idp.example.com/.WELL-KNOWN/openid-configuration"
        assertEquals(mixed, OidcDiscovery.discoveryUrl(mixed))
    }

    @Test
    fun `missing token_endpoint throws with helpful message`() {
        val body = """
            {
              "issuer": "https://idp/",
              "authorization_endpoint": "https://idp/authorize"
            }
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            OidcDiscovery.parse(body)
        }
        val msg = ex.message ?: ""
        assertEquals(true, msg.contains("token_endpoint"))
    }
}
