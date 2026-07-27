package sh.haven.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import sh.haven.core.ssh.KnownHostEntry

@Composable
fun NewHostKeyDialog(
    entry: KnownHostEntry,
    onTrust: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.connections_hostkey_title_verify)) },
        text = {
            Column {
                val hostDisplay = if (entry.port == 22) entry.hostname
                    else "${entry.hostname}:${entry.port}"
                Text(stringResource(R.string.connections_hostkey_first_time, hostDisplay))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.connections_hostkey_key_type, entry.keyType))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.connections_hostkey_fingerprint))
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.fingerprint(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.connections_hostkey_verify_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(stringResource(R.string.connections_hostkey_trust))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
fun KeyChangedDialog(
    oldFingerprint: String,
    entry: KnownHostEntry,
    onAccept: () -> Unit,
    onDisconnect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDisconnect,
        title = {
            Text(
                stringResource(R.string.connections_hostkey_changed_title),
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column {
                val hostDisplay = if (entry.port == 22) entry.hostname
                    else "${entry.hostname}:${entry.port}"
                Text(
                    stringResource(R.string.connections_hostkey_changed_body, hostDisplay),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.connections_hostkey_old_fingerprint), style = MaterialTheme.typography.bodySmall)
                Text(
                    oldFingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.connections_hostkey_new_fingerprint), style = MaterialTheme.typography.bodySmall)
                Text(
                    entry.fingerprint(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.connections_hostkey_accept_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.connections_hostkey_disconnect))
            }
        },
    )
}
