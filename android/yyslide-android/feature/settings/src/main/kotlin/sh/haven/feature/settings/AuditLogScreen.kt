package sh.haven.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.ConnectionLog

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuditLogScreen(
    onBack: () -> Unit,
    viewModel: AuditLogViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsState()
    val filterProfileId by viewModel.filterProfileId.collectAsState()
    val availableProfiles by viewModel.availableProfiles.collectAsState()
    val verboseLog by viewModel.verboseLog.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedLogId by remember { mutableStateOf<Long?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.audit_log_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
            },
            actions = {
                if (logs.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.audit_log_clear_logs))
                    }
                }
            },
        )

        // Filter chips
        if (availableProfiles.size > 1) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterProfileId == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text(stringResource(R.string.audit_log_filter_all)) },
                )
                availableProfiles.forEach { (id, label) ->
                    FilterChip(
                        selected = filterProfileId == id,
                        onClick = { viewModel.setFilter(if (filterProfileId == id) null else id) },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.audit_log_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(logs, key = { it.id }) { item ->
                    val isExpanded = expandedLogId == item.id
                    val currentVerbose = if (isExpanded && verboseLog?.first == item.id) verboseLog?.second else null

                    LogItem(
                        item = item,
                        expanded = isExpanded,
                        verboseText = currentVerbose,
                        onToggleExpand = {
                            if (isExpanded) {
                                expandedLogId = null
                                viewModel.clearVerboseLog()
                            } else {
                                expandedLogId = item.id
                                viewModel.loadVerboseLog(item.id)
                            }
                        },
                        onCopy = { text ->
                            clipboardManager.setText(AnnotatedString(text))
                        },
                        onShare = { text ->
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_SUBJECT,
                                    context.getString(R.string.audit_log_share_subject, item.profileLabel),
                                )
                                putExtra(android.content.Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    intent,
                                    context.getString(R.string.audit_log_share_chooser),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.audit_log_clear_title)) },
            text = { Text(stringResource(R.string.audit_log_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearLogs()
                    showClearDialog = false
                }) { Text(stringResource(R.string.audit_log_clear_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun LogItem(
    item: LogDisplayItem,
    expanded: Boolean,
    verboseText: String?,
    onToggleExpand: () -> Unit,
    onShare: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
) {
    val (icon, tint) = when (item.status) {
        ConnectionLog.Status.CONNECTED -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        ConnectionLog.Status.DISCONNECTED -> Icons.Filled.RemoveCircle to Color(0xFF9E9E9E)
        ConnectionLog.Status.FAILED -> Icons.Filled.Error to Color(0xFFF44336)
        ConnectionLog.Status.TIMEOUT -> Icons.Filled.Error to Color(0xFFFF9800)
        ConnectionLog.Status.SYNC_OK -> Icons.Filled.Sync to Color(0xFF4CAF50)
        ConnectionLog.Status.SYNC_FAILED -> Icons.Filled.SyncProblem to Color(0xFFF44336)
    }

    val timeText = DateUtils.getRelativeTimeSpanString(
        item.timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    val statusLabel = connectionLogStatusLabel(item.status)

    Column {
        ListItem(
            modifier = Modifier.clickable(onClick = onToggleExpand),
            leadingContent = {
                Icon(icon, contentDescription = statusLabel, tint = tint, modifier = Modifier.size(24.dp))
            },
            headlineContent = {
                Text("${item.profileLabel}${if (item.host.isNotEmpty()) " (${item.host})" else ""}")
            },
            supportingContent = {
                val line = if (item.details != null) "$statusLabel (${item.details}) - $timeText"
                else "$statusLabel - $timeText"
                Text(line)
            },
            trailingContent = {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.audit_log_toggle_details),
                    modifier = Modifier.size(20.dp),
                )
            },
        )

        if (expanded) {
            if (verboseText != null) {
                SelectionContainer {
                    Text(
                        text = verboseText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onCopy != null) {
                        IconButton(onClick = { onCopy(verboseText) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.audit_log_copy_log), modifier = Modifier.size(18.dp))
                        }
                    }
                    if (onShare != null) {
                        IconButton(onClick = { onShare(verboseText) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.audit_log_share_log), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.audit_log_no_verbose),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun connectionLogStatusLabel(status: ConnectionLog.Status): String = when (status) {
    ConnectionLog.Status.CONNECTED -> stringResource(R.string.audit_log_status_connected)
    ConnectionLog.Status.DISCONNECTED -> stringResource(R.string.audit_log_status_disconnected)
    ConnectionLog.Status.FAILED -> stringResource(R.string.audit_log_status_failed)
    ConnectionLog.Status.TIMEOUT -> stringResource(R.string.audit_log_status_timeout)
    ConnectionLog.Status.SYNC_OK -> stringResource(R.string.audit_log_status_sync)
    ConnectionLog.Status.SYNC_FAILED -> stringResource(R.string.audit_log_status_sync_failed)
}
