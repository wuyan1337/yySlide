package sh.haven.core.stepca

import sh.haven.core.data.db.entities.StepCaConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one full mint: OIDC handshake → /sign-ssh → return cert.
 *
 * Keypair generation is the caller's job (existing `SshKeyGenerator` in
 * core/security covers it). Splitting that out keeps this module free
 * of the BouncyCastle dep and lets the caller decide what KeyType to
 * use — step-ca signs whatever public key you hand it.
 */
@Singleton
class StepCaSignFlow @Inject constructor(
    private val oidcAuthClient: OidcAuthClient,
    private val apiClient: StepCaApiClient,
) {

    /**
     * @param publicKeyOpenSsh OpenSSH single-line public key, e.g.
     *   `ssh-ed25519 AAAAC3... user@host`. The caller is expected to
     *   have generated this locally and to retain the matching private
     *   key for storage alongside the returned cert.
     * @param keyLabel user-visible label for the new key — used to build
     *   the cert `keyID` field so the CA's audit log is meaningful.
     * @param principalsOverride if non-null, replaces
     *   [StepCaConfig.defaultPrincipals] for this one mint.
     */
    suspend fun run(
        caConfig: StepCaConfig,
        publicKeyOpenSsh: String,
        keyLabel: String,
        principalsOverride: List<String>? = null,
    ): Result {
        val idToken = try {
            oidcAuthClient.authorize(caConfig)
        } catch (e: Throwable) {
            return Result.Failure("OIDC sign-in failed: ${e.message}")
        }
        val keyId = KeyIdBuilder.build(keyLabel)
        return when (val signed = apiClient.signSsh(
            caConfig = caConfig,
            idToken = idToken,
            publicKeyOpenSsh = publicKeyOpenSsh,
            keyId = keyId,
            principalsOverride = principalsOverride,
        )) {
            is StepCaApiClient.SignSshResult.Success -> Result.Success(signed.certBytes)
            is StepCaApiClient.SignSshResult.Failure -> Result.Failure(signed.message)
        }
    }

    sealed interface Result {
        data class Success(val certBytes: ByteArray) : Result {
            override fun equals(other: Any?): Boolean =
                other is Success && certBytes.contentEquals(other.certBytes)

            override fun hashCode(): Int = certBytes.contentHashCode()
        }
        data class Failure(val message: String) : Result
    }
}
