package sh.haven.core.data.keystore

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.data.db.TotpSecretDao
import sh.haven.core.data.db.entities.TotpSecret
import sh.haven.core.security.CredentialEncryption
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TotpSecretSection"

/**
 * [KeystoreSection] over the `totp_secrets` table (#178). Surfaces each
 * stored TOTP secret in the security audit screen the same way SSH keys
 * and profile passwords appear — metadata only ([enumerate] never
 * exposes the base32 secret); [fetch] decrypts on demand.
 */
@Singleton
class TotpSecretSection @Inject constructor(
    private val totpSecretDao: TotpSecretDao,
    @ApplicationContext private val appContext: Context,
) : KeystoreSection {

    override val store: KeystoreStore = KeystoreStore.TOTP_SECRETS

    override suspend fun enumerate(): List<KeystoreEntry> =
        totpSecretDao.getAll().map { toEntry(it) }

    override suspend fun wipe(entryId: String): Boolean {
        val existed = totpSecretDao.getById(entryId) != null
        if (existed) totpSecretDao.deleteById(entryId)
        return existed
    }

    override suspend fun fetch(entryId: String): KeystoreFetch {
        val row = totpSecretDao.getById(entryId) ?: return KeystoreFetch.NotFound
        return try {
            val plain = if (CredentialEncryption.isEncrypted(row.secret)) {
                CredentialEncryption.decrypt(appContext, row.secret)
            } else {
                row.secret
            }
            KeystoreFetch.Password(plain)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $entryId: ${e.message}")
            KeystoreFetch.Failed("Decryption failed")
        }
    }

    private fun toEntry(row: TotpSecret): KeystoreEntry {
        val flags = mutableSetOf<KeystoreFlag>()
        if (CredentialEncryption.isEncrypted(row.secret)) flags.add(KeystoreFlag.HARDWARE_BACKED)
        return KeystoreEntry(
            id = row.id,
            store = KeystoreStore.TOTP_SECRETS,
            keyKind = KeyKind.TOTP_SECRET,
            label = row.label,
            algorithm = "TOTP-${row.algorithm}",
            publicMaterial = null,
            fingerprint = null,
            createdAt = row.createdAt,
            flags = flags,
        )
    }
}
