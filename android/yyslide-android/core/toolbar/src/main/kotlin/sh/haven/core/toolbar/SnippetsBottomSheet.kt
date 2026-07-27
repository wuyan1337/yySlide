package sh.haven.core.toolbar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.haven.core.data.preferences.ToolbarItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsBottomSheet(
    snippets: List<ToolbarItem.Custom>,
    onDismiss: () -> Unit,
    onSnippetTap: (ToolbarItem.Custom) -> Unit,
    onAddSnippet: (ToolbarItem.Custom) -> Unit,
    onDeleteSnippet: (ToolbarItem.Custom) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filtered = remember(snippets, searchQuery) {
        if (searchQuery.isBlank()) snippets
        else snippets.filter { snippet ->
            snippet.label.contains(searchQuery, ignoreCase = true) ||
                displaySendSequence(snippet.send).contains(searchQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Snippets",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.toolbar_snippet_add))
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.toolbar_snippet_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Text(
                    if (snippets.isEmpty()) "No snippets yet. Add custom keys to the toolbar, or tap + above."
                    else "No matching snippets.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(filtered) { snippet ->
                        ListItem(
                            headlineContent = {
                                Text(snippet.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    displaySendSequence(snippet.send),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { onDeleteSnippet(snippet) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.toolbar_snippet_delete),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onSnippetTap(snippet) },
                        )
                        HorizontalDivider()
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAddDialog) {
        AddSnippetDialog(
            onDismiss = { showAddDialog = false },
            onSave = { snippet ->
                onAddSnippet(snippet)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddSnippetDialog(
    onDismiss: () -> Unit,
    onSave: (ToolbarItem.Custom) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toolbar_snippet_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.toolbar_snippet_label)) },
                    placeholder = { Text("e.g. Deploy, Restart") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.toolbar_snippet_command)) },
                    placeholder = { Text("e.g. docker compose up -d\\n") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                Text(
                    text = "Use \\n for Enter, \\u001b for Escape",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseSendSequence(command)
                    onSave(ToolbarItem.Custom(label.trim(), parsed))
                },
                enabled = label.isNotBlank() && command.isNotBlank(),
            ) {
                Text(stringResource(R.string.toolbar_custom_key_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.toolbar_custom_key_cancel)) }
        },
    )
}
