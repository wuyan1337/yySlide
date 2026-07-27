package sh.haven.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.data.repository.StepCaConfigRepository
import sh.haven.core.fido.SkKeyData
import sh.haven.core.fido.SkKeyParser
import sh.haven.core.security.Keystore
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreStore
import sh.haven.core.security.SshKeyGenerator
import sh.haven.core.ssh.SshCertificateParser
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshKeyImporter
import sh.haven.core.stepca.StepCaSignFlow
import javax.inject.Inject

/**
 * One row in the discovery picker after [KeysViewModel.discoverFromSecurityKey]
 * completes. Carries enough to render and identify each candidate, plus
 * the underlying [SkKeyData] to persist on selection.
 */
data class DiscoveredSkCredential(
    val id: String,
    val rpId: String,
    val algorithmName: String,
    val fingerprint: String,
    val data: SkKeyData,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KeysViewModel @Inject constructor(
    private val repository: SshKeyRepository,
    private val connectionRepository: ConnectionRepository,
    private val keystore: Keystore,
    private val stepCaConfigRepository: StepCaConfigRepository,
    private val stepCaSignFlow: StepCaSignFlow,
    private val fidoAuthenticator: sh.haven.core.fido.FidoAuthenticator,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val barcodeDecoder: sh.haven.core.scan.BarcodeDecoder,
    agentUiCommandBus: AgentUiCommandBus,
) : ViewModel() {

    init {
        // Internal UI commands stage the same confirmation used by direct
        // taps. No command is allowed to rotate key material immediately.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                if (command is AgentUiCommand.RegenerateStepCaCert) {
                    requestRegenerateViaStepCa(command.keyId)
                }
            }
        }
    }

    /** Registered step-ca CAs (#133 phase 2). The Keys "Generate via
     *  step-ca" affordance disables itself when this is empty and links
     *  the user to Settings. */
    val stepCaConfigs: StateFlow<List<StepCaConfig>> = stepCaConfigRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val keys: StateFlow<List<SshKey>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Per-key audit metadata indexed by [SshKey.id]. Refreshed whenever
     * the underlying key list changes (insert / delete / passphrase
     * toggle / biometric toggle). The Keys screen looks up flags +
     * KeyKind from here while still rendering the [SshKey] row's
     * key-specific actions (copy public, export private).
     */
    private val refreshTicker = MutableStateFlow(0L)

    val keyEntries: StateFlow<Map<String, KeystoreEntry>> = combine(
        repository.observeAll(),
        refreshTicker,
    ) { _, _ -> Unit }
        .flatMapLatest {
            flow {
                emit(
                    keystore.enumerate()
                        .filter { it.store == KeystoreStore.SSH_KEYS }
                        .associateBy { it.id },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Profile-password entries. Sourced from [Keystore.enumerate] for
     * [KeystoreStore.PROFILE_CREDENTIALS] — gives us the same audit
     * metadata as SSH keys (HARDWARE_BACKED chip, plaintext detection,
     * fingerprint-shaped id) without surfacing the actual password
     * value.
     */
    val passwordEntries: StateFlow<List<KeystoreEntry>> = combine(
        connectionRepository.observeAll(),
        refreshTicker,
    ) { _, _ -> Unit }
        .flatMapLatest {
            flow {
                emit(
                    keystore.enumerate()
                        .filter { it.store == KeystoreStore.PROFILE_CREDENTIALS },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Toggle the BIOMETRIC_PROTECTED flag on an SSH key. Refreshes the
     * audit-metadata flow on success so the screen reflects the change.
     */
    fun setBiometricProtected(keyId: String, protected: Boolean) {
        viewModelScope.launch {
            val ok = keystore.setBiometricProtected(KeystoreStore.SSH_KEYS, keyId, protected)
            if (ok) refreshTicker.value = System.nanoTime()
        }
    }

    /** Wipe a stored profile password (clears the column without removing the profile). */
    fun wipePasswordEntry(entry: KeystoreEntry) {
        viewModelScope.launch {
            val ok = keystore.wipe(entry.store, entry.id)
            if (ok) {
                refreshTicker.value = System.nanoTime()
                _message.value = "Cleared ${entry.label}"
            } else {
                _error.value = "Could not clear ${entry.label}"
            }
        }
    }

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _pendingRegenerationKeyId = MutableStateFlow<String?>(null)
    val pendingRegenerationKeyId: StateFlow<String?> = _pendingRegenerationKeyId.asStateFlow()

    /** Stage key rotation for an explicit user confirmation in KeysScreen. */
    fun requestRegenerateViaStepCa(keyId: String) {
        if (keyId.isNotBlank()) _pendingRegenerationKeyId.value = keyId
    }

    fun dismissRegenerateViaStepCa() {
        _pendingRegenerationKeyId.value = null
    }

    fun confirmRegenerateViaStepCa() {
        val keyId = _pendingRegenerationKeyId.value ?: return
        _pendingRegenerationKeyId.value = null
        regenerateViaStepCa(keyId)
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun dismissMessage() { _message.value = null }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Import flow state
    private val _importResult = MutableStateFlow<SshKeyImporter.ImportedKey?>(null)
    val importResult: StateFlow<SshKeyImporter.ImportedKey?> = _importResult.asStateFlow()

    private val _needsPassphrase = MutableStateFlow(false)
    val needsPassphrase: StateFlow<Boolean> = _needsPassphrase.asStateFlow()

    private var pendingImportBytes: ByteArray? = null

    fun generateKey(label: String, keyType: SshKeyGenerator.KeyType) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val generated = withContext(Dispatchers.Default) {
                    SshKeyGenerator.generate(keyType, label)
                }
                val entity = SshKey(
                    label = label,
                    keyType = generated.type.sshName,
                    privateKeyBytes = generated.privateKeyBytes,
                    publicKeyOpenSsh = generated.publicKeyOpenSsh,
                    fingerprintSha256 = generated.fingerprintSha256,
                )
                repository.save(entity)
            } catch (e: Exception) {
                _error.value = e.message ?: "Key generation failed"
            } finally {
                _generating.value = false
            }
        }
    }

    /**
     * Generate an Ed25519 keypair locally, run the OIDC handshake against
     * [caConfigId], post the public key to step-ca, and persist the
     * resulting key+cert as a single [SshKey] row. (#133 phase 2)
     *
     * @param principalsOverride if non-empty, replaces the CA's
     *   defaultPrincipals for this one mint.
     */
    fun generateViaStepCa(
        label: String,
        caConfigId: String,
        principalsOverride: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val caConfig = stepCaConfigRepository.getById(caConfigId)
                    ?: run {
                        _error.value = "step-ca config not found"
                        return@launch
                    }
                val generated = withContext(Dispatchers.Default) {
                    SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, label)
                }
                val signResult = stepCaSignFlow.run(
                    caConfig = caConfig,
                    publicKeyOpenSsh = generated.publicKeyOpenSsh,
                    keyLabel = label,
                    principalsOverride = principalsOverride.takeIf { it.isNotEmpty() },
                )
                when (signResult) {
                    is StepCaSignFlow.Result.Failure -> {
                        _error.value = signResult.message
                        return@launch
                    }
                    is StepCaSignFlow.Result.Success -> {
                        val entity = SshKey(
                            label = label,
                            keyType = generated.type.sshName,
                            privateKeyBytes = generated.privateKeyBytes,
                            publicKeyOpenSsh = generated.publicKeyOpenSsh,
                            fingerprintSha256 = generated.fingerprintSha256,
                            certificateBytes = signResult.certBytes,
                            caConfigId = caConfig.id,
                            certIssuedAt = System.currentTimeMillis(),
                        )
                        repository.save(entity)
                    }
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "step-ca generate failed", e)
                _error.value = e.message ?: "step-ca generation failed"
            } finally {
                _generating.value = false
            }
        }
    }

    /**
     * Re-mint the cert for an existing step-ca-minted [SshKey]. Same
     * shape as [generateViaStepCa] but updates the existing row rather
     * than inserting a new one — preserves the id, label, isEncrypted,
     * and biometricProtected flags. (#133 phase 2b)
     *
     * Called only after the confirmation dialog has been accepted.
     */
    private fun regenerateViaStepCa(keyId: String) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val existing = repository.getById(keyId) ?: run {
                    _error.value = "Key no longer exists"
                    return@launch
                }
                val caConfigId = existing.caConfigId ?: run {
                    _error.value = "Key was not minted via step-ca; nothing to regenerate"
                    return@launch
                }
                val caConfig = stepCaConfigRepository.getById(caConfigId) ?: run {
                    _error.value =
                        "step-ca CA used to mint '${existing.label}' has been removed"
                    return@launch
                }
                val generated = withContext(Dispatchers.Default) {
                    SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, existing.label)
                }
                val signResult = stepCaSignFlow.run(
                    caConfig = caConfig,
                    publicKeyOpenSsh = generated.publicKeyOpenSsh,
                    keyLabel = existing.label,
                )
                when (signResult) {
                    is StepCaSignFlow.Result.Failure -> {
                        _error.value = signResult.message
                        return@launch
                    }
                    is StepCaSignFlow.Result.Success -> {
                        val updated = existing.copy(
                            privateKeyBytes = generated.privateKeyBytes,
                            publicKeyOpenSsh = generated.publicKeyOpenSsh,
                            fingerprintSha256 = generated.fingerprintSha256,
                            certificateBytes = signResult.certBytes,
                            certIssuedAt = System.currentTimeMillis(),
                        )
                        repository.save(updated)
                    }
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "step-ca regenerate failed", e)
                _error.value = e.message ?: "step-ca regeneration failed"
            } finally {
                _generating.value = false
            }
        }
    }

    fun importFromUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    _error.value = "Could not read key file"
                    return@launch
                }
                startImport(bytes)
            } catch (e: Exception) {
                _error.value = "Failed to read file: ${e.message}"
            }
        }
    }

    fun startImport(fileBytes: ByteArray) {
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val imported = withContext(Dispatchers.Default) {
                    SshKeyImporter.import(fileBytes)
                }
                _importResult.value = imported
            } catch (e: SshKeyImporter.SkKeyDetectedException) {
                handleSkKeyImport(e.fileBytes)
            } catch (_: SshKeyImporter.EncryptedKeyException) {
                pendingImportBytes = fileBytes
                _needsPassphrase.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to read key file"
            } finally {
                _generating.value = false
            }
        }
    }

    fun retryImportWithPassphrase(passphrase: String) {
        val bytes = pendingImportBytes ?: return
        _needsPassphrase.value = false
        viewModelScope.launch {
            _generating.value = true
            _error.value = null
            try {
                val imported = withContext(Dispatchers.Default) {
                    SshKeyImporter.import(bytes, passphrase)
                }
                _importResult.value = imported
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to decrypt key"
            } finally {
                _generating.value = false
            }
        }
    }

    fun saveImportedKey(label: String) {
        val imported = _importResult.value ?: return
        _importResult.value = null
        pendingImportBytes = null
        viewModelScope.launch {
            try {
                val entity = SshKey(
                    label = label,
                    keyType = imported.keyType,
                    privateKeyBytes = imported.privateKeyBytes,
                    publicKeyOpenSsh = imported.publicKeyOpenSsh,
                    fingerprintSha256 = imported.fingerprintSha256,
                    isEncrypted = imported.isEncrypted,
                )
                repository.save(entity)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to save key"
            }
        }
    }

    private fun handleSkKeyImport(fileBytes: ByteArray) {
        viewModelScope.launch {
            try {
                val skData = SkKeyParser.parse(fileBytes)
                saveSkKey(skData)
                _message.value = "FIDO2 security key imported"
                Log.d("KeysViewModel", "SK key imported: ${skData.algorithmName}, app=${skData.application}")
            } catch (e: Exception) {
                Log.e("KeysViewModel", "SK key import failed", e)
                _error.value = "FIDO2 key import failed: ${e.message}"
            }
        }
    }

    private suspend fun saveSkKey(skData: SkKeyData) {
        val entity = SshKey(
            label = "FIDO2: ${skData.application}",
            keyType = skData.algorithmName,
            privateKeyBytes = SkKeyData.serialize(skData),
            publicKeyOpenSsh = SkKeyParser.formatPublicKeyLine(skData),
            fingerprintSha256 = SkKeyParser.fingerprintSha256(skData.publicKeyBlob),
        )
        repository.save(entity)
    }

    // ---------- FIDO resident key discovery (#152) ----------

    /** Set true while [discoverFromSecurityKey] is running. UI hides the
     *  Add menu and shows the FIDO touch/PIN dialog while non-zero. */
    private val _discoverInProgress = MutableStateFlow(false)
    val discoverInProgress: StateFlow<Boolean> = _discoverInProgress.asStateFlow()

    /** Passthrough to FidoAuthenticator's touch-prompt state so the UI
     *  can render the same waiting/touch/PIN dialog as the connect path
     *  during a discovery enumeration. */
    val fidoTouchPrompt: StateFlow<sh.haven.core.fido.FidoTouchPrompt?> =
        fidoAuthenticator.touchPrompt

    /**
     * Discovered SSH-SK credentials staged for the user to pick from.
     * Non-empty when enumeration succeeded; the UI shows a picker dialog
     * and calls [importDiscoveredCredentials] with the user's selection
     * or [dismissDiscoveryPicker] to discard.
     */
    private val _discoveredCredentials = MutableStateFlow<List<DiscoveredSkCredential>>(emptyList())
    val discoveredCredentials: StateFlow<List<DiscoveredSkCredential>> = _discoveredCredentials.asStateFlow()

    /**
     * Enumerate resident SSH-SK credentials off a connected security key
     * via CTAP 2.1 `authenticatorCredentialManagement` (issue #152). Drives
     * the existing FIDO touch/PIN flow ([FidoAuthenticator.touchPrompt]);
     * once the enumeration completes, populates [discoveredCredentials]
     * for the picker.
     */
    fun discoverFromSecurityKey() {
        if (_discoverInProgress.value) return
        viewModelScope.launch {
            _discoverInProgress.value = true
            try {
                val results = fidoAuthenticator.enumerateResidentCredentials(rpIdPrefix = "ssh")
                if (results.isEmpty()) {
                    _message.value = "No SSH resident keys found on this security key"
                } else {
                    _discoveredCredentials.value = results.mapIndexed { i, (rpId, sk) ->
                        DiscoveredSkCredential(
                            id = "$i",
                            rpId = rpId,
                            algorithmName = sk.algorithmName,
                            fingerprint = SkKeyParser.fingerprintSha256(sk.publicKeyBlob),
                            data = sk,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "discoverFromSecurityKey failed", e)
                _error.value = "FIDO discovery failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _discoverInProgress.value = false
            }
        }
    }

    /** Save a subset of the discovered credentials. Pass empty to drop all. */
    fun importDiscoveredCredentials(ids: Set<String>) {
        val selected = _discoveredCredentials.value.filter { it.id in ids }
        _discoveredCredentials.value = emptyList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            var ok = 0
            for (cred in selected) {
                try {
                    saveSkKey(cred.data)
                    ok++
                } catch (e: Exception) {
                    Log.e("KeysViewModel", "Save of discovered ${cred.rpId} failed", e)
                }
            }
            _message.value = if (ok == selected.size) {
                "Imported $ok resident SSH key${if (ok == 1) "" else "s"}"
            } else {
                "Imported $ok of ${selected.size} keys; check log for failures"
            }
        }
    }

    fun dismissDiscoveryPicker() {
        _discoveredCredentials.value = emptyList()
    }

    fun cancelImport() {
        _importResult.value = null
        _needsPassphrase.value = false
        pendingImportBytes = null
    }

    /** Key ID pending export — UI launches SAF file picker when set. */
    private val _pendingExportKeyId = MutableStateFlow<String?>(null)
    val pendingExportKeyId: StateFlow<String?> = _pendingExportKeyId.asStateFlow()

    fun requestExport(keyId: String) {
        _pendingExportKeyId.value = keyId
    }

    fun clearPendingExport() {
        _pendingExportKeyId.value = null
    }

    fun getExportFileName(keyId: String): String {
        val key = keys.value.firstOrNull { it.id == keyId } ?: return "id_key"
        val sanitized = key.label.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "id_$sanitized"
    }

    fun exportPrivateKey(context: Context, keyId: String, destinationUri: Uri) {
        viewModelScope.launch {
            try {
                val pemBytes = withContext(Dispatchers.IO) {
                    val decrypted = repository.getDecryptedKeyBytes(keyId)
                        ?: throw IllegalStateException("Key not found")
                    val key = keys.value.firstOrNull { it.id == keyId }
                        ?: throw IllegalStateException("Key not found")
                    SshKeyExporter.toPem(decrypted, key.keyType)
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        out.write(pemBytes)
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                _message.value = "Private key exported"
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Export failed", e)
                _error.value = "Export failed: ${e.message}"
            }
        }
    }

    fun deleteKey(id: String) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    /**
     * Pending key id for "attach certificate" — UI launches the SAF
     * file picker when this is set, then calls
     * [importCertificateFromUri] with the chosen Uri.
     */
    private val _pendingCertKeyId = MutableStateFlow<String?>(null)
    val pendingCertKeyId: StateFlow<String?> = _pendingCertKeyId.asStateFlow()

    fun requestAttachCertificate(keyId: String) {
        _pendingCertKeyId.value = keyId
    }

    fun clearPendingCertificate() {
        _pendingCertKeyId.value = null
    }

    /**
     * Read the certificate file the user picked and attach it to the
     * pending key. Validation happens at attach time via [SshCertificateParser]:
     *   - parser rejects anything that isn't a valid `-cert.pub` blob
     *   - the cert's embedded public key fingerprint must match the
     *     target key's [SshKey.fingerprintSha256] — catches the "picked
     *     the wrong cert for this key" mistake here rather than at
     *     connect time, where the failure surface is opaque
     *   - expired certs still attach but with a warning (the user may
     *     be prepping for a renewal that hasn't run yet)
     * On success, the principals list is surfaced so the user can
     * confirm the cert lets them log in as the expected role.
     */
    fun importCertificateFromUri(context: Context, keyId: String, uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("Could not read certificate file")
                val certInfo = SshCertificateParser.parse(bytes)
                if (certInfo == null) {
                    _error.value = "File doesn't look like an OpenSSH certificate (id_xxx-cert.pub)"
                    return@launch
                }
                val key = repository.getById(keyId)
                if (key == null) {
                    _error.value = "Key not found"
                    return@launch
                }
                if (!SshCertificateParser.matchesKey(certInfo, key.fingerprintSha256)) {
                    _error.value = "Certificate does not match this key"
                    return@launch
                }
                repository.setCertificateBytes(keyId, certInfo.rawBlob)
                refreshTicker.value = System.nanoTime()
                _message.value = if (!SshCertificateParser.isCurrentlyValid(certInfo)) {
                    "Certificate attached (warning: outside its validity window)"
                } else if (certInfo.validPrincipals.isNotEmpty()) {
                    "Certificate attached (principals: ${certInfo.validPrincipals.joinToString()})"
                } else {
                    "Certificate attached"
                }
            } catch (e: Exception) {
                Log.e("KeysViewModel", "Attach certificate failed", e)
                _error.value = "Attach failed: ${e.message}"
            }
        }
    }

    fun removeCertificate(keyId: String) {
        viewModelScope.launch {
            repository.setCertificateBytes(keyId, null)
            refreshTicker.value = System.nanoTime()
            _message.value = "Certificate removed"
        }
    }

    // ---------- OATH-TOTP secrets (#178) ----------

    val totpSecrets: StateFlow<List<sh.haven.core.data.db.entities.TotpSecret>> =
        totpSecretRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Add a TOTP secret from a pasted `otpauth://` URI or bare base32 string. */
    fun addTotpFromText(text: String) {
        val parsed = sh.haven.core.security.OtpAuthUri.parse(text)
        if (parsed == null) {
            _error.value = "Not a valid otpauth:// URI or base32 secret"
            return
        }
        saveTotp(parsed)
    }

    /** Decode an `otpauth://` QR from a picked image, then store the secret. */
    fun addTotpFromImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.Default) {
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: throw IllegalStateException("Could not read image")
                    barcodeDecoder.decode(bitmap)
                }
                if (decoded == null) {
                    _error.value = "No QR code found in the image"
                    return@launch
                }
                val parsed = sh.haven.core.security.OtpAuthUri.parse(decoded)
                if (parsed == null) {
                    _error.value = "QR code is not a TOTP (otpauth://totp) code"
                    return@launch
                }
                saveTotp(parsed)
            } catch (e: Exception) {
                Log.e("KeysViewModel", "TOTP QR scan failed", e)
                _error.value = "Could not scan QR: ${e.message}"
            }
        }
    }

    private fun saveTotp(parsed: sh.haven.core.security.OtpAuthUri.Parsed) {
        viewModelScope.launch {
            try {
                totpSecretRepository.save(
                    sh.haven.core.data.db.entities.TotpSecret(
                        label = parsed.label,
                        secret = parsed.secret,
                        issuer = parsed.issuer,
                        accountName = parsed.accountName,
                        algorithm = parsed.algorithm.name,
                        digits = parsed.digits,
                        periodSeconds = parsed.periodSeconds,
                    ),
                )
                _message.value = "Authenticator added: ${parsed.label}"
            } catch (e: Exception) {
                _error.value = "Could not save authenticator: ${e.message}"
            }
        }
    }

    fun deleteTotp(id: String) {
        viewModelScope.launch { totpSecretRepository.delete(id) }
    }

    fun showError(msg: String) {
        _error.value = msg
    }

    fun dismissError() {
        _error.value = null
    }
}
