package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A registered step-ca certificate authority the user has configured.
 * One CA serves many SSH keys/profiles; the row is referenced by
 * [SshKey.caConfigId] on every key minted via this CA.
 *
 * Phase 2a of #133. Phase 2b will add a renewal scheduler that reads
 * [oidcIssuer]/[oidcClientId] and uses [rootCertPem] for TLS pinning
 * just like the manual sign flow.
 */
@Entity(tableName = "step_ca_configs")
data class StepCaConfig(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    /** User-visible label, e.g. "Work step-ca". */
    val name: String,
    /** Base URL of the step-ca CA, e.g. `https://ca.internal:9000`. */
    val caUrl: String,
    /** OIDC issuer URL (used for `.well-known/openid-configuration` lookup). */
    val oidcIssuer: String,
    /** OIDC authorization endpoint (typically `<issuer>/authorize`). */
    val oidcAuthUrl: String,
    /** OIDC token endpoint (typically `<issuer>/token`). */
    val oidcTokenUrl: String,
    /** Public OIDC client ID registered with the IdP. */
    val oidcClientId: String,
    /**
     * OIDC client secret. Null/empty = PKCE-only public client. Non-null
     * = confidential client; secret is appended to the token-exchange body.
     *
     * step-ca's own model treats the secret as semi-public: it's published
     * via the unauthenticated `/provisioners` endpoint so the bootstrap
     * flow can fetch it. We persist it in plaintext for parity with
     * [rootCertPem] (also non-secret-by-design). Real-world deployments
     * with confidential-client IdPs (Authentik default, Keycloak, Okta)
     * require this to be set or the token endpoint returns `invalid_client`.
     */
    val oidcClientSecret: String? = null,
    /**
     * step-ca provisioner name — what shows up in `step ca provisioner list`.
     * Sent in the sign-ssh request alongside the OIDC ID token.
     */
    val provisioner: String,
    /**
     * Default SSH principals to request, comma-separated.
     * Empty string means "let step-ca infer from the OIDC token's email
     * local part" (the typical step-ca default).
     */
    val defaultPrincipals: String,
    /**
     * PEM-encoded root certificate of the CA. Used to pin TLS for both
     * the sign-ssh API call and the (optional) `/health` test-connection
     * call. Never the system trust store — step-ca CAs are usually
     * private and not anchored in any public chain.
     */
    val rootCertPem: String,
    /**
     * Optional OpenSSH wire-format public key of the SSH **host** CA
     * (separate from [rootCertPem], which is the X.509 TLS root). When
     * non-null, [sh.haven.core.ssh.HostKeyVerifier] will silently accept
     * a server presenting an `ssh-*-cert-v01@openssh.com` host cert
     * signed by this key, provided the cert is currently valid and the
     * hostname matches one of its principals — bypassing the TOFU
     * prompt. Null = behave as before (every cert-bearing host triggers
     * TOFU just like a raw key would). (#133 phase 2b)
     */
    val sshHostCaPublicKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StepCaConfig) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
