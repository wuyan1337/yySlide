package sh.haven.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Diagnostic page for the proot install log (issue #162 Phase 3b).
 * Mirrors [AuditLogScreen] for shape and feel; the differences are
 * keyed by distroId (not profileId), and the verbose body lives in
 * `logTail` rather than `verboseLog`.
 *
 * Pass [initialFilterDistroId] when navigating from a "View install
 * log" deep-link so the page opens already filtered to the failing
 * distro.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProotInstallLogScreen(
    onBack: () -> Unit,
    initialFilterDistroId: String? = null,
    viewModel: ProotInstallLogViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsState()
    val availableDistros by viewModel.availableDistros.collectAsState()
    val filterDistroId by viewModel.filterDistroId.collectAsState()
    val logTail by viewModel.logTail.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var consumedInitialFilter by remember { mutableStateOf(false) }

    // Apply the deep-link filter exactly once on first composition.
    if (!consumedInitialFilter && initialFilterDistroId != null) {
        viewModel.setFilter(initialFilterDistroId)
        consumedInitialFilter = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_proot_install_log_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                    )
                }
            },
            actions = {
                if (items.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.settings_proot_install_log_clear),
                        )
                    }
                }
            },
        )

        if (availableDistros.size > 1) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterDistroId == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text(stringResource(R.string.audit_log_filter_all)) },
                )
                availableDistros.forEach { id ->
                    FilterChip(
                        selected = filterDistroId == id,
                        onClick = { viewModel.setFilter(if (filterDistroId == id) null else id) },
                        label = { Text(id) },
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    stringResource(R.string.settings_proot_install_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    val isExpanded = expandedId == item.id
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = if (item.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                contentDescription = if (item.ok) "OK" else "Failed",
                                tint = if (item.ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        headlineContent = {
                            Text(
                                "${item.distroId} · ${item.phase}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        supportingContent = {
                            val time = DateUtils.getRelativeTimeSpanString(
                                item.timestamp,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString()
                            val exitSuffix = item.exit?.let { " · exit $it" } ?: ""
                            Text(
                                buildString {
                                    append(time)
                                    append(exitSuffix)
                                    item.message?.let { append("\n").append(it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            if (!item.ok) {
                                IconButton(onClick = {
                                    if (isExpanded) {
                                        expandedId = null
                                        viewModel.clearLogTail()
                                    } else {
                                        expandedId = item.id
                                        viewModel.loadLogTail(item.id)
                                    }
                                }) {
                                    Icon(
                                        if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = !item.ok) {
                            if (isExpanded) {
                                expandedId = null
                                viewModel.clearLogTail()
                            } else {
                                expandedId = item.id
                                viewModel.loadLogTail(item.id)
                            }
                        },
                    )

                    if (isExpanded) {
                        val (loadedId, body) = logTail ?: (null to null)
                        if (loadedId == item.id && body != null) {
                            SelectionContainer {
                                Text(
                                    text = body,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_proot_install_log_clear_title)) },
            text = { Text(stringResource(R.string.settings_proot_install_log_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearAll()
                }) {
                    Text(stringResource(R.string.audit_log_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
