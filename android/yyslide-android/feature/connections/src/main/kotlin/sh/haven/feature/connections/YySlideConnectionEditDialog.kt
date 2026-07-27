package sh.haven.feature.connections

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.haven.core.data.db.entities.ConnectionGroup
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.ssh.SshKeyImporter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YySlideConnectionEditDialog(
    existing: ConnectionProfile? = null,
    groups: List<ConnectionGroup> = emptyList(),
    sshKeys: List<SshKey> = emptyList(),
    onDismiss: () -> Unit,
    onCreateGroup: (ConnectionGroup) -> Unit = {},
    onImportKey: (Uri, String?, (Result<SshKey>) -> Unit) -> Unit = { _, _, callback ->
        callback(Result.failure(IllegalStateException("Key import is unavailable")))
    },
    onSave: (ConnectionProfile) -> Unit,
) {
    val initialKey = existing?.authMethodSpecs
        ?.filterIsInstance<ConnectionProfile.AuthMethodSpec.Key>()
        ?.firstOrNull()
    var label by rememberSaveable { mutableStateOf(existing?.label.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(existing?.sshPassword.orEmpty()) }
    var groupId by rememberSaveable { mutableStateOf(existing?.groupId) }
    var authMode by rememberSaveable {
        mutableStateOf(if (initialKey != null || existing?.authType == ConnectionProfile.AuthType.KEY) "KEY" else "PASSWORD")
    }
    var keyId by rememberSaveable { mutableStateOf(initialKey?.keyId ?: existing?.keyId) }
    var authExpanded by remember { mutableStateOf(false) }
    var groupExpanded by remember { mutableStateOf(false) }
    var keyExpanded by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupLabel by rememberSaveable { mutableStateOf("") }
    var pendingKeyUri by remember { mutableStateOf<Uri?>(null) }
    var importingKey by remember { mutableStateOf(false) }
    var keyImportError by remember { mutableStateOf<String?>(null) }
    var showKeyPassphraseDialog by remember { mutableStateOf(false) }
    var keyPassphrase by rememberSaveable { mutableStateOf("") }
    val importPrivateKeyFailed = stringResource(R.string.connections_yy_import_private_key_failed)

    val selectedGroup = groups.firstOrNull { it.id == groupId }
    val selectedKey = sshKeys.firstOrNull { it.id == keyId }
    val canSave = host.isNotBlank() && port.toIntOrNull() in 1..65535
    fun startKeyImport(uri: Uri, passphrase: String?) {
        importingKey = true
        keyImportError = null
        onImportKey(uri, passphrase) { result ->
            importingKey = false
            result
                .onSuccess { key ->
                    authMode = "KEY"
                    keyId = key.id
                    keyPassphrase = ""
                    showKeyPassphraseDialog = false
                }
                .onFailure { error ->
                    if (error is SshKeyImporter.EncryptedKeyException) {
                        pendingKeyUri = uri
                        showKeyPassphraseDialog = true
                    } else {
                        keyImportError = error.message ?: importPrivateKeyFailed
                    }
                }
        }
    }
    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingKeyUri = uri
        startKeyImport(uri, null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.connections_dialog_new else R.string.connections_dialog_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.connections_yy_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.common_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.common_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(112.dp),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.common_username)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = groupExpanded,
                    onExpandedChange = { groupExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedGroup?.label ?: stringResource(R.string.connections_yy_no_category),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.connections_yy_category)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(groupExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = groupExpanded,
                        onDismissRequest = { groupExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_yy_no_category)) },
                            onClick = {
                                groupId = null
                                groupExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_yy_new_category)) },
                            onClick = {
                                groupExpanded = false
                                newGroupLabel = ""
                                showNewGroupDialog = true
                            },
                        )
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.label) },
                                onClick = {
                                    groupId = group.id
                                    groupExpanded = false
                                },
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = authExpanded,
                    onExpandedChange = { authExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(
                            if (authMode == "KEY") R.string.connections_yy_private_key else R.string.common_password,
                        ),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.connections_yy_authentication)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(authExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = authExpanded,
                        onDismissRequest = { authExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_password)) },
                            onClick = {
                                authMode = "PASSWORD"
                                authExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.connections_yy_private_key)) },
                            onClick = {
                                authMode = "KEY"
                                if (keyId == null) keyId = sshKeys.firstOrNull()?.id
                                authExpanded = false
                            },
                        )
                    }
                }

                if (authMode == "KEY") {
                    ExposedDropdownMenuBox(
                        expanded = keyExpanded,
                        onExpandedChange = { keyExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedKey?.label ?: stringResource(R.string.connections_yy_any_saved_key),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.connections_yy_key)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(keyExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(
                            expanded = keyExpanded,
                            onDismissRequest = { keyExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_yy_any_saved_key)) },
                                onClick = {
                                    keyId = null
                                    keyExpanded = false
                                },
                            )
                            sshKeys.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(key.label) },
                                    onClick = {
                                        keyId = key.id
                                        keyExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connections_yy_import_local_key_file)) },
                                onClick = {
                                    keyExpanded = false
                                    keyPickerLauncher.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !importingKey,
                            onClick = { keyPickerLauncher.launch(arrayOf("*/*")) },
                        ) {
                            Text(
                                stringResource(
                                    if (importingKey) R.string.connections_yy_importing else R.string.connections_yy_import_local_key,
                                ),
                            )
                        }
                    }
                    if (!keyImportError.isNullOrBlank()) {
                        Text(
                            text = keyImportError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    sh.haven.core.ui.PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.common_password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("SSH") },
                    )
                    Text(
                        text = stringResource(R.string.connections_yy_ssh_only_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val portInt = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                    val specs = if (authMode == "KEY") {
                        listOf(ConnectionProfile.AuthMethodSpec.Key(keyId))
                    } else {
                        listOf(ConnectionProfile.AuthMethodSpec.Password)
                    }
                    val authMethods = ConnectionProfile.AuthMethodSpec.serializeList(specs)
                    val profile = (existing ?: ConnectionProfile(
                        label = label.ifBlank { "${username}@${host}" },
                        host = host,
                        port = portInt,
                        username = username,
                    )).copy(
                        label = label.ifBlank { "${username}@${host}" },
                        host = host.trim(),
                        port = portInt,
                        username = username.trim(),
                        sshPassword = password.takeIf { authMode == "PASSWORD" && it.isNotBlank() },
                        authType = if (authMode == "KEY") ConnectionProfile.AuthType.KEY else ConnectionProfile.AuthType.PASSWORD,
                        keyId = if (authMode == "KEY") keyId else null,
                        authMethods = authMethods,
                        connectionType = "SSH",
                        groupId = groupId,
                        destinationHash = null,
                        jumpProfileId = null,
                        proxyType = null,
                        proxyHost = null,
                        tunnelConfigId = null,
                        vncPort = null,
                        vncUsername = null,
                        vncPassword = null,
                        vncSshProfileId = null,
                        sessionManager = null,
                        useMosh = false,
                        useEternalTerminal = false,
                        rdpUsername = null,
                        rdpDomain = null,
                        rdpPassword = null,
                        rdpSshProfileId = null,
                        smbShare = null,
                        smbDomain = null,
                        smbPassword = null,
                        smbSshProfileId = null,
                        rcloneRemoteName = null,
                        rcloneProvider = null,
                        useAndroidShell = false,
                        moshServerCommand = null,
                        forwardAgent = false,
                        addressFamily = "AUTO",
                        reticulumNetworkName = null,
                        reticulumPassphrase = null,
                        postLoginCommand = null,
                        fileTransport = "AUTO",
                        terminalColorScheme = null,
                        autoReconnect = true,
                        reconnectMaxAttempts = 5,
                        reconnectOnNetworkChange = true,
                        tunnelOnly = false,
                        portKnockSequence = null,
                        spaKey = null,
                        spaHmacKey = null,
                        spaAccessSpec = null,
                        spaExplicitIp = null,
                    )
                    onSave(profile)
                },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = { Text(stringResource(R.string.connections_yy_new_category)) },
            text = {
                OutlinedTextField(
                    value = newGroupLabel,
                    onValueChange = { newGroupLabel = it },
                    label = { Text(stringResource(R.string.connections_yy_category_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newGroupLabel.isNotBlank(),
                    onClick = {
                        val group = ConnectionGroup(label = newGroupLabel.trim())
                        onCreateGroup(group)
                        groupId = group.id
                        showNewGroupDialog = false
                    },
                ) {
                    Text(stringResource(R.string.common_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewGroupDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showKeyPassphraseDialog) {
        AlertDialog(
            onDismissRequest = {
                showKeyPassphraseDialog = false
                keyPassphrase = ""
            },
            title = { Text(stringResource(R.string.connections_yy_private_key_passphrase)) },
            text = {
                sh.haven.core.ui.PasswordField(
                    value = keyPassphrase,
                    onValueChange = { keyPassphrase = it },
                    label = stringResource(R.string.connections_yy_passphrase),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !importingKey,
                    onClick = {
                        pendingKeyUri?.let { startKeyImport(it, keyPassphrase) }
                    },
                ) {
                    Text(
                        stringResource(
                            if (importingKey) R.string.connections_yy_importing else R.string.common_import,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !importingKey,
                    onClick = {
                        showKeyPassphraseDialog = false
                        keyPassphrase = ""
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
