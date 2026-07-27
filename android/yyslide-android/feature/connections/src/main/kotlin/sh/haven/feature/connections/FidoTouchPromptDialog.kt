package sh.haven.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sh.haven.core.fido.FidoTouchPrompt

/**
 * Modal prompt shown while a FIDO2 SSH assertion is in flight. The dialog is
 * not user-dismissible for the discovery and touch states — the JSch auth
 * path is awaiting the security key's signature, and there is no clean cancel
 * route from the UI thread back into a blocking USB / NFC transfer. The
 * dialog disappears automatically when [FidoTouchPrompt] flips back to null
 * in [FidoAuthenticator.touchPrompt], whether the assertion succeeds, fails,
 * or the underlying transfer times out.
 *
 * The PIN-entry state ([FidoTouchPrompt.EnterPin]) IS user-dismissible —
 * tapping Cancel calls back with `null`, which makes the authenticator throw
 * out of its PIN flow before any blocking touch wait begins.
 */
@Composable
fun FidoTouchPromptDialog(prompt: FidoTouchPrompt) {
    when (prompt) {
        is FidoTouchPrompt.EnterPin -> PinEntryDialog(prompt)
        is FidoTouchPrompt.WaitingForKey,
        is FidoTouchPrompt.TouchKey -> TouchDialog(prompt)
    }
}

@Composable
private fun TouchDialog(prompt: FidoTouchPrompt) {
    val (title, body) = when (prompt) {
        is FidoTouchPrompt.WaitingForKey -> stringResource(R.string.connections_fido_waiting_title) to
            stringResource(R.string.connections_fido_waiting_body)
        is FidoTouchPrompt.TouchKey -> when (prompt.transport) {
            FidoTouchPrompt.TouchKey.Transport.USB ->
                stringResource(R.string.connections_fido_touch_usb_title) to
                    stringResource(R.string.connections_fido_touch_usb_body)
            FidoTouchPrompt.TouchKey.Transport.NFC ->
                stringResource(R.string.connections_fido_touch_nfc_title) to
                    stringResource(R.string.connections_fido_touch_nfc_body)
        }
        is FidoTouchPrompt.EnterPin -> error("PinEntryDialog handles this state")
    }

    AlertDialog(
        // Empty lambda — touch states are not user-dismissible. The
        // FidoAuthenticator clears the state when the assertion finishes
        // (success, failure, or timeout) and the dialog goes away with it.
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.connections_fido_cancel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun PinEntryDialog(prompt: FidoTouchPrompt.EnterPin) {
    var pin by remember { mutableStateOf("") }

    val retriesNote = prompt.retriesRemaining?.let {
        stringResource(R.string.connections_fido_pin_wrong, it)
    }

    AlertDialog(
        onDismissRequest = { prompt.submit(null) },
        title = { Text(stringResource(R.string.connections_fido_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.connections_fido_pin_help),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (retriesNote != null) {
                    Text(
                        retriesNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(R.string.connections_fido_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { prompt.submit(pin) },
                enabled = pin.isNotEmpty(),
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = { prompt.submit(null) }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
