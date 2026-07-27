package sh.haven.core.stepca

import org.json.JSONObject

/**
 * Parser for step-ca's public `/provisioners` endpoint. step-ca returns
 * an envelope of the form:
 *
 * ```json
 * { "provisioners": [
 *     { "type": "OIDC", "name": "Authentik",
 *       "clientID": "...", "clientSecret": "...",
 *       "configurationEndpoint": "https://auth.example.com/.well-known/openid-configuration",
 *       "scopes": ["openid", "email"] },
 *     { "type": "JWK", "name": "admin" },
 *     ...
 *   ],
 *   "nextCursor": "..."
 * }
 * ```
 *
 * Bootstrap only needs the OIDC entries — that's what auto-fills the CA
 * registration form. step-ca exposes `clientSecret` on this unauthenticated
 * endpoint by design; we surface it as a regular field so confidential-
 * client IdPs (Authentik default, Keycloak, Okta) authenticate cleanly.
 */
object Provisioners {

    data class OidcProvisioner(
        val name: String,
        val clientId: String,
        val clientSecret: String?,
        val configurationEndpoint: String,
        val scopes: List<String>,
    )

    fun parseOidc(body: String): List<OidcProvisioner> {
        val root = JSONObject(body)
        val array = root.optJSONArray("provisioners") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (!obj.optString("type").equals("OIDC", ignoreCase = true)) continue

                val name = obj.optString("name").ifBlank { continue }
                val clientId = obj.optString("clientID").ifBlank { continue }
                val config = obj.optString("configurationEndpoint").ifBlank { continue }
                val secret = obj.optString("clientSecret").takeIf { it.isNotEmpty() }
                val scopesJson = obj.optJSONArray("scopes")
                val scopes = if (scopesJson == null) emptyList() else buildList {
                    for (s in 0 until scopesJson.length()) {
                        scopesJson.optString(s).takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                }
                add(
                    OidcProvisioner(
                        name = name,
                        clientId = clientId,
                        clientSecret = secret,
                        configurationEndpoint = config,
                        scopes = scopes,
                    ),
                )
            }
        }
    }
}
