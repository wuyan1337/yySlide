package sh.haven.feature.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jcraft.jsch.JSchException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.ConnectionGroupDao
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.security.CredentialEncryption
import sh.haven.core.ssh.SshKeyImporter
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class YySlideCloudSessionState(
    val serverUrl: String = YySlideCloudRepository.DEFAULT_SERVER_URL,
    val username: String? = null,
    val loggedIn: Boolean = false,
    val lastSyncAt: String? = null,
)

data class YySlideCloudSyncResult(
    val importedConnections: Int = 0,
    val skippedConnections: Int = 0,
    val deletedConnections: Int = 0,
    val exportedConnections: Int = 0,
    val importedKeys: Int = 0,
    val skippedKeys: Int = 0,
    val conflictCopies: Int = 0,
) {
    fun summary(context: Context): String {
        val parts = mutableListOf(
            context.getString(R.string.yyslide_cloud_summary_imported_connections, importedConnections),
            context.getString(R.string.yyslide_cloud_summary_uploaded_connections, exportedConnections),
            context.getString(R.string.yyslide_cloud_summary_deleted_connections, deletedConnections),
            context.getString(R.string.yyslide_cloud_summary_skipped_connections, skippedConnections),
        )
        if (conflictCopies > 0) {
            parts += context.getString(R.string.yyslide_cloud_summary_conflict_copies, conflictCopies)
        }
        if (importedKeys > 0 || skippedKeys > 0) {
            parts += context.getString(R.string.yyslide_cloud_summary_imported_keys, importedKeys)
            parts += context.getString(R.string.yyslide_cloud_summary_skipped_keys, skippedKeys)
        }
        return parts.joinToString(context.getString(R.string.yyslide_cloud_summary_separator))
    }
}

private data class YySlideImportState(
    val versions: MutableMap<String, String>,
    val hashes: MutableMap<String, String>,
    val deletedConnections: MutableMap<String, String>,
    val groupVersions: MutableMap<String, String>,
    val groupHashes: MutableMap<String, String>,
    val deletedGroups: MutableMap<String, String>,
)

private data class YySlideExportResult(
    val snapshot: JSONObject,
    val exportedConnections: Int,
)

private class YySlideCloudHttpException(
    val status: Int,
    message: String,
) : IOException(message)

@Singleton
class YySlideCloudRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val connectionRepository: ConnectionRepository,
    private val connectionGroupDao: ConnectionGroupDao,
    private val sshKeyRepository: SshKeyRepository,
) {
    private val syncMutex = Mutex()

    val sessionState: Flow<YySlideCloudSessionState> = dataStore.data.map { prefs ->
        YySlideCloudSessionState(
            serverUrl = prefs[serverUrlKey] ?: DEFAULT_SERVER_URL,
            username = prefs[usernameKey],
            loggedIn = !prefs[tokenKey].isNullOrBlank() && !prefs[vaultKeyKey].isNullOrBlank(),
            lastSyncAt = prefs[lastSyncAtKey],
        )
    }

    suspend fun login(serverUrl: String, username: String, password: String, totpCode: String) {
        withContext(Dispatchers.IO) {
            val normalizedUrl = normalizeServerUrl(serverUrl)
            val loginJson = postJson(
                serverUrl = normalizedUrl,
                path = "/v1/auth/login",
                body = JSONObject()
                    .put("username", username.trim())
                    .put("password", password)
                    .put("totpCode", totpCode.trim()),
                token = null,
            )
            val token = loginJson.optString("token").takeIf { it.isNotBlank() }
                ?: throw IOException(context.getString(R.string.yyslide_cloud_error_missing_login_token))
            val wrappedVaultKey = loginJson.optJSONObject("wrappedVaultKey")
                ?: throw IOException(context.getString(R.string.yyslide_cloud_error_missing_vault_key))
            val vaultKey = try {
                YySlideCloudCrypto.unwrapVaultKey(wrappedVaultKey, password)
            } catch (e: GeneralSecurityException) {
                throw IOException(context.getString(R.string.yyslide_cloud_error_unlock_vault_key), e)
            }
            YySlideCloudCrypto.requireVaultKey(vaultKey)
            dataStore.edit { prefs ->
                prefs[serverUrlKey] = normalizedUrl
                prefs[usernameKey] = username.trim()
                prefs[tokenKey] = CredentialEncryption.encrypt(context, token)
                prefs[vaultKeyKey] = CredentialEncryption.encrypt(context, vaultKey)
                prefs.remove(importedVersionsKey)
                prefs.remove(importedHashesKey)
                prefs.remove(deletedConnectionsKey)
                prefs.remove(importedGroupVersionsKey)
                prefs.remove(importedGroupHashesKey)
                prefs.remove(deletedGroupsKey)
                prefs.remove(lastSyncAtKey)
            }
        }
    }

    suspend fun syncFromCloud(): YySlideCloudSyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        val session = readSessionSecrets()
        var lastConflict: YySlideCloudHttpException? = null

        for (attempt in 0 until 2) {
            val state = readImportState()
            val remote = getJson(session.serverUrl, "/v1/vault", session.token)
            val remoteVault = remote.optJSONObject("vault")
            val baseUpdatedAt = remoteVault?.optCleanString("updatedAt")
            val imported = if (remoteVault != null) {
                val snapshot = try {
                    YySlideCloudCrypto.decryptVaultPayload(session.vaultKey, remoteVault)
                } catch (e: GeneralSecurityException) {
                    throw IOException(context.getString(R.string.yyslide_cloud_error_decrypt_vault), e)
                }
                importSnapshot(snapshot, state)
            } else {
                YySlideCloudSyncResult()
            }
            storeImportState(state)

            val now = Instant.now().toString()
            val export = exportVaultSnapshot(state, now)
            val encryptedVault = YySlideCloudCrypto.encryptVaultPayload(session.vaultKey, export.snapshot)
            val body = JSONObject()
                .put("vault", encryptedVault)
                .put("baseUpdatedAt", baseUpdatedAt ?: JSONObject.NULL)

            try {
                putJson(session.serverUrl, "/v1/vault", body, session.token)
                storeImportState(state, now)
                return@withContext imported.copy(exportedConnections = export.exportedConnections)
            } catch (e: YySlideCloudHttpException) {
                if (e.status == 409 && attempt == 0) {
                    lastConflict = e
                    continue
                }
                throw e
            }
        }
        throw lastConflict ?: IOException(context.getString(R.string.yyslide_cloud_sync_failed))
    }

    }

    suspend fun pullFromCloud(): YySlideCloudSyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val session = readSessionSecrets()
            val state = readImportState()
            val remote = getJson(session.serverUrl, "/v1/vault", session.token)
            val remoteVault = remote.optJSONObject("vault")
            val result = if (remoteVault != null) {
                val snapshot = try {
                    YySlideCloudCrypto.decryptVaultPayload(session.vaultKey, remoteVault)
                } catch (e: GeneralSecurityException) {
                    throw IOException(context.getString(R.string.yyslide_cloud_error_decrypt_vault), e)
                }
                importSnapshot(snapshot, state)
            } else {
                YySlideCloudSyncResult()
            }
            storeImportState(state, Instant.now().toString())
            result
        }
    }

    suspend fun listenForVaultEvents(onRemoteChange: suspend () -> Unit) = withContext(Dispatchers.IO) {
        var retryDelayMs = 1000L
        while (coroutineContext.isActive) {
            val session = runCatching { readSessionSecrets() }.getOrNull()
            if (session == null) {
                delay(3000L)
                continue
            }
            try {
                openVaultEventStream(session, onRemoteChange)
                retryDelayMs = 1000L
            } catch (e: Exception) {
                if (!coroutineContext.isActive) {
                    break
                }
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(30000L)
            }
        }
    }

    suspend fun logout() {
        dataStore.edit { prefs ->
            prefs.remove(tokenKey)
            prefs.remove(vaultKeyKey)
            prefs.remove(usernameKey)
            prefs.remove(lastSyncAtKey)
            prefs.remove(importedVersionsKey)
            prefs.remove(importedHashesKey)
            prefs.remove(deletedConnectionsKey)
            prefs.remove(importedGroupVersionsKey)
            prefs.remove(importedGroupHashesKey)
            prefs.remove(deletedGroupsKey)
        }
    }

    suspend fun recordDeletedConnection(id: String) = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext
        val state = readImportState()
        val now = Instant.now().toString()
        val previousDeletedAt = state.deletedConnections[id]
        if (previousDeletedAt == null || timestampValue(previousDeletedAt) < timestampValue(now)) {
            state.deletedConnections[id] = now
        }
        state.versions.remove(id)
        state.hashes.remove(id)
        storeImportState(state)
    }

    suspend fun recordDeletedGroup(group: ConnectionGroup) = withContext(Dispatchers.IO) {
        val state = readImportState()
        val now = Instant.now().toString()
        listOf(deletedGroupIdKey(group.id), deletedGroupLabelKey(group.label)).forEach { key ->
            val previousDeletedAt = state.deletedGroups[key]
            if (previousDeletedAt == null || timestampValue(previousDeletedAt) < timestampValue(now)) {
                state.deletedGroups[key] = now
            }
        }
        state.groupVersions.remove(group.id)
        state.groupHashes.remove(group.id)
        storeImportState(state)
    }

    private suspend fun importSnapshot(
        snapshot: JSONObject,
        state: YySlideImportState,
    ): YySlideCloudSyncResult {
        val exportedAt = snapshot.optCleanString("exportedAt") ?: Instant.now().toString()
        val deletedById = latestDeletedMap(snapshot.optJSONArray("deletedConnections"))
        mergeLatest(state.deletedConnections, deletedById)
        val deletedGroupsByKey = latestDeletedGroupMap(snapshot.optJSONArray("deletedGroups"))
        mergeLatest(state.deletedGroups, deletedGroupsByKey)
        importGroupsFromSnapshot(snapshot.optJSONArray("groups"), exportedAt, deletedGroupsByKey, state)
        val groups = connectionGroupDao.getAll()
        val groupIdsByLabel = groups
            .associateBy { it.label.normalizedLabelKey() }
            .toMutableMap()
        val groupLabelsById = groups
            .associateBy({ it.id }, { it.label })
            .toMutableMap()
        var deletedCount = 0
        for ((id, deletedAt) in deletedById) {
            val importedAt = state.versions[id] ?: continue
            val existing = connectionRepository.getById(id)
            val localChanged = existing != null &&
                state.hashes[id] != null &&
                state.hashes[id] != connectionContentHash(existing, groupLabelsById[existing.groupId])
            if (localChanged) {
                continue
            }
            if (timestampValue(deletedAt) >= timestampValue(importedAt)) {
                connectionRepository.delete(id)
                state.versions.remove(id)
                state.hashes.remove(id)
                deletedCount += 1
            }
        }

        var nextGroupSortOrder = (groupIdsByLabel.values.maxOfOrNull { it.sortOrder } ?: 0) + 1

        var importedCount = 0
        var skippedCount = 0
        var importedKeyCount = 0
        var skippedKeyCount = 0
        var conflictCopyCount = 0
        val connections = snapshot.optJSONArray("connections") ?: JSONArray()
        for (index in 0 until connections.length()) {
            val remote = connections.optJSONObject(index) ?: continue
            val id = remote.optCleanString("id") ?: run {
                skippedCount += 1
                continue
            }
            val host = remote.optCleanString("host") ?: run {
                skippedCount += 1
                continue
            }
            val username = remote.optCleanString("username") ?: run {
                skippedCount += 1
                continue
            }
            val remoteUpdatedAt = remote.optCleanString("updatedAt") ?: exportedAt
            val deletedAt = state.deletedConnections[id]
            if (deletedAt != null && timestampValue(deletedAt) >= timestampValue(remoteUpdatedAt)) {
                skippedCount += 1
                continue
            }

            val existing = connectionRepository.getById(id)
            val lastImportedAt = state.versions[id]
            if (existing != null && lastImportedAt == null) {
                skippedCount += 1
                continue
            }
            val localChanged = existing != null &&
                lastImportedAt != null &&
                state.hashes[id] != null &&
                state.hashes[id] != connectionContentHash(existing, groupLabelsById[existing.groupId])
            if (existing != null && lastImportedAt != null &&
                timestampValue(remoteUpdatedAt) <= timestampValue(lastImportedAt)
            ) {
                skippedCount += 1
                continue
            }
            val importId = if (localChanged) {
                "yyslide-conflict-${sha256Hex("$id:$remoteUpdatedAt").take(24)}"
            } else {
                id
            }

            val groupId = remote.optCleanString("group")?.let { label ->
                val key = label.normalizedLabelKey()
                val existingGroup = groupIdsByLabel[key]
                if (existingGroup != null) {
                    existingGroup.id
                } else {
                    val group = ConnectionGroup(label = label, sortOrder = nextGroupSortOrder++)
                    connectionGroupDao.upsert(group)
                    groupIdsByLabel[key] = group
                    groupLabelsById[group.id] = group.label
                    group.id
                }
            }
            val groupLabel = groupId?.let { groupLabelsById[it] }

            val label = remote.optCleanString("name") ?: host
            val saveLabel = if (localChanged) {
                "$label ${context.getString(R.string.yyslide_cloud_conflict_label_suffix)}"
            } else {
                label
            }
            val authType = if (remote.optString("authType") == "key") {
                ConnectionProfile.AuthType.KEY
            } else {
                ConnectionProfile.AuthType.PASSWORD
            }
            val importedKeyId = if (authType == ConnectionProfile.AuthType.KEY) {
                val privateKey = remote.optRawString("privateKey")
                if (privateKey.isNullOrBlank()) {
                    skippedKeyCount += 1
                    null
                } else {
                    try {
                        importPrivateKey(importId, saveLabel, privateKey, remote.optRawString("passphrase"))
                            .also { importedKeyCount += 1 }
                    } catch (_: SshKeyImporter.EncryptedKeyException) {
                        skippedKeyCount += 1
                        null
                    } catch (_: JSchException) {
                        skippedKeyCount += 1
                        null
                    } catch (_: IllegalArgumentException) {
                        skippedKeyCount += 1
                        null
                    }
                }
            } else {
                null
            }
            val keyId = importedKeyId ?: existing?.keyId
            val password = remote.optRawString("password")?.takeIf { it.isNotBlank() }
            val authMethods = if (authType == ConnectionProfile.AuthType.KEY) {
                ConnectionProfile.AuthMethodSpec.serializeList(listOf(ConnectionProfile.AuthMethodSpec.Key(keyId)))
            } else {
                ConnectionProfile.AuthMethodSpec.serializeList(listOf(ConnectionProfile.AuthMethodSpec.Password))
            }

            val baseProfile = if (localChanged) null else existing
            val profile = (baseProfile ?: ConnectionProfile(
                id = importId,
                label = saveLabel,
                host = host,
                port = remote.optInt("port", 22).coerceIn(1, 65535),
                username = username,
            )).copy(
                label = saveLabel,
                host = host,
                port = remote.optInt("port", 22).coerceIn(1, 65535),
                username = username,
                sshPassword = if (authType == ConnectionProfile.AuthType.PASSWORD) {
                    password ?: baseProfile?.sshPassword
                } else {
                    null
                },
                authType = authType,
                keyId = keyId,
                authMethods = authMethods,
                connectionType = "SSH",
                groupId = groupId,
            )
            connectionRepository.save(profile)
            state.versions[importId] = remoteUpdatedAt
            state.hashes[importId] = connectionContentHash(profile, groupLabel)
            state.deletedConnections.remove(importId)
            importedCount += 1
            if (localChanged) {
                conflictCopyCount += 1
            }
        }

        return YySlideCloudSyncResult(
            importedConnections = importedCount,
            skippedConnections = skippedCount,
            deletedConnections = deletedCount,
            importedKeys = importedKeyCount,
            skippedKeys = skippedKeyCount,
            conflictCopies = conflictCopyCount,
        )
    }

    private suspend fun importGroupsFromSnapshot(
        remoteGroups: JSONArray?,
        exportedAt: String,
        remoteDeletedGroups: Map<String, String>,
        state: YySlideImportState,
    ) {
        val groupsById = connectionGroupDao.getAll().associateBy { it.id }.toMutableMap()
        val groupsByLabel = groupsById.values.associateBy { it.label.normalizedLabelKey() }.toMutableMap()

        fun findGroupForDeleteKey(key: String): ConnectionGroup? =
            when {
                key.startsWith(DELETED_GROUP_ID_PREFIX) -> groupsById[key.removePrefix(DELETED_GROUP_ID_PREFIX)]
                key.startsWith(DELETED_GROUP_LABEL_PREFIX) -> groupsByLabel[key.removePrefix(DELETED_GROUP_LABEL_PREFIX)]
                else -> null
            }

        fun canApplyDelete(group: ConnectionGroup, deletedAt: String): Boolean {
            val importedAt = state.groupVersions[group.id] ?: return false
            val importedHash = state.groupHashes[group.id]
            val localChanged = importedHash != null && importedHash != groupContentHash(group)
            return !localChanged && timestampValue(deletedAt) >= timestampValue(importedAt)
        }

        suspend fun removeGroup(group: ConnectionGroup) {
            connectionRepository.getAll()
                .filter { it.groupId == group.id }
                .forEach { profile -> connectionRepository.save(profile.copy(groupId = null)) }
            connectionGroupDao.deleteById(group.id)
            groupsById.remove(group.id)
            groupsByLabel.remove(group.label.normalizedLabelKey())
            state.groupVersions.remove(group.id)
            state.groupHashes.remove(group.id)
        }

        for ((key, deletedAt) in remoteDeletedGroups) {
            val group = findGroupForDeleteKey(key) ?: continue
            if (canApplyDelete(group, deletedAt)) {
                removeGroup(group)
            }
        }

        var nextGroupSortOrder = (groupsById.values.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val groups = remoteGroups ?: JSONArray()
        for (index in 0 until groups.length()) {
            val remote = groups.optJSONObject(index) ?: continue
            val label = remote.optCleanString("label") ?: remote.optCleanString("name") ?: continue
            val remoteId = remote.optCleanString("id") ?: UUID.randomUUID().toString()
            val remoteUpdatedAt = remote.optCleanString("updatedAt") ?: exportedAt
            val deletedAt = latestTimestamp(
                state.deletedGroups[deletedGroupIdKey(remoteId)],
                state.deletedGroups[deletedGroupLabelKey(label)],
            )
            val labelKey = label.normalizedLabelKey()
            val existing = groupsById[remoteId] ?: groupsByLabel[labelKey]
            if (deletedAt != null && timestampValue(deletedAt) >= timestampValue(remoteUpdatedAt)) {
                if (existing != null && canApplyDelete(existing, deletedAt)) {
                    removeGroup(existing)
                }
                continue
            }

            val lastImportedAt = existing?.let { state.groupVersions[it.id] }
            if (existing != null && lastImportedAt != null) {
                val importedHash = state.groupHashes[existing.id]
                val localChanged = importedHash != null && importedHash != groupContentHash(existing)
                if (localChanged || timestampValue(remoteUpdatedAt) <= timestampValue(lastImportedAt)) {
                    continue
                }
            }

            val previousLabelKey = existing?.label?.normalizedLabelKey()
            val sortOrder = if (remote.has("sortOrder")) {
                remote.optInt("sortOrder")
            } else {
                existing?.sortOrder ?: nextGroupSortOrder++
            }
            val group = (existing ?: ConnectionGroup(
                id = remoteId,
                label = label,
                sortOrder = sortOrder,
            )).copy(
                label = label,
                colorTag = remote.optInt("colorTag", existing?.colorTag ?: 0),
                sortOrder = sortOrder,
            )
            connectionGroupDao.upsert(group)
            groupsById[group.id] = group
            previousLabelKey?.takeIf { it != labelKey }?.let { groupsByLabel.remove(it) }
            groupsByLabel[labelKey] = group
            state.groupVersions[group.id] = remoteUpdatedAt
            state.groupHashes[group.id] = groupContentHash(group)
            state.deletedGroups.remove(deletedGroupIdKey(remoteId))
            state.deletedGroups.remove(deletedGroupIdKey(group.id))
            state.deletedGroups.remove(deletedGroupLabelKey(label))
            existing?.label?.let { state.deletedGroups.remove(deletedGroupLabelKey(it)) }
        }
    }

    private suspend fun importPrivateKey(
        connectionId: String,
        label: String,
        privateKey: String,
        passphrase: String?,
    ): String {
        val imported = SshKeyImporter.import(
            fileBytes = privateKey.toByteArray(Charsets.UTF_8),
            passphrase = passphrase?.takeIf { it.isNotBlank() },
        )
        val keyId = "yyslide-${sha256Hex(connectionId).take(24)}"
        sshKeyRepository.save(
            SshKey(
                id = keyId,
                label = "yySlide: $label",
                keyType = imported.keyType,
                privateKeyBytes = imported.privateKeyBytes,
                publicKeyOpenSsh = imported.publicKeyOpenSsh,
                fingerprintSha256 = imported.fingerprintSha256,
                isEncrypted = imported.isEncrypted,
            ),
        )
        return keyId
    }

    private suspend fun exportVaultSnapshot(
        state: YySlideImportState,
        now: String,
    ): YySlideExportResult {
        val groups = connectionGroupDao.getAll()
        val groupLabelsById = groups.associateBy({ it.id }, { it.label })
        val profiles = connectionRepository.getAll().filter { it.isSsh }
        val localIds = profiles.map { it.id }.toSet()
        val localGroupIds = groups.map { it.id }.toSet()

        for ((id, importedAt) in state.versions.toMap()) {
            if (id !in localIds) {
                val existingDeletedAt = state.deletedConnections[id]
                if (existingDeletedAt == null || timestampValue(existingDeletedAt) < timestampValue(importedAt)) {
                    state.deletedConnections[id] = now
                }
                state.versions.remove(id)
                state.hashes.remove(id)
            }
        }

        for ((id, importedAt) in state.groupVersions.toMap()) {
            if (id !in localGroupIds) {
                val key = deletedGroupIdKey(id)
                val existingDeletedAt = state.deletedGroups[key]
                if (existingDeletedAt == null || timestampValue(existingDeletedAt) < timestampValue(importedAt)) {
                    state.deletedGroups[key] = now
                }
                state.groupVersions.remove(id)
                state.groupHashes.remove(id)
            }
        }

        val exportedGroups = JSONArray()
        for (group in groups) {
            val contentHash = groupContentHash(group)
            val importedVersion = state.groupVersions[group.id]
            val importedHash = state.groupHashes[group.id]
            val updatedAt = if (importedVersion != null && (importedHash == null || importedHash == contentHash)) {
                importedVersion
            } else {
                now
            }
            exportedGroups.put(exportConnectionGroup(group, updatedAt))
            state.groupVersions[group.id] = updatedAt
            state.groupHashes[group.id] = contentHash
            state.deletedGroups.remove(deletedGroupIdKey(group.id))
            state.deletedGroups.remove(deletedGroupLabelKey(group.label))
        }

        val keysById = sshKeyRepository.getAllDecrypted().associateBy { it.id }
        val exportedConnections = JSONArray()
        for (profile in profiles) {
            val groupLabel = groupLabelsById[profile.groupId]
            val contentHash = connectionContentHash(profile, groupLabel)
            val importedVersion = state.versions[profile.id]
            val importedHash = state.hashes[profile.id]
            val updatedAt = if (importedVersion != null && (importedHash == null || importedHash == contentHash)) {
                importedVersion
            } else {
                now
            }
            exportedConnections.put(exportConnection(profile, groupLabel, keysById[profile.keyId], updatedAt))
            state.versions[profile.id] = updatedAt
            state.hashes[profile.id] = contentHash
            state.deletedConnections.remove(profile.id)
        }

        normalizeDeletedConnections(state.deletedConnections).also { normalized ->
            state.deletedConnections.clear()
            state.deletedConnections.putAll(normalized)
        }
        val deletedConnections = JSONArray()
        for ((id, deletedAt) in state.deletedConnections) {
            deletedConnections.put(JSONObject().put("id", id).put("deletedAt", deletedAt))
        }

        normalizeDeletedGroups(state.deletedGroups).also { normalized ->
            state.deletedGroups.clear()
            state.deletedGroups.putAll(normalized)
        }
        val deletedGroups = JSONArray()
        for ((key, deletedAt) in state.deletedGroups) {
            deletedGroups.put(deletedGroupToJson(key, deletedAt))
        }

        val snapshot = JSONObject()
            .put("version", 3)
            .put("exportedAt", now)
            .put("groups", exportedGroups)
            .put("connections", exportedConnections)
            .put("deletedConnections", deletedConnections)
            .put("deletedGroups", deletedGroups)

        return YySlideExportResult(snapshot, exportedConnections.length())
    }

    private fun exportConnectionGroup(group: ConnectionGroup, updatedAt: String): JSONObject =
        JSONObject()
            .put("id", group.id)
            .put("label", group.label)
            .put("sortOrder", group.sortOrder)
            .put("colorTag", group.colorTag)
            .put("updatedAt", updatedAt)

    private fun exportConnection(
        profile: ConnectionProfile,
        groupLabel: String?,
        key: SshKey?,
        updatedAt: String,
    ): JSONObject {
        val authType = if (profile.authType == ConnectionProfile.AuthType.KEY) "key" else "password"
        val json = JSONObject()
            .put("id", profile.id)
            .put("name", profile.label)
            .put("host", profile.host)
            .put("port", profile.port)
            .put("username", profile.username)
            .put("authType", authType)
            .put("group", groupLabel ?: "")
            .put("updatedAt", updatedAt)

        if (authType == "password") {
            json.put("password", profile.sshPassword ?: "")
        } else {
            key?.let {
                json.put("privateKey", it.privateKeyBytes.toString(Charsets.UTF_8))
            }
            json.put("passphrase", "")
        }
        return json
    }

    private suspend fun readSessionSecrets(): SessionSecrets {
        val prefs = dataStore.data.first()
        val serverUrl = prefs[serverUrlKey] ?: DEFAULT_SERVER_URL
        val token = prefs[tokenKey]?.let { CredentialEncryption.decrypt(context, it) }
            ?: throw IllegalStateException(context.getString(R.string.yyslide_cloud_error_not_signed_in))
        val vaultKey = prefs[vaultKeyKey]?.let { CredentialEncryption.decrypt(context, it) }
            ?: throw IllegalStateException(context.getString(R.string.yyslide_cloud_error_not_signed_in))
        return SessionSecrets(serverUrl, token, vaultKey)
    }

    private suspend fun readImportState(): YySlideImportState {
        val prefs = dataStore.data.first()
        return YySlideImportState(
            versions = parseStringMap(prefs[importedVersionsKey]),
            hashes = parseStringMap(prefs[importedHashesKey]),
            deletedConnections = parseStringMap(prefs[deletedConnectionsKey]),
            groupVersions = parseStringMap(prefs[importedGroupVersionsKey]),
            groupHashes = parseStringMap(prefs[importedGroupHashesKey]),
            deletedGroups = parseStringMap(prefs[deletedGroupsKey]),
        )
    }

    private suspend fun storeImportState(state: YySlideImportState, lastSyncAt: String? = null) {
        dataStore.edit { prefs ->
            prefs[importedVersionsKey] = encodeStringMap(state.versions)
            prefs[importedHashesKey] = encodeStringMap(state.hashes)
            prefs[deletedConnectionsKey] = encodeStringMap(state.deletedConnections)
            prefs[importedGroupVersionsKey] = encodeStringMap(state.groupVersions)
            prefs[importedGroupHashesKey] = encodeStringMap(state.groupHashes)
            prefs[deletedGroupsKey] = encodeStringMap(state.deletedGroups)
            if (lastSyncAt != null) {
                prefs[lastSyncAtKey] = lastSyncAt
            }
        }
    }

    private fun getJson(serverUrl: String, path: String, token: String): JSONObject =
        requestJson(serverUrl, "GET", path, null, token)

    private fun putJson(serverUrl: String, path: String, body: JSONObject, token: String): JSONObject =
        requestJson(serverUrl, "PUT", path, body, token)

    private fun postJson(serverUrl: String, path: String, body: JSONObject, token: String?): JSONObject =
        requestJson(serverUrl, "POST", path, body, token)

    private fun requestJson(
        serverUrl: String,
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
    ): JSONObject {
        val builder = Request.Builder()
            .url("$serverUrl$path")
            .method(method, body?.toString()?.toRequestBody(JSON_MEDIA_TYPE))
            .header(CLIENT_ID_HEADER, clientId)
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }.getOrElse {
                JSONObject().put("error", text.ifBlank { "HTTP ${response.code}" })
            }
            if (!response.isSuccessful) {
                throw YySlideCloudHttpException(
                    response.code,
                    json.optString("error").ifBlank {
                        context.getString(R.string.yyslide_cloud_error_request_failed, response.code)
                    },
                )
            }
            return json
        }
    }

    private suspend fun openVaultEventStream(
        session: SessionSecrets,
        onRemoteChange: suspend () -> Unit,
    ) {
        val request = Request.Builder()
            .url("${session.serverUrl}/v1/vault/events")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${session.token}")
            .header(CLIENT_ID_HEADER, clientId)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw YySlideCloudHttpException(
                    response.code,
                    "yySlide Cloud event stream failed: ${response.code}",
                )
            }
            val source = response.body?.source()
                ?: throw IOException("yySlide Cloud event stream is empty")
            val frameLines = mutableListOf<String>()
            while (coroutineContext.isActive) {
                val line = source.readUtf8Line()?.trimEnd('\r') ?: break
                if (line.isEmpty()) {
                    handleVaultEventFrame(frameLines, onRemoteChange)
                    frameLines.clear()
                } else {
                    frameLines += line
                }
            }
        }
    }

    private suspend fun handleVaultEventFrame(
        lines: List<String>,
        onRemoteChange: suspend () -> Unit,
    ) {
        if (lines.isEmpty()) return
        var event = "message"
        val data = StringBuilder()
        for (line in lines) {
            when {
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
            }
        }
        if (event != "vault.updated" || data.isBlank()) {
            return
        }
        val payload = runCatching { JSONObject(data.toString()) }.getOrNull() ?: return
        if (payload.optString("sourceClientId") == clientId) {
            return
        }
        onRemoteChange()
    }

    private data class SessionSecrets(val serverUrl: String, val token: String, val vaultKey: String)

    companion object {
        const val DEFAULT_SERVER_URL = "https://yyslide.ccwu.cc"

        private const val CLIENT_ID_HEADER = "X-yySlide-Client-Id"
        private val clientId = "android-${UUID.randomUUID()}"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val serverUrlKey = stringPreferencesKey("yyslide_cloud_server_url")
        private val usernameKey = stringPreferencesKey("yyslide_cloud_username")
        private val tokenKey = stringPreferencesKey("yyslide_cloud_token")
        private val vaultKeyKey = stringPreferencesKey("yyslide_cloud_vault_key")
        private val lastSyncAtKey = stringPreferencesKey("yyslide_cloud_last_sync_at")
        private val importedVersionsKey = stringPreferencesKey("yyslide_cloud_imported_connection_versions")
        private val importedHashesKey = stringPreferencesKey("yyslide_cloud_imported_connection_hashes")
        private val deletedConnectionsKey = stringPreferencesKey("yyslide_cloud_deleted_connections")
        private val importedGroupVersionsKey = stringPreferencesKey("yyslide_cloud_imported_group_versions")
        private val importedGroupHashesKey = stringPreferencesKey("yyslide_cloud_imported_group_hashes")
        private val deletedGroupsKey = stringPreferencesKey("yyslide_cloud_deleted_groups")

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun normalizeServerUrl(serverUrl: String): String {
            val raw = serverUrl.trim().ifBlank { DEFAULT_SERVER_URL }
            val withScheme = if (
                raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            ) {
                raw
            } else {
                "https://$raw"
            }
            val url = withScheme.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Enter a valid yySlide Cloud server URL")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) {
                throw IllegalArgumentException("Server URL must not include credentials, a query, or a fragment")
            }
            val cleartextLoopback = url.host == "127.0.0.1" || url.host == "localhost"
            if (url.scheme == "http" && !cleartextLoopback) {
                throw IllegalArgumentException("yySlide Cloud requires HTTPS except for localhost")
            }
            return url.toString().trimEnd('/')
        }
    }
}

private object YySlideCloudCrypto {
    private const val KEY_ITERATIONS = 310000
    private const val KEY_BITS = 256

    fun unwrapVaultKey(wrappedVaultKey: JSONObject, password: String): String {
        val iterations = wrappedVaultKey.optInt("iterations", KEY_ITERATIONS)
        val key = pbkdf2(password, decodeBase64(wrappedVaultKey.getString("salt")), iterations)
        return aesGcmDecrypt(key, wrappedVaultKey, "yySlide:vault-key")
    }

    fun decryptVaultPayload(vaultKey: String, vault: JSONObject): JSONObject {
        val key = decodeBase64(vaultKey)
        val plaintext = aesGcmDecrypt(key, vault, "yySlide:vault")
        return JSONObject(plaintext)
    }

    fun encryptVaultPayload(vaultKey: String, snapshot: JSONObject): JSONObject {
        val key = decodeBase64(vaultKey)
        return aesGcmEncrypt(key, snapshot.toString(), "yySlide:vault")
            .put("version", 1)
    }

    fun requireVaultKey(vaultKey: String) {
        if (decodeBase64(vaultKey).size != 32) {
            throw GeneralSecurityException("Vault key length is invalid")
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun aesGcmEncrypt(key: ByteArray, plaintext: String, aad: String): JSONObject {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - 16)
        val tag = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
        return JSONObject()
            .put("alg", "AES-256-GCM")
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .put("tag", Base64.encodeToString(tag, Base64.NO_WRAP))
    }

    private fun aesGcmDecrypt(key: ByteArray, box: JSONObject, aad: String): String {
        val ciphertext = decodeBase64(box.getString("ciphertext"))
        val tag = decodeBase64(box.getString("tag"))
        val encrypted = ciphertext + tag
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, decodeBase64(box.getString("iv"))))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun decodeBase64(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)
}

private fun JSONObject.optCleanString(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.optRawString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun String.normalizedLabelKey(): String = trim().lowercase(Locale.ROOT)

private const val DELETED_GROUP_ID_PREFIX = "id:"
private const val DELETED_GROUP_LABEL_PREFIX = "label:"

private fun connectionContentHash(profile: ConnectionProfile, groupLabel: String?): String =
    sha256Hex(
        listOf(
            profile.id,
            profile.label,
            profile.host,
            profile.port.toString(),
            profile.username,
            profile.authType.name,
            profile.keyId.orEmpty(),
            if (profile.authType == ConnectionProfile.AuthType.PASSWORD) profile.sshPassword.orEmpty() else "",
            groupLabel.orEmpty(),
        ).joinToString("\u001f") { it.replace("\u001f", "\\u001f") },
    )

private fun groupContentHash(group: ConnectionGroup): String =
    sha256Hex(
        listOf(
            group.label,
            group.colorTag.toString(),
            group.sortOrder.toString(),
        ).joinToString("\u001f") { it.replace("\u001f", "\\u001f") },
    )

private fun mergeLatest(target: MutableMap<String, String>, source: Map<String, String>) {
    for ((id, timestamp) in source) {
        val existing = target[id]
        if (existing == null || timestampValue(timestamp) > timestampValue(existing)) {
            target[id] = timestamp
        }
    }
}

private fun normalizeDeletedConnections(items: Map<String, String>): Map<String, String> =
    items.entries
        .filter { it.key.isNotBlank() }
        .sortedBy { timestampValue(it.value) }
        .takeLast(1000)
        .associate { it.key to it.value }

private fun normalizeDeletedGroups(items: Map<String, String>): Map<String, String> =
    items.entries
        .filter { it.key.startsWith(DELETED_GROUP_ID_PREFIX) || it.key.startsWith(DELETED_GROUP_LABEL_PREFIX) }
        .sortedBy { timestampValue(it.value) }
        .takeLast(1000)
        .associate { it.key to it.value }

private fun latestDeletedMap(items: JSONArray?): Map<String, String> {
    val result = mutableMapOf<String, String>()
    if (items == null) return result
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val id = item.optCleanString("id") ?: continue
        val deletedAt = item.optCleanString("deletedAt") ?: Instant.now().toString()
        val existing = result[id]
        if (existing == null || timestampValue(deletedAt) > timestampValue(existing)) {
            result[id] = deletedAt
        }
    }
    return result
}

private fun latestDeletedGroupMap(items: JSONArray?): Map<String, String> {
    val result = mutableMapOf<String, String>()
    if (items == null) return result
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val deletedAt = item.optCleanString("deletedAt") ?: Instant.now().toString()
        val keys = listOfNotNull(
            item.optCleanString("id")?.let(::deletedGroupIdKey),
            item.optCleanString("label")?.let(::deletedGroupLabelKey),
        )
        for (key in keys) {
            val existing = result[key]
            if (existing == null || timestampValue(deletedAt) > timestampValue(existing)) {
                result[key] = deletedAt
            }
        }
    }
    return result
}

private fun deletedGroupIdKey(id: String): String = "$DELETED_GROUP_ID_PREFIX${id.trim()}"

private fun deletedGroupLabelKey(label: String): String = "$DELETED_GROUP_LABEL_PREFIX${label.normalizedLabelKey()}"

private fun deletedGroupToJson(key: String, deletedAt: String): JSONObject {
    val json = JSONObject().put("deletedAt", deletedAt)
    when {
        key.startsWith(DELETED_GROUP_ID_PREFIX) -> json.put("id", key.removePrefix(DELETED_GROUP_ID_PREFIX))
        key.startsWith(DELETED_GROUP_LABEL_PREFIX) -> json.put("label", key.removePrefix(DELETED_GROUP_LABEL_PREFIX))
    }
    return json
}

private fun latestTimestamp(first: String?, second: String?): String? =
    when {
        first == null -> second
        second == null -> first
        timestampValue(first) >= timestampValue(second) -> first
        else -> second
    }

private fun timestampValue(value: String?): Long =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

private fun parseStringMap(jsonText: String?): MutableMap<String, String> {
    val result = mutableMapOf<String, String>()
    val obj = JSONObject(jsonText.takeUnless { it.isNullOrBlank() } ?: "{}")
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = obj.optString(key)
    }
    return result
}

private fun encodeStringMap(map: Map<String, String>): String = JSONObject(map).toString()

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
