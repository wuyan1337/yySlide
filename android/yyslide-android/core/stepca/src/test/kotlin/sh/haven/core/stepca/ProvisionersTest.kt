package sh.haven.core.stepca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProvisionersTest {

    @Test
    fun `extracts OIDC entries, ignores other types`() {
        val body = """
            {
              "provisioners": [
                {"type": "JWK", "name": "admin"},
                {"type": "OIDC", "name": "Authentik",
                 "clientID": "auth-client",
                 "clientSecret": "auth-secret",
                 "configurationEndpoint": "https://auth.example/.well-known/openid-configuration",
                 "scopes": ["openid", "email", "profile"]},
                {"type": "ACME", "name": "acme"},
                {"type": "OIDC", "name": "Keycloak",
                 "clientID": "kc-client",
                 "configurationEndpoint": "https://kc.example/realms/x/.well-known/openid-configuration"}
              ]
            }
        """.trimIndent()

        val out = Provisioners.parseOidc(body)
        assertEquals(2, out.size)

        val authentik = out[0]
        assertEquals("Authentik", authentik.name)
        assertEquals("auth-client", authentik.clientId)
        assertEquals("auth-secret", authentik.clientSecret)
        assertEquals(
            "https://auth.example/.well-known/openid-configuration",
            authentik.configurationEndpoint,
        )
        assertEquals(listOf("openid", "email", "profile"), authentik.scopes)

        val keycloak = out[1]
        assertEquals("Keycloak", keycloak.name)
        assertNull(keycloak.clientSecret)  // public client; no secret published
        assertTrue(keycloak.scopes.isEmpty())
    }

    @Test
    fun `OIDC type matches case-insensitively`() {
        val body = """
            {"provisioners": [
              {"type": "oidc", "name": "lc",
               "clientID": "x", "configurationEndpoint": "https://idp/.well-known/openid-configuration"}
            ]}
        """.trimIndent()
        assertEquals(1, Provisioners.parseOidc(body).size)
    }

    @Test
    fun `incomplete OIDC entries are skipped without crashing`() {
        val body = """
            {"provisioners": [
              {"type": "OIDC", "name": "", "clientID": "x", "configurationEndpoint": "https://idp/c"},
              {"type": "OIDC", "name": "y", "clientID": "", "configurationEndpoint": "https://idp/c"},
              {"type": "OIDC", "name": "z", "clientID": "x", "configurationEndpoint": ""}
            ]}
        """.trimIndent()
        assertTrue(Provisioners.parseOidc(body).isEmpty())
    }

    @Test
    fun `missing envelope returns empty`() {
        assertTrue(Provisioners.parseOidc("{}").isEmpty())
    }
}
