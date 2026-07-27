package sh.haven.app.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.Compatibility
import sh.haven.core.local.proot.Distro
import sh.haven.core.local.proot.DistroCatalog
import sh.haven.core.local.proot.PackageFamily
import sh.haven.feature.connections.R

/**
 * Desktop-tab Manage view (issue #162 Phase 3c). Hosts the distro picker
 * + rootfs setup progress + DE install/start/stop rows that used to live
 * in a full-screen dialog behind the Connections topbar. Co-located with
 * the session viewer ([DesktopScreen]) so the install path and the run
 * path share one home — `DesktopScreen` toggles between this view and
 * the session tabs via the TopAppBar Manage action.
 *
 * State and actions are read from [DesktopViewModel]; the three
 * composables below (`DesktopManagerSection`, `DesktopRow`,
 * `DesktopSetupDialog`) are otherwise the same shape they had in
 * `feature/connections/.../ConnectionsScreen.kt` pre-3c — moving here
 * was a relocation, not a rewrite.
 */
@Composable
fun DesktopManagerScreen(viewModel: DesktopViewModel = hiltViewModel()) {
    val installedDesktops = viewModel.installedDesktops
    val desktopStates by viewModel.desktopStates.collectAsState()
    val desktopSetupState by viewModel.desktopSetupState.collectAsState()
    val activeDistroId by viewModel.activeDistroId.collectAsState()
    val rootfsSetupState by viewModel.rootfsSetupState.collectAsState()
    val installedDistros = viewModel.installedDistros
    val availableDistros = viewModel.availableDistros
    val isRootfsReady = rootfsSetupState is ProotManager.SetupState.Ready

    var setupDesktopDe by remember {
        mutableStateOf<ProotManager.DesktopEnvironment?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DesktopManagerSection(
            installedDesktops = installedDesktops,
            desktopStates = desktopStates,
            desktopSetupState = desktopSetupState,
            activeDistroId = activeDistroId,
            installedDistros = installedDistros,
            availableDistros = availableDistros,
            rootfsSetupState = rootfsSetupState,
            isRootfsReady = isRootfsReady,
            storedVncPortFor = { viewModel.storedVncPortFor(it) },
            onSwitchDistro = { viewModel.switchActiveDistro(it) },
            onOpenShellForDistro = { viewModel.openShellForDistro(it) },
            onAddDistro = { viewModel.addDistro(it) },
            onDeleteDistro = { viewModel.deleteDistro(it.id) },
            onInstall = { setupDesktopDe = it },
            onStart = { viewModel.startDesktop(it) },
            onStop = { viewModel.stopDesktop(it) },
            onUninstall = { viewModel.uninstallDesktop(it) },
            onRetryRootfs = { viewModel.retryRootfsInstall() },
        )
    }

    setupDesktopDe?.let { de ->
        androidx.compose.runtime.LaunchedEffect(desktopSetupState) {
            if (desktopSetupState is ProotManager.DesktopSetupState.Complete) {
                setupDesktopDe = null
                viewModel.resetDesktopSetupState()
            }
        }
        val activeFamily = DistroCatalog.lookup(activeDistroId)?.family
        val suggestedPort = remember(de, activeDistroId) { viewModel.suggestVncPortFor(de) }
        DesktopSetupDialog(
            desktopState = desktopSetupState,
            selectedDe = de,
            activeFamily = activeFamily,
            suggestedVncPort = suggestedPort,
            onStart = { password, _, addons, vncPort ->
                viewModel.setupDesktop(password, de, addons, vncPort)
            },
            onDismiss = {
                setupDesktopDe = null
                viewModel.resetDesktopSetupState()
            },
        )
    }
}

@Composable
private fun DesktopManagerSection(
    installedDesktops: Set<ProotManager.DesktopEnvironment>,
    desktopStates: Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>,
    desktopSetupState: ProotManager.DesktopSetupState,
    activeDistroId: String,
    installedDistros: List<Distro>,
    availableDistros: List<Distro>,
    rootfsSetupState: ProotManager.SetupState,
    isRootfsReady: Boolean,
    storedVncPortFor: (ProotManager.DesktopEnvironment) -> Int?,
    onSwitchDistro: (String) -> Unit,
    onOpenShellForDistro: (String) -> Unit,
    onAddDistro: (Distro) -> Unit,
    onDeleteDistro: (Distro) -> Unit,
    onInstall: (ProotManager.DesktopEnvironment) -> Unit,
    onStart: (ProotManager.DesktopEnvironment) -> Unit,
    onStop: (ProotManager.DesktopEnvironment) -> Unit,
    onUninstall: (ProotManager.DesktopEnvironment) -> Unit,
    onRetryRootfs: () -> Unit,
) {
    var distroMenuOpen by remember { mutableStateOf(false) }

    val activeDistroLabel = installedDistros.firstOrNull { it.id == activeDistroId }?.label
        ?: DistroCatalog.lookup(activeDistroId)?.label
        ?: activeDistroId

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (installedDistros.size > 1 || availableDistros.isNotEmpty()) {
                Box {
                    AssistChip(
                        onClick = { distroMenuOpen = true },
                        label = { Text(activeDistroLabel) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.DesktopWindows,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = distroMenuOpen,
                        onDismissRequest = { distroMenuOpen = false },
                    ) {
                        // Installed distros are NOT rendered as DropdownMenuItem:
                        // its trailingIcon slot is sized for a single decorative
                        // icon, and nesting interactive IconButtons there made the
                        // shell/delete buttons unhittable on non-first rows
                        // (GlassHaven/Haven#168 — confirmed dead on the 2nd row).
                        // A plain Row with three sibling tap targets (switch /
                        // open-shell / delete), each a full 48dp IconButton or a
                        // weighted clickable label, hit-tests reliably.
                        installedDistros.forEach { distro ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable {
                                            onSwitchDistro(distro.id)
                                            distroMenuOpen = false
                                        }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (distro.id == activeDistroId) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(distro.label)
                                }
                                // Open a proot shell in this distro (#168). Switches
                                // the active distro and opens a local-shell tab.
                                IconButton(onClick = {
                                    distroMenuOpen = false
                                    onOpenShellForDistro(distro.id)
                                }) {
                                    Icon(
                                        Icons.Filled.Terminal,
                                        contentDescription = stringResource(
                                            R.string.connections_desktop_open_shell_for_distro,
                                            distro.label,
                                        ),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = {
                                    distroMenuOpen = false
                                    onDeleteDistro(distro)
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(
                                            R.string.connections_desktop_delete_distro,
                                            distro.label,
                                        ),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        if (availableDistros.isNotEmpty() && installedDistros.isNotEmpty()) {
                            HorizontalDivider()
                        }
                        availableDistros.forEach { distro ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.connections_desktop_add_distro,
                                            distro.label,
                                            distro.sizeEstimateMb,
                                        ),
                                    )
                                },
                                onClick = {
                                    onAddDistro(distro)
                                    distroMenuOpen = false
                                },
                            )
                        }
                    }
                }
                when (val s = rootfsSetupState) {
                    is ProotManager.SetupState.Downloading -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connections_rootfs_downloading, s.progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    ProotManager.SetupState.Extracting -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connections_rootfs_extracting),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ProotManager.SetupState.Initializing -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.connections_rootfs_initializing, s.step),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is ProotManager.SetupState.Error -> {
                        Spacer(Modifier.height(4.dp))
                        val phaseLabel = when (s.phase) {
                            ProotManager.Phase.RootfsDownload -> stringResource(R.string.connections_phase_download)
                            ProotManager.Phase.RootfsExtract -> stringResource(R.string.connections_phase_extract)
                            ProotManager.Phase.BootstrapHook -> stringResource(R.string.connections_phase_bootstrap_hook)
                            ProotManager.Phase.Baseline -> stringResource(R.string.connections_phase_baseline_packages)
                        }
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    stringResource(R.string.connections_phase_failed, phaseLabel),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (s.logTail.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                s.logTail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.connections_proot_history_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // Retry — re-runs the failing layer. For
                        // Download/Extract the underlying retry wipes
                        // the rootfs and starts over (partial state is
                        // the risk); for Hook/Baseline it re-runs
                        // hooks + baseline against the existing
                        // rootfs. Label reflects which path Retry
                        // will take.
                        val retryLabel = when (s.phase) {
                            ProotManager.Phase.RootfsDownload,
                            ProotManager.Phase.RootfsExtract -> stringResource(R.string.connections_retry_wipe)
                            ProotManager.Phase.BootstrapHook,
                            ProotManager.Phase.Baseline -> stringResource(R.string.connections_retry_step)
                        }
                        TextButton(onClick = onRetryRootfs) { Text(retryLabel) }
                    }
                    else -> { /* Ready / NotInstalled — silent */ }
                }
                Spacer(Modifier.height(8.dp))
            }

            // In-progress desktop-install indicator. The DesktopSetupDialog
            // normally shows this, but its "which DE" state is screen-local
            // `remember` and is lost when the user navigates away (e.g. to
            // the Terminal tab) and back while the install keeps running in
            // the ViewModel scope — leaving the rows dimmed with no
            // explanation, which reads as a silent failure. desktopSetupState
            // lives in ProotManager so it survives navigation; drive a
            // banner off it so the Manage screen always shows an install
            // that's still going.
            val deInstalling = desktopSetupState as? ProotManager.DesktopSetupState.Installing
            if (deInstalling != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        deInstalling.step.ifBlank {
                            stringResource(R.string.connections_desktop_installing)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            val activeDistro = DistroCatalog.lookup(activeDistroId)
            val compatibleDes = ProotManager.DesktopEnvironment.entries
                .filter { !it.hidden }
                .filter { de ->
                    activeDistro == null ||
                        de.spec.packagesPerFamily.containsKey(activeDistro.family)
                }
            compatibleDes.forEach { de ->
                val isInstalled = de in installedDesktops
                val instance = desktopStates[de]
                DesktopRow(
                    de = de,
                    isInstalled = isInstalled,
                    instance = instance,
                    isSetupBusy = desktopSetupState is ProotManager.DesktopSetupState.Installing,
                    activeFamily = activeDistro?.family,
                    isRootfsReady = isRootfsReady,
                    storedVncPort = storedVncPortFor(de),
                    onInstall = { onInstall(de) },
                    onStart = { onStart(de) },
                    onStop = { onStop(de) },
                    onUninstall = { onUninstall(de) },
                )
            }
        }
    }
}

@Composable
private fun DesktopRow(
    de: ProotManager.DesktopEnvironment,
    isInstalled: Boolean,
    instance: DesktopManager.DesktopInstance?,
    isSetupBusy: Boolean = false,
    activeFamily: PackageFamily? = null,
    isRootfsReady: Boolean = true,
    storedVncPort: Int? = null,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    val compatibility = activeFamily?.let { de.spec.compatibilityOn(it) }
        ?: Compatibility.Stable
    var showUninstallConfirm by remember { mutableStateOf(false) }

    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.connections_desktop_uninstall_title)) },
            text = { Text(stringResource(R.string.connections_desktop_uninstall_message, de.label)) },
            confirmButton = {
                TextButton(onClick = { showUninstallConfirm = false; onUninstall() }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Icon(
            Icons.Filled.Circle,
            contentDescription = null,
            tint = when (instance?.state) {
                DesktopManager.DesktopState.RUNNING -> Color(0xFF4CAF50)
                DesktopManager.DesktopState.STARTING -> Color(0xFFFFC107)
                DesktopManager.DesktopState.ERROR -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(de.label, style = MaterialTheme.typography.bodyMedium)
                if (compatibility == Compatibility.Experimental) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.connections_desktop_experimental),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            when {
                instance?.state == DesktopManager.DesktopState.RUNNING && !de.isNative ->
                    Text(
                        stringResource(
                            R.string.connections_desktop_vnc_instance,
                            instance.displayNumber,
                            instance.vncPort,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == DesktopManager.DesktopState.RUNNING && de.isNative ->
                    Text(
                        stringResource(R.string.connections_desktop_native_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                instance?.state == DesktopManager.DesktopState.ERROR ->
                    Text(
                        instance.errorMessage ?: "Error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                !isInstalled ->
                    Text(
                        de.sizeEstimate +
                            if (!isRootfsReady) " · install distro first" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                isInstalled && instance?.state != DesktopManager.DesktopState.RUNNING &&
                    instance?.state != DesktopManager.DesktopState.STARTING && storedVncPort != null && !de.isNative ->
                    Text(
                        stringResource(R.string.connections_desktop_vnc_port_value, storedVncPort),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }

        if (!isInstalled) {
            // Disabled until rootfs reaches Ready — the install path
            // calls into ProotManager.setupDesktop which silently
            // installs the rootfs if missing; we'd rather make the
            // dependency obvious. The subtitle above carries the
            // "install distro first" hint.
            TextButton(onClick = onInstall, enabled = !isSetupBusy && isRootfsReady) {
                Text(stringResource(R.string.common_install))
            }
        } else if (isSetupBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            when (instance?.state) {
                DesktopManager.DesktopState.RUNNING ->
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.connections_desktop_stop))
                    }
                DesktopManager.DesktopState.STARTING ->
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> {
                    IconButton(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.connections_desktop_start))
                    }
                    IconButton(onClick = { showUninstallConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopSetupDialog(
    desktopState: ProotManager.DesktopSetupState,
    selectedDe: ProotManager.DesktopEnvironment,
    activeFamily: PackageFamily? = null,
    suggestedVncPort: Int = 5901,
    onStart: (
        password: String,
        de: ProotManager.DesktopEnvironment,
        addons: Set<ProotManager.DesktopAddon>,
        vncPort: Int?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val compatibility = activeFamily?.let { selectedDe.spec.compatibilityOn(it) }
        ?: Compatibility.Stable
    val compatibilityNote = activeFamily?.let { selectedDe.spec.compatibilityNoteOn(it) }
    var password by rememberSaveable { mutableStateOf("haven") }
    var shellCmd by rememberSaveable { mutableStateOf("/bin/sh") }
    var portText by rememberSaveable(selectedDe, suggestedVncPort) {
        mutableStateOf(suggestedVncPort.toString())
    }
    val portInt = portText.toIntOrNull()
    val portValid = portInt != null && portInt in 5901..5999
    var selectedAddons by remember {
        mutableStateOf(emptySet<ProotManager.DesktopAddon>())
    }
    val isInstalling = desktopState is ProotManager.DesktopSetupState.Installing

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text(stringResource(R.string.connections_desktop_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (desktopState) {
                    is ProotManager.DesktopSetupState.Idle -> {
                        Text(
                            "${selectedDe.label} (${selectedDe.sizeEstimate})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (compatibility == Compatibility.Experimental && compatibilityNote != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        stringResource(
                                            R.string.connections_desktop_experimental_on,
                                            activeFamily.name,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        compatibilityNote,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                        Text(
                            when {
                                selectedDe.isWayland -> stringResource(R.string.connections_desktop_wayland_description)
                                selectedDe == ProotManager.DesktopEnvironment.OPENBOX -> stringResource(R.string.connections_desktop_openbox_description)
                                else -> stringResource(R.string.connections_desktop_vnc_description)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!selectedDe.isWayland) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.connections_desktop_vnc_password)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // Per-DE VNC port. Defaults to the next free
                            // 5900+N for the active distro; the user can
                            // override (e.g. to match an SSH tunnel they
                            // already have set up, or to dodge a port
                            // that's in use elsewhere on the network).
                            // Range 5901-5999 is enforced; outside that
                            // we keep the field editable but disable
                            // Install via portValid.
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text(stringResource(R.string.connections_field_vnc_port)) },
                                supportingText = {
                                    Text(
                                        if (portValid) {
                                            stringResource(
                                                R.string.connections_desktop_vnc_display,
                                                portInt - 5900,
                                            )
                                        } else {
                                            stringResource(R.string.connections_desktop_vnc_port_range)
                                        },
                                    )
                                },
                                isError = !portValid,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (selectedDe.isNative) {
                            var shellExpanded by remember { mutableStateOf(false) }
                            val shellOptions = listOf("/bin/sh", "/bin/ash", "/bin/bash", "/bin/zsh", "/bin/fish")
                            ExposedDropdownMenuBox(
                                expanded = shellExpanded,
                                onExpandedChange = { shellExpanded = it },
                            ) {
                                OutlinedTextField(
                                    value = shellCmd,
                                    onValueChange = { shellCmd = it },
                                    label = { Text(stringResource(R.string.connections_desktop_shell)) },
                                    singleLine = true,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = shellExpanded) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                                )
                                ExposedDropdownMenu(
                                    expanded = shellExpanded,
                                    onDismissRequest = { shellExpanded = false },
                                ) {
                                    shellOptions.forEach { shell ->
                                        DropdownMenuItem(
                                            text = { Text(shell) },
                                            onClick = {
                                                shellCmd = shell
                                                shellExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Text(
                                stringResource(R.string.connections_desktop_addons_header),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            ProotManager.DesktopAddon.entries.forEach { addon ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Checkbox(
                                        checked = addon in selectedAddons,
                                        onCheckedChange = { checked ->
                                            selectedAddons = if (checked) selectedAddons + addon
                                                else selectedAddons - addon
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(addon.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${addon.description} (${addon.sizeEstimate})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is ProotManager.DesktopSetupState.Installing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(
                                desktopState.step,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProotManager.DesktopSetupState.Complete -> {
                        Text(stringResource(R.string.connections_desktop_installed))
                    }
                    is ProotManager.DesktopSetupState.Error -> {
                        val phaseLabel = when (desktopState.phase) {
                            ProotManager.DePhase.Packages -> stringResource(R.string.connections_phase_desktop_packages)
                            ProotManager.DePhase.VncConfig -> stringResource(R.string.connections_phase_vnc_config)
                            ProotManager.DePhase.Marker -> stringResource(R.string.connections_phase_marker_file)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        stringResource(R.string.connections_phase_failed, phaseLabel),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                            )
                            Text(
                                stringResource(R.string.connections_desktop_setup_failed, desktopState.message),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (desktopState.logTail.isNotEmpty()) {
                                Text(
                                    desktopState.logTail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 10,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                stringResource(R.string.connections_proot_history_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (desktopState is ProotManager.DesktopSetupState.Idle) {
                // Wayland DEs don't surface a VNC port (no Xvnc), so
                // the dialog skips the port field and we pass null —
                // setupDesktop handles null as "no preference".
                val portArg = if (selectedDe.isWayland) null else portInt
                TextButton(
                    onClick = { onStart(password, selectedDe, selectedAddons, portArg) },
                    enabled = selectedDe.isWayland || portValid,
                ) { Text(stringResource(R.string.common_install)) }
            }
        },
        dismissButton = {
            if (!isInstalling) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
