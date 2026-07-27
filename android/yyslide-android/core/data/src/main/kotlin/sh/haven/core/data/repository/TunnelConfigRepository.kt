package sh.haven.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.TunnelConfigDao
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.security.KeyEncryption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores and retrieves [TunnelConfig]s, encrypting [TunnelConfig.configText]
 * at rest. Mirrors [SshKeyRepository] — same Android keystore–backed
 * encryption, same legacy-passthrough for unencrypted blobs.
 */
@Singleton
class TunnelConfigRepository @Inject constructor(
    private val tunnelConfigDao: TunnelConfigDao,
    @ApplicationContext private val context: Context,
) {
    fun observeAll(): Flow<List<TunnelConfig>> = tunnelConfigDao.observeAll()

    /**
     * Observe only standalone tunnels (those without an owning profile).
     * The Tunnels screen uses this; embedded Cloudflare Tunnels live
     * inside SSH profile editors instead. See GH #154.
     */
    fun observeStandalone(): Flow<List<TunnelConfig>> = tunnelConfigDao.observeStandalone()

    suspend fun getAll(): List<TunnelConfig> = tunnelConfigDao.getAll()

    /**
     * Return every stored config with [TunnelConfig.configText] decrypted.
     * Used by the backup export path — the backup file has its own encryption
     * layer, so the DB keystore wrap is unpeeled here.
     */
    suspend fun getAllDecrypted(): List<TunnelConfig> = tunnelConfigDao.getAll().map { row ->
        if (KeyEncryption.isEncrypted(row.configText)) {
            row.copy(configText = KeyEncryption.decrypt(context, row.configText))
        } else {
            row
        }
    }

    /** Return the decrypted config, or null if no row. */
    suspend fun getById(id: String): TunnelConfig? {
        val row = tunnelConfigDao.getById(id) ?: return null
        return if (KeyEncryption.isEncrypted(row.configText)) {
            row.copy(configText = KeyEncryption.decrypt(context, row.configText))
        } else {
            row
        }
    }

    /** Save a config, encrypting [TunnelConfig.configText] before persistence. */
    suspend fun save(config: TunnelConfig) {
        tunnelConfigDao.upsert(
            config.copy(configText = KeyEncryption.encrypt(context, config.configText)),
        )
    }

    suspend fun delete(id: String) = tunnelConfigDao.deleteById(id)

    /**
     * Return the decrypted embedded tunnel owned by [profileId], or null if
     * none exists. Used by the SSH profile editor to preload Cloudflare
     * Tunnel transport fields when reopening an existing profile.
     */
    suspend fun findByOwner(profileId: String): TunnelConfig? {
        val row = tunnelConfigDao.findByOwner(profileId) ?: return null
        return if (KeyEncryption.isEncrypted(row.configText)) {
            row.copy(configText = KeyEncryption.decrypt(context, row.configText))
        } else {
            row
        }
    }

    /**
     * Upsert an embedded tunnel for [profileId], reusing the existing row's
     * id (and createdAt) when present so the encryption-at-rest payload
     * stays addressable by [ConnectionProfile.tunnelConfigId] across edits.
     * Returns the id of the persisted row.
     */
    suspend fun upsertEmbedded(profileId: String, type: String, label: String, configText: ByteArray): String {
        val existing = tunnelConfigDao.findByOwner(profileId)
        val row = TunnelConfig(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            label = label,
            type = type,
            configText = KeyEncryption.encrypt(context, configText),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            ownerProfileId = profileId,
        )
        tunnelConfigDao.upsert(row)
        return row.id
    }

    suspend fun deleteByOwner(profileId: String) = tunnelConfigDao.deleteByOwner(profileId)
}
