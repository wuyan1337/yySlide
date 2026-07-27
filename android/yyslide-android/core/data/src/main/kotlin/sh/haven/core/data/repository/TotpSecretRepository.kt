package sh.haven.core.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.haven.core.data.db.TotpSecretDao
import sh.haven.core.data.db.entities.TotpSecret
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TotpSecretRepository"

/**
 * CRUD for [TotpSecret] rows, encrypting the base32 [TotpSecret.secret]
 * at rest with [CredentialEncryption] (the same `ENC:`+Base64 envelope
 * used for profile passwords). Plaintext secrets exist only in memory;
 * callers see decrypted values via [getDecryptedSecret] / [observeAll].
 */
@Singleton
class TotpSecretRepository @Inject constructor(
    private val totpSecretDao: TotpSecretDao,
    @ApplicationContext private val context: Context,
) {
    /** Observe all secrets with [TotpSecret.secret] decrypted for in-app display/use. */
    fun observeAll(): Flow<List<TotpSecret>> =
        totpSecretDao.observeAll().map { list -> list.map { decrypt(it) } }

    suspend fun getAll(): List<TotpSecret> = totpSecretDao.getAll().map { decrypt(it) }

    suspend fun getById(id: String): TotpSecret? = totpSecretDao.getById(id)?.let { decrypt(it) }

    /** Plaintext base32 secret for [id], or null if missing / undecryptable. */
    suspend fun getDecryptedSecret(id: String): String? =
        totpSecretDao.getById(id)?.let { decryptValue(it.secret) }

    suspend fun save(secret: TotpSecret) {
        totpSecretDao.upsert(secret.copy(secret = CredentialEncryption.encrypt(context, secret.secret)))
    }

    suspend fun delete(id: String) = totpSecretDao.deleteById(id)

    private fun decrypt(row: TotpSecret): TotpSecret =
        row.copy(secret = decryptValue(row.secret) ?: row.secret)

    private fun decryptValue(stored: String): String? = try {
        CredentialEncryption.decrypt(context, stored)
    } catch (e: Exception) {
        Log.w(TAG, "TOTP secret decrypt failed: ${e.message}")
        null
    }
}
