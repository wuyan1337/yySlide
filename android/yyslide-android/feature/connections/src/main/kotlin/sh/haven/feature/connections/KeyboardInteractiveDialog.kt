package sh.haven.feature.connections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import sh.haven.core.ssh.KeyboardInteractiveChallenge
import sh.haven.core.ui.PasswordField

/**
 * Collect responses for a server-issued keyboard-interactive round.
 * Renders one field per prompt, masking the fields the server marked
 * as no-echo (passwords, TOTP codes). Submitting returns the responses
 * to JSch via [ConnectionsViewModel.submitKeyboardInteractiveResponses];
 * dismissing cancels the auth attempt.
 *
 * The dialog is not auto-dismissible — the SSH IO thread is blocked
 * waiting for a response, and the user should consciously either
 * answer or cancel. See #100.
 */
@Composable
fun KeyboardInteractiveDialog(
    challenge: KeyboardInteractiveChallenge,
    onSubmit: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val responses = remember(challenge) {
        android.util.Log.d(
            "yySlideKI",
            "Dialog: remember(challenge) → NEW responses list for prompts=${challenge.prompts.size} first='${challenge.prompts.firstOrNull()?.text}'",
        )
        // Pre-fill from challenge.prefilled where present (#178 confirm-OTP
        // path supplies the generated code); blank otherwise.
        mutableStateListOf<String>().apply {
            addAll(List(challenge.prompts.size) { i -> challenge.prefilled.getOrNull(i) ?: "" })
        }
    }

    DisposableEffect(challenge) {
        android.util.Log.d("yySlideKI", "Dialog: ENTER challenge prompts=${challenge.prompts.size} first='${challenge.prompts.firstOrNull()?.text}'")
        onDispose {
            android.util.Log.d("yySlideKI", "Dialog: LEAVE challenge first='${challenge.prompts.firstOrNull()?.text}'")
        }
    }

    val submit: () -> Unit = {
        android.util.Log.d(
            "yySlideKI",
            "Dialog: submit tapped, responses=${responses.toList().map { it.length }}",
        )
        onSubmit(responses.toList())
    }

    val titleAdditional = stringResource(R.string.connections_ki_title_additional)
    val titleServer = stringResource(R.string.connections_ki_title_server)
    val title = when {
        challenge.name.isNotBlank() -> challenge.name
        challenge.prompts.any { !it.echo } -> titleAdditional
        else -> titleServer
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                if (challenge.destination.isNotBlank()) {
                    Text(
                        challenge.destination,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (challenge.instruction.isNotBlank()) {
                    Text(
                        challenge.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                challenge.prompts.forEachIndexed { i, prompt ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    val isLast = i == challenge.prompts.lastIndex
                    val imeAction = if (isLast) ImeAction.Go else ImeAction.Next
                    if (prompt.echo) {
                        OutlinedTextField(
                            value = responses[i],
                            onValueChange = { responses[i] = it },
                            label = { Text(prompt.text.ifBlank { stringResource(R.string.connections_ki_response_fallback) }) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = imeAction,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PasswordField(
                            value = responses[i],
                            onValueChange = { responses[i] = it },
                            label = prompt.text.ifBlank { stringResource(R.string.connections_ki_response_fallback) },
                            imeAction = imeAction,
                            onImeAction = if (isLast) submit else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(stringResource(R.string.connections_ki_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
