package sh.haven.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Unified session picker dialog used by both
 * [sh.haven.feature.connections.ConnectionsScreen] (during initial
 * connection — picking which existing tmux/zellij/screen session to
 * attach to or creating a new one) and
 * [sh.haven.feature.terminal.TerminalScreen] (long-press on a tab to
 * spawn a second session on the same SSH connection).
 *
 * The two callers used to ship near-identical local copies of this
 * composable; this consolidation puts the canonical implementation in
 * one place. Callers parameterise the strings so existing per-feature
 * translations (`connections_*` vs `terminal_*`) keep flowing.
 *
 * Optional features (gated by parameter):
 *  - [previousSessionNames] — restore-previous row at the top.
 *    Only meaningful right after a fresh login when the picker can
 *    show "you had these N tmux sessions last time, restore them all?"
 *  - [error] — error string shown above the session list. Used by
 *    the new-tab path when listing remote sessions failed.
 *  - [plainShellSubtitle] / [onPlainShell] — "Open plain shell" row
 *    that bypasses the session manager for one connection. Always
 *    rendered when [onPlainShell] is non-null.
 */
@Composable
fun SessionPickerDialog(
    title: String,
    sessionNames: List<String>,
    suggestedNewName: String,
    createButtonContentDescription: String,
    onSelect: (String) -> Unit,
    onNewSession: (name: String) -> Unit,
    onDismiss: () -> Unit,
    previousSessionNames: List<String> = emptyList(),
    restorePreviousLabel: String? = null,
    onRestorePrevious: (List<String>) -> Unit = {},
    canKill: Boolean = false,
    canRename: Boolean = false,
    killContentDescription: String = "",
    renameContentDescription: String = "",
    onKill: (String) -> Unit = {},
    onRename: (old: String, new: String) -> Unit = { _, _ -> },
    error: String? = null,
    plainShellLabel: String? = null,
    onPlainShell: (() -> Unit)? = null,
    cancelLabel: String,
    renameDialog: @Composable (currentLabel: String, onDismiss: () -> Unit, onRename: (String) -> Unit) -> Unit,
) {
    var renamingSession by remember { mutableStateOf<String?>(null) }

    renamingSession?.let { name ->
        renameDialog(name, { renamingSession = null }) { newName ->
            onRename(name, newName)
            renamingSession = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (previousSessionNames.size > 1 && restorePreviousLabel != null) {
                    ListItem(
                        headlineContent = {
                            Text(
                                restorePreviousLabel,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                previousSessionNames.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable { onRestorePrevious(previousSessionNames) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                sessionNames.forEach { name ->
                    val wasPrevious = name in previousSessionNames
                    ListItem(
                        headlineContent = {
                            Text(
                                name,
                                fontWeight = if (wasPrevious) FontWeight.Bold else null,
                            )
                        },
                        trailingContent = {
                            Row {
                                if (canRename) {
                                    IconButton(onClick = { renamingSession = name }) {
                                        Icon(
                                            Icons.Filled.DriveFileRenameOutline,
                                            contentDescription = renameContentDescription,
                                        )
                                    }
                                }
                                if (canKill) {
                                    IconButton(onClick = { onKill(name) }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = killContentDescription,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { onSelect(name) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NewSessionInlineRow(
                    suggestedName = suggestedNewName,
                    createContentDescription = createButtonContentDescription,
                    onCreate = onNewSession,
                )
                if (onPlainShell != null && plainShellLabel != null) {
                    ListItem(
                        headlineContent = { Text(plainShellLabel) },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.clickable { onPlainShell() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * Inline "Create new session" row at the bottom of [SessionPickerDialog].
 * Pre-fills the suggested name and selects-on-focus so a tap-then-type
 * immediately replaces the suggestion (#112). Keyboard "Done" / Enter
 * triggers Create.
 */
@Composable
private fun NewSessionInlineRow(
    suggestedName: String,
    createContentDescription: String,
    onCreate: (String) -> Unit,
) {
    var fieldValue by remember(suggestedName) {
        mutableStateOf(
            TextFieldValue(
                text = suggestedName,
                selection = TextRange(0, suggestedName.length),
            ),
        )
    }
    var hasBeenFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { fieldValue = it },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && !hasBeenFocused) {
                        hasBeenFocused = true
                        fieldValue = fieldValue.copy(
                            selection = TextRange(0, fieldValue.text.length),
                        )
                    } else if (!focusState.isFocused) {
                        hasBeenFocused = false
                    }
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (fieldValue.text.isNotBlank()) onCreate(fieldValue.text)
            }),
        )
        IconButton(
            onClick = { onCreate(fieldValue.text) },
            enabled = fieldValue.text.isNotBlank(),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = createContentDescription,
            )
        }
    }
}
