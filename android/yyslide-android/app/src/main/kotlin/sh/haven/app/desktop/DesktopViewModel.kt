package sh.haven.app.desktop

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.desktop.DesktopStatus
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.knock.KnockSequence
import sh.haven.core.knock.PortKnocker
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.local.proot.Distro
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.rdp.RdpSession
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.core.tunnel.TunneledConnection
import sh.haven.core.tunnel.TunneledSocket
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import sh.haven.feature.rdp.RdpViewModel
import sh.haven.feature.vnc.VncViewModel
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

private const val TAG = "DesktopViewModel"

/** An active SSH session that can be used for tunneling. */
data class SshTunnelOption(
    val sessionId: String,
    val label: String,
    val profileId: String,
)

private sealed class DesktopStartOutcome {
    object Ready : DesktopStartOutcome()
    object Timeout : DesktopStartOutcome()
    data class Error(val message: String) : DesktopStartOutcome()
}

@HiltViewModel
class DesktopViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
    private val moshSessionManager: MoshSessionManager,
    private val etSessionManager: EtSessionManager,
    private val connectionLogRepository: ConnectionLogRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val tunnelResolver: TunnelResolver,
    private val portKnocker: PortKnocker,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val localSessionManager: LocalSessionManager,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
) : ViewModel() {

    // --- Distro / DE management (issue #162 Phase 3c) ---
    //
    // The Connections topbar used to host a dialog with the distro picker,
    // rootfs setup state, and DE install/start/stop rows. That UI moved
    // to a "Manage" view inside the Desktop tab in 3c (`DesktopManagerScreen`).
    // These StateFlows + methods are the data layer the new screen reads;
    // every accessor is a thin pass-through to ProotManager / DesktopManager
    // so the source of truth stays in one place.

    private val prootManager: ProotManager get() = localSessionManager.prootManager
    private val desktopManager: DesktopManager get() = localSessionManager.desktopManager

    val activeDistroId: StateFlow<String> get() = prootManager.activeDistroIdFlow

    val rootfsSetupState: StateFlow<ProotManager.SetupState> get() = prootManager.state

    val desktopSetupState: StateFlow<ProotManager.DesktopSetupState>
        get() = prootManager.desktopState

    val desktopStates: StateFlow<Map<ProotManager.DesktopEnvironment, DesktopManager.DesktopInstance>>
        get() = desktopManager.desktops

    val installedDistros: List<Distro> get() = prootManager.installedDistros
    val availableDistros: List<Distro> get() = prootManager.availableDistros
    val installedDesktops: Set<ProotManager.DesktopEnvironment>
        get() = prootManager.installedDesktops

    val isRootfsReady: Boolean get() = prootManager.isReady

    fun switchActiveDistro(distroId: String) {
        prootManager.setActiveDistroId(distroId)
    }

    /**
     * Local-shell open requests keyed by the resolved profile id. Collected
     * by HavenNavHost (which is always composed, unlike TerminalScreen) so
     * the request is never dropped while the user is on the Desktop tab.
     * HavenNavHost sets a pending-profile state and animates to the Terminal
     * page; TerminalScreen consumes it once composed and calls
     * addLocalTabForProfile. SharedFlow with replay=0 — a screen rotation
     * shouldn't re-open a shell. See GlassHaven/Haven#168.
     */
    private val _openLocalShellRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openLocalShellRequests: SharedFlow<String> = _openLocalShellRequests.asSharedFlow()

    /**
     * Open a terminal tab into the given distro. Restores the entry-point
     * removed in v5.38.0 when the Connections topbar lost its "Alpine
     * console" icon — see GlassHaven/Haven#168. Reuses the single canonical
     * "Local Shell" profile (mirrors McpTools.openLocalShell so the agent
     * and the user reach the same place) and switches the active distro
     * to [distroId] first, so the proot session boots into the right rootfs.
     *
     * Emits the resolved profile id on [openLocalShellRequests] rather than
     * the AgentUiCommandBus: the bus is replay=0 and TerminalViewModel only
     * collects it while TerminalScreen is composed, so a request fired from
     * the Desktop tab (Terminal not in the pager's composition window) was
     * silently dropped — the tab never opened (#168 regression). HavenNavHost
     * is always composed, so routing through it is reliable.
     */
    fun openShellForDistro(distroId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (prootManager.activeDistroId != distroId) {
                prootManager.setActiveDistroId(distroId)
            }
            val all = connectionRepository.getAll()
            val profile = all.firstOrNull {
                it.connectionType == "LOCAL" && !it.useAndroidShell
            } ?: run {
                val seeded = ConnectionProfile(
                    label = "Local Shell",
                    host = "localhost",
                    username = "",
                    port = 0,
                    connectionType = "LOCAL",
                    useAndroidShell = false,
                )
                connectionRepository.save(seeded)
                connectionRepository.getAll().firstOrNull {
                    it.connectionType == "LOCAL" && it.label == "Local Shell" && !it.useAndroidShell
                } ?: seeded
            }
            _openLocalShellRequests.emit(profile.id)
        }
    }

    /**
     * Make [distro] the active distro and install its rootfs.
     * Mirrors the revert-on-failure semantics from ConnectionsViewModel.addDistro:1472.
     */
    fun addDistro(distro: Distro) {
        val previousActive = prootManager.activeDistroId
        prootManager.setActiveDistroId(distro.id)
        viewModelScope.launch {
            try {
                prootManager.installRootfs()
                if (prootManager.state.value is ProotManager.SetupState.Error) {
                    Log.w(TAG, "addDistro: ${distro.id} install failed, reverting to $previousActive")
                    prootManager.setActiveDistroId(previousActive)
                }
            } catch (e: Exception) {
                Log.e(TAG, "addDistro: ${distro.id} install threw", e)
                prootManager.setActiveDistroId(previousActive)
            }
        }
    }

    /**
     * Delete a distro's rootfs. Stops any DEs running on it first so
     * the session-viewer tabs don't outlive their backing rootfs.
     */
    fun deleteDistro(distroId: String) {
        if (prootManager.activeDistroId == distroId) {
            desktopManager.stopAll()
        }
        prootManager.deleteDistro(distroId)
    }

    /**
     * Install a desktop environment on the active distro. Optional addons
     * land in a follow-up `installAddons` call when the primary install
     * succeeds — same shape ConnectionsViewModel.setupDesktop had before
     * the 3c move.
     */
    fun setupDesktop(
        vncPassword: String,
        de: ProotManager.DesktopEnvironment = ProotManager.DesktopEnvironment.XFCE4,
        addons: Set<ProotManager.DesktopAddon> = emptySet(),
        vncPort: Int? = null,
    ) {
        viewModelScope.launch {
            // Capture the port preference BEFORE the install kicks off
            // so the eventual startDesktop reads it on its first launch.
            // Port 0 from the dialog (e.g. user cleared the field) means
            // "keep auto"; we only persist non-zero overrides.
            if (vncPort != null && vncPort in 5901..5999) {
                desktopManager.setPortPreference(
                    distroId = prootManager.activeDistroId,
                    deId = de.spec.id,
                    port = vncPort,
                )
            }
            prootManager.setupDesktop(vncPassword, de)
            if (addons.isNotEmpty() &&
                prootManager.desktopState.value is ProotManager.DesktopSetupState.Complete
            ) {
                prootManager.installAddons(addons)
            }
        }
    }

    /**
     * Suggested default VNC port for a fresh install on the active
     * distro. Picks the lowest 5900+N not already pinned by another
     * installed DE or in use by a running session. UI reads this once
     * when the install dialog opens and stuffs it into the editable
     * field; subsequent typed edits replace it.
     */
    fun suggestVncPortFor(de: ProotManager.DesktopEnvironment): Int {
        val existing = desktopManager.getPortPreference(prootManager.activeDistroId, de.spec.id)
        if (existing in 5901..5999) return existing
        return desktopManager.suggestNextVncPort(prootManager.activeDistroId)
    }

    /** The stored port preference for [de], or null if auto-assign applies. */
    fun storedVncPortFor(de: ProotManager.DesktopEnvironment): Int? {
        val p = desktopManager.getPortPreference(prootManager.activeDistroId, de.spec.id)
        return if (p in 5901..5999) p else null
    }

    fun uninstallDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            desktopManager.stopDesktop(de)
            prootManager.uninstallDesktop(de)
            prootManager.resetDesktopState()
        }
    }

    /**
     * Start a DE and add it as a tab in the Desktop session viewer.
     * Replaces the old _navigateToVnc → ConnectionsScreen → DesktopViewModel
     * round-trip with a direct `addVncSession` (or `addWaylandTab`) call.
     */
    fun startDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch {
            val shellCmd = preferencesRepository.waylandShellCommand.first()
            Log.d(TAG, "startDesktop: ${de.label} shell=$shellCmd")
            withContext(Dispatchers.IO) {
                desktopManager.startDesktop(de, shellCmd)
            }
            if (de.isNative) {
                kotlinx.coroutines.delay(2000)
                addWaylandTab()
                return@launch
            }
            val port = desktopManager.getVncPort(de) ?: 5901
            // Wait up to 8 s for the Xvnc server to start listening,
            // racing the poll against the DesktopManager's own error
            // state — if the launch throws inside DesktopManager (e.g.
            // missing compositor binary on the Arch nested-Wayland path,
            // GlassHaven/Haven#162 bug B), the manager records ERROR +
            // an errorMessage and we exit the wait immediately with the
            // diagnostic instead of running out the full timeout and
            // leaving the user staring at a spinner. See #169.
            val outcome = withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    val managerInstance = desktopManager.desktops.value[de]
                    if (managerInstance?.state == DesktopManager.DesktopState.ERROR) {
                        return@withContext DesktopStartOutcome.Error(
                            managerInstance.errorMessage ?: "Couldn't start ${de.label}",
                        )
                    }
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        return@withContext DesktopStartOutcome.Ready
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(500)
                    }
                }
                DesktopStartOutcome.Timeout
            }
            when (outcome) {
                is DesktopStartOutcome.Ready -> {
                    // Confirm to the manager that the desktop is up so
                    // the row's status dot flips from amber STARTING to
                    // green RUNNING. The manager keeps STARTING until
                    // we signal — see DesktopManager.markRunning.
                    desktopManager.markRunning(de)
                }
                is DesktopStartOutcome.Error -> {
                    Log.e(TAG, "startDesktop: ${de.label} failed — ${outcome.message}")
                    val activeDistro = prootManager.activeDistroId
                    // Surface to the user via a toast + the Manage row's
                    // ERROR chip (DesktopManager already holds the state).
                    // NOT persisted to ConnectionLog: that table has a
                    // foreign key to connection_profiles, and a desktop has
                    // no profile row — inserting a synthetic id crashed the
                    // app with SQLITE_CONSTRAINT_FOREIGNKEY on every failing
                    // nested-Wayland start (Sway/Hyprland/Niri), the
                    // regression the #169/#162-B error-surfacing introduced.
                    _userMessages.emit("Couldn't start ${de.label} on $activeDistro: ${outcome.message}")
                    desktopManager.stopDesktop(de)
                    return@launch
                }
                is DesktopStartOutcome.Timeout -> {
                    Log.e(TAG, "startDesktop: VNC port $port not listening after 8s")
                    _userMessages.emit("${de.label} didn't come up within 8s")
                    desktopManager.stopDesktop(de)
                    return@launch
                }
            }
            val pwd = prootManager.storedVncPassword
                ?: connectionRepository.getAll()
                    .firstOrNull { it.isVnc && it.host == "localhost" }
                    ?.vncPassword
            addVncSession(
                host = "localhost",
                port = port,
                password = pwd,
                username = null,
                sshForward = false,
                sshSessionId = null,
                profileId = null,
                colorDepth = "BPP_24_TRUE",
            )
        }
    }

    fun stopDesktop(de: ProotManager.DesktopEnvironment) {
        viewModelScope.launch(Dispatchers.IO) {
            desktopManager.stopDesktop(de)
        }
    }

    /**
     * Open an in-app VNC viewer for any running desktop that doesn't have
     * one yet. A desktop started outside the UI start path — notably via
     * the MCP `start_desktop` tool, which brings up the compositor +
     * wayvnc but can't open a viewer — leaves the Sessions view empty.
     * Called when the Sessions/monitor view is shown so the
     * recently-started session actually connects. addVncSession dedupes
     * by host:port, so this is safe to call repeatedly and won't disturb
     * an already-open viewer. Native (labwc) desktops use the Wayland tab
     * path instead and are skipped here.
     */
    fun connectRunningDesktopViewers() {
        viewModelScope.launch {
            val pwd = prootManager.storedVncPassword
                ?: connectionRepository.getAll()
                    .firstOrNull { it.isVnc && it.host == "localhost" }
                    ?.vncPassword
            desktopManager.desktops.value.forEach { (de, inst) ->
                if (inst.state == DesktopManager.DesktopState.RUNNING && !de.isNative) {
                    addVncSession(
                        host = "localhost",
                        port = inst.vncPort,
                        password = pwd,
                        username = null,
                        sshForward = false,
                        sshSessionId = null,
                        profileId = null,
                        colorDepth = "BPP_24_TRUE",
                    )
                }
            }
        }
    }

    fun resetDesktopSetupState() {
        prootManager.resetDesktopState()
    }

    /**
     * Retry the failed install phase (issue #162 Phase 3d). Dispatches
     * via `ProotManager.retry()` which inspects the current Error
     * state and re-runs just the failing layer (or wipes-and-retries
     * for the destructive Download/Extract phases).
     */
    fun retryRootfsInstall() {
        viewModelScope.launch { prootManager.retry() }
    }

    init {
        // Workspace launcher posts here when a DESKTOP / WAYLAND item
        // fires. The matching pager switch happens in HavenNavHost.
        // DesktopViewModel is hoisted to nav scope so emissions always
        // land regardless of which tab the user is currently viewing.
        viewModelScope.launch {
            agentUiCommandBus.commands.collect { command ->
                when (command) {
                    is sh.haven.core.data.agent.AgentUiCommand.OpenRemoteDesktop ->
                        openRemoteDesktopForProfile(command.profileId)
                    is sh.haven.core.data.agent.AgentUiCommand.OpenWaylandDesktop ->
                        addWaylandTab()
                    else -> { /* handled by other collectors */ }
                }
            }
        }
    }

    /**
     * Resolve [profileId] to a VNC or RDP profile and dispatch to the
     * matching `add*Session`. Used by the workspace launcher; for
     * tunneled profiles, picks the first connected SSH session for the
     * tunnel profile and lets `addVncSession` / `addRdpSession` throw
     * the existing "SSH session not found" error if none is up.
     */
    private fun openRemoteDesktopForProfile(profileId: String) {
        viewModelScope.launch {
            val profile = connectionRepository.getById(profileId)
            if (profile == null) {
                Log.w(TAG, "OpenRemoteDesktop: profile $profileId not found")
                return@launch
            }
            when {
                profile.isVnc -> {
                    val sshSessionId =
                        if (profile.vncSshForward && profile.vncSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.vncSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addVncSession(
                        host = profile.host,
                        port = profile.vncPort ?: 5900,
                        password = profile.vncPassword,
                        username = profile.vncUsername,
                        sshForward = profile.vncSshForward,
                        sshSessionId = sshSessionId,
                        profileId = profile.id,
                        colorDepth = profile.vncColorDepth,
                    )
                }
                profile.isRdp -> {
                    val sshSessionId =
                        if (profile.rdpSshForward && profile.rdpSshProfileId != null) {
                            sshSessionManager.getSessionsForProfile(profile.rdpSshProfileId!!)
                                .firstOrNull { it.status.name == "CONNECTED" }
                                ?.sessionId
                        } else null
                    addRdpSession(
                        host = profile.host,
                        port = profile.rdpPort,
                        username = profile.rdpUsername.orEmpty(),
                        password = profile.rdpPassword.orEmpty(),
                        domain = profile.rdpDomain.orEmpty(),
                        sshForward = profile.rdpSshForward,
                        sshSessionId = sshSessionId,
                        sshProfileId = profile.rdpSshProfileId,
                        profileId = profile.id,
                        useNla = profile.rdpUseNla,
                        colorDepth = profile.rdpColorDepth,
                    )
                }
                else -> Log.w(
                    TAG,
                    "OpenRemoteDesktop: ${profile.label} is ${profile.connectionType}, not VNC/RDP",
                )
            }
        }
    }

    /**
     * Transient user-facing messages from background tasks (desktop start
     * failures, etc.) that need to surface as a Toast/Snackbar. Collected
     * by DesktopScreen. SharedFlow with replay=0 so a screen rotation
     * doesn't re-fire the same message.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private val _tabs = MutableStateFlow<List<DesktopTab>>(emptyList())
    val tabs: StateFlow<List<DesktopTab>> = _tabs.asStateFlow()

    private val _activeTabIndex = MutableStateFlow(0)
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    /** The currently active tab, derived for convenience. */
    val activeTab: StateFlow<DesktopTab?> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Whether the active tab is connected (used to disable pager swipe). */
    val activeTabConnected: StateFlow<Boolean> = combine(_tabs, _activeTabIndex) { tabs, idx ->
        tabs.getOrNull(idx)?.connected?.value == true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // --- Tab management ---

    fun selectTab(index: Int) {
        val tabs = _tabs.value
        if (index in tabs.indices) {
            pauseAllExcept(index)
            _activeTabIndex.value = index
        }
    }

    fun moveTab(fromIndex: Int, direction: Int) {
        val tabs = _tabs.value.toMutableList()
        val toIndex = fromIndex + direction
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return
        val tab = tabs.removeAt(fromIndex)
        tabs.add(toIndex, tab)
        _tabs.value = tabs
        if (_activeTabIndex.value == fromIndex) _activeTabIndex.value = toIndex
        else if (_activeTabIndex.value == toIndex) _activeTabIndex.value = fromIndex
    }

    /**
     * User dismissed the bandwidth-suggestion banner — clear it; the
     * session-side bandwidthSuggestionFired flag stops it re-firing.
     */
    fun dismissBandwidthSuggestion(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } as? DesktopTab.Vnc ?: return
        tab._bandwidthSuggestion.value = null
    }

    /**
     * User accepted the bandwidth-suggestion banner — persist the new
     * colour depth on the profile (if any), close the existing tab, and
     * reconnect with the new depth.
     */
    fun acceptBandwidthSuggestion(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.id == tabId } as? DesktopTab.Vnc ?: return
        val newDepth = tab._bandwidthSuggestion.value ?: return
        val pid = tab.profileId
        viewModelScope.launch(Dispatchers.IO) {
            if (pid != null) {
                connectionRepository.getById(pid)?.let { existing ->
                    if (existing.vncColorDepth != newDepth) {
                        connectionRepository.save(existing.copy(vncColorDepth = newDepth))
                    }
                }
            }
            // Snapshot before close, since closeTab disposes the tab.
            val host = tab.originalHost.ifEmpty { return@launch }
            val port = tab.originalPort
            val username = tab.originalUsername
            val password = tab.originalPassword
            val sshForward = tab.sshForward
            val sshSessionId = tab.sshSessionId
            withContext(Dispatchers.Main) { closeTab(tabId) }
            addVncSession(
                host = host,
                port = port,
                password = password,
                username = username,
                sshForward = sshForward,
                sshSessionId = sshSessionId,
                profileId = pid,
                colorDepth = newDepth,
            )
        }
    }

    fun closeTab(tabId: String) {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        disconnectTab(tab)
        _tabs.value = tabs
        if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
            _activeTabIndex.value = tabs.size - 1
        }
        pauseAllExcept(_activeTabIndex.value)
    }

    // --- Tab deduplication ---

    /**
     * Find an existing tab matching a connection. Matches by profileId first,
     * then by host:port for VNC or host:port:username for RDP.
     * Returns the tab index, or -1 if not found.
     */
    private fun findExistingTab(
        profileId: String?,
        host: String,
        port: Int,
        protocol: String,
        username: String? = null,
    ): Int {
        val tabs = _tabs.value
        // Match by profileId if available
        if (profileId != null) {
            val idx = tabs.indexOfFirst { tab ->
                when (tab) {
                    is DesktopTab.Vnc -> tab.profileId == profileId
                    is DesktopTab.Rdp -> tab.profileId == profileId
                    else -> false
                }
            }
            if (idx >= 0) return idx
        }
        // Match by host+port (and username for RDP)
        return tabs.indexOfFirst { tab ->
            when {
                protocol == "VNC" && tab is DesktopTab.Vnc && tab.profileId == null ->
                    tab.label == "$host:$port"
                protocol == "RDP" && tab is DesktopTab.Rdp && tab.profileId == null ->
                    tab.label == "$host:$port"
                else -> false
            }
        }
    }

    // --- VNC sessions ---

    fun addVncSession(
        host: String,
        port: Int,
        password: String?,
        username: String? = null,
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        profileId: String? = null,
        colorDepth: String = "BPP_24_TRUE",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "VNC")
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    // Already connected — just switch to it
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                // Disconnected/errored — close old tab before creating new one
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            // Per-tab live state, declared up front so the tab can be shown in
            // a connecting state (connected=false, no frame) before the
            // synchronous handshake runs — mirroring RDP, which previously was
            // the only protocol to show "connecting".
            val connected = MutableStateFlow(false)
            val frame = MutableStateFlow<Bitmap?>(null)
            val error = MutableStateFlow<String?>(null)
            val cursor = MutableStateFlow<sh.haven.feature.vnc.CursorOverlay?>(null)
            val pointerPos = MutableStateFlow(0 to 0)
            val bandwidthSuggestion = MutableStateFlow<String?>(null)

            // Hoisted out of try so the catch / onError can clean them up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelPort: Int? = null
            var tunnelSessionId: String? = null
            var tunnelLease: SshSessionManager.TunnelLease? = null
            var tabAdded = false
            try {
                val actualHost: String
                val actualPort: Int
                // WireGuard / Tailscale TunneledConnection — non-null when
                // the profile has tunnelConfigId set and we're not going
                // through SSH RemoteForward instead.
                var tunneledConn: TunneledConnection? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    // Tunnel target is 127.0.0.1 on the SSH server, not the
                    // SSH profile's external host. VNC servers like wayvnc
                    // typically bind loopback only; using the SSH host's
                    // external address makes sshd try to connect back to
                    // itself over the public interface and hit ECONNREFUSED.
                    // This was #104's actual cause; the earlier 127.0.0.1
                    // fix in VncScreen covered the pure VNC→tunnel path
                    // but missed the saved-on-SSH Desktop-tab path.
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", port)
                    tunnelPort = lp
                    tunnelSessionId = sshSessionId
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "VNC SSH tunnel: localhost:$lp -> 127.0.0.1:$port (via $host)")
                    // Tie this tab to the SSH session: if the SSH is torn down
                    // for any reason (Connections-list disconnect, network
                    // death, jump cascade), the lease fires and closes the tab
                    // instead of leaving it over a dead pipe (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    // Try the WireGuard / Tailscale path. Returns null when
                    // the profile has no tunnelConfigId — caller falls
                    // through to a direct kernel-socket dial in client.start.
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunneledConn = tunnelResolver.dial(profile, actualHost, actualPort, 30_000)
                            if (tunneledConn != null) {
                                Log.d(TAG, "VNC dialed via tunnel ${profile.tunnelConfigId} -> $actualHost:$actualPort")
                            }
                        }
                    }
                }

                val config = VncConfig().apply {
                    this.colorDepth = runCatching { ColorDepth.valueOf(colorDepth) }
                        .getOrDefault(ColorDepth.BPP_24_TRUE)
                    shared = true
                    if (!password.isNullOrEmpty()) passwordSupplier = { password }
                    if (!username.isNullOrEmpty()) usernameSupplier = { username }
                    onScreenUpdate = { bitmap -> frame.value = bitmap }
                    onCursorUpdate = { bmp, hx, hy ->
                        cursor.value = if (bmp == null) null else sh.haven.feature.vnc.CursorOverlay(bmp, hx, hy)
                    }
                    onBandwidthSuggestion = { suggested ->
                        // Only surface if the global preference is on. If
                        // the user's already dismissed the banner this
                        // session, the StateFlow is set to null and the
                        // session-side flag (bandwidthSuggestionFired)
                        // prevents re-firing.
                        viewModelScope.launch {
                            if (preferencesRepository.bandwidthAutoSuggest.first()) {
                                bandwidthSuggestion.value = suggested.name
                            }
                        }
                    }
                    onError = { e ->
                        Log.e(TAG, "VNC error on tab $tabId", e)
                        error.value = VncViewModel.describeError(e, host, port)
                        connected.value = false
                        desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                        // VncClient.start() catches setup failures (e.g. the
                        // handshake EOF when no VNC server is listening behind
                        // the SSH forward) and routes them here instead of
                        // throwing, so the catch block below never runs for
                        // them — and a mid-session drop lands here too. Release
                        // the SSH tunnel (lease) + any WG/Tailscale dependent so
                        // nothing is left orphaned with a green dot (#121).
                        // Idempotent, so the later disconnectTab stays safe.
                        tunnelLease?.close()
                        releaseSshTunnelDependent(profileId)
                    }
                    onRemoteClipboard = { text ->
                        Log.d(TAG, "VNC clipboard ($tabId): ${text.take(50)}")
                    }
                }

                val client = VncClient(config)

                // Add the tab now (connected=false) so the Desktop screen
                // shows a connecting state during the (synchronous) handshake,
                // like RDP does via onConnected. The handshake below flips
                // _connected in place. A connected tab keeps its lease so it's
                // torn down with the SSH session; a failed handshake leaves the
                // error visible (onError already released the lease, and that
                // release removed the lease before any SSH teardown could fire
                // its parent-gone callback, so the error tab isn't auto-closed).
                val newTab = DesktopTab.Vnc(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    client = client,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    _cursor = cursor,
                    _pointerPos = pointerPos,
                    _bandwidthSuggestion = bandwidthSuggestion,
                    tunnelPort = tunnelPort,
                    tunnelSessionId = tunnelSessionId,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                    originalHost = host,
                    originalPort = port,
                    originalUsername = username,
                    originalPassword = password,
                    sshForward = sshForward,
                    sshSessionId = sshSessionId,
                    colorDepth = colorDepth,
                )
                _tabs.value = _tabs.value.toMutableList().apply { add(newTab) }
                tabAdded = true
                pauseAllExcept(_tabs.value.size - 1)
                _activeTabIndex.value = _tabs.value.size - 1
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)

                val tc = tunneledConn
                if (tc != null) {
                    client.start(TunneledSocket(tc, actualHost, actualPort), actualHost)
                } else {
                    // Knock only on the direct path. SSH-forward goes via
                    // a localhost tunnel (handled at the SSH connect site)
                    // and a userspace WG/Tailscale tunnel doesn't expose
                    // raw kernel sockets for knockd to see.
                    if (!sshForward && profileId != null) {
                        runVncKnockIfConfigured(profileId, actualHost)
                    }
                    client.start(actualHost, actualPort)
                }
                // start() runs the handshake synchronously and swallows setup
                // failures into onError (above), which has flagged the error +
                // set the registry to ERROR. Only flip to connected on success.
                if (error.value == null) {
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VNC connect failed", e)
                // Connect-failure cleanup (#121): release the SSH tunnel lease
                // (removes the forward + releases the dependent, tearing down
                // the SSH iff it was opened solely for this tunnel) and any
                // WG/Tailscale dependent. Idempotent / no-op if the failure
                // happened before the lease was acquired.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                error.value = VncViewModel.describeError(e, host, port)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // If the failure happened before the tab was added (e.g. tunnel
                // setup threw), surface an error tab so the user sees why.
                if (!tabAdded) {
                    val errorTab = DesktopTab.Vnc(
                        id = tabId,
                        label = label,
                        colorTag = colorTag,
                        client = VncClient(VncConfig()),
                        _connected = connected,
                        _frame = frame,
                        _error = error,
                        _cursor = cursor,
                        _pointerPos = pointerPos,
                        _bandwidthSuggestion = bandwidthSuggestion,
                        profileId = profileId,
                    )
                    val tabs = _tabs.value.toMutableList()
                    tabs.add(errorTab)
                    _tabs.value = tabs
                    _activeTabIndex.value = tabs.size - 1
                }
            }
        }
    }

    // --- RDP sessions ---

    fun addRdpSession(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String = "",
        sshForward: Boolean = false,
        sshSessionId: String? = null,
        sshProfileId: String? = null,
        profileId: String? = null,
        useNla: Boolean = true,
        colorDepth: Int = 16,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Deduplicate: if a tab for the same connection exists, reuse or replace
            val existingIdx = findExistingTab(profileId, host, port, "RDP", username)
            if (existingIdx >= 0) {
                val existing = _tabs.value[existingIdx]
                if (existing.connected.value) {
                    pauseAllExcept(existingIdx)
                    _activeTabIndex.value = existingIdx
                    return@launch
                }
                closeTab(existing.id)
            }

            val label = resolveLabel(profileId) ?: "$host:$port"
            val colorTag = resolveColorTag(profileId)
            val tabId = UUID.randomUUID().toString()

            // Hoisted out of try so the catch / onError can clean them up
            // when the dial fails (#121). tunnelLease owns the forward +
            // dependent release + the parent-gone teardown callback.
            var tunnelPort: Int? = null
            var tunnelSessionId: String? = null
            var tunnelLease: SshSessionManager.TunnelLease? = null
            try {
                val actualHost: String
                val actualPort: Int

                // SOCKS5 endpoint of any WireGuard / Tailscale tunnel the
                // profile selected (#149 step 4 + 9). Only consulted when
                // not going through SSH RemoteForward — that already
                // tunnels the connection via 127.0.0.1:<localPort>.
                var rdpSocksProxy: sh.haven.rdp.SocksProxyConfig? = null

                if (sshForward && sshSessionId != null) {
                    val sshClient = findSshClient(sshSessionId)
                        ?: throw IllegalStateException("SSH session not found")
                    val lp = sshClient.setPortForwardingL("127.0.0.1", 0, host, port)
                    tunnelPort = lp
                    tunnelSessionId = sshSessionId
                    actualHost = "127.0.0.1"
                    actualPort = lp
                    Log.d(TAG, "RDP SSH tunnel: localhost:$lp -> $host:$port")
                    // Tie this tab to the SSH session so it closes if the SSH
                    // is torn down for any reason (#121).
                    if (profileId != null) {
                        tunnelLease = sshSessionManager.acquireTunnelLease(
                            sessionId = sshSessionId,
                            dependentProfileId = profileId,
                            localForwardPort = lp,
                        ) { viewModelScope.launch { closeTab(tabId) } }
                    }
                } else {
                    actualHost = host
                    actualPort = port
                    if (profileId != null) {
                        val profile = connectionRepository.getById(profileId)
                        if (profile != null) {
                            tunnelResolver.socksEndpoint(profile)?.let { endpoint ->
                                rdpSocksProxy = sh.haven.rdp.SocksProxyConfig(
                                    host = endpoint.address.hostString,
                                    port = endpoint.address.port.toUShort(),
                                    username = endpoint.username,
                                    password = endpoint.password,
                                )
                                Log.d(TAG, "RDP routed via SOCKS5 $endpoint -> $host:$port")
                            }
                        }
                    }
                }

                val connected = MutableStateFlow(false)
                val frame = MutableStateFlow<Bitmap?>(null)
                val error = MutableStateFlow<String?>(null)

                val verboseEnabled = preferencesRepository.verboseLoggingEnabled.first()
                val verboseBuffer = if (verboseEnabled) ConcurrentLinkedQueue<String>() else null

                val session = RdpSession(
                    sessionId = "rdp-$tabId",
                    host = actualHost,
                    port = actualPort,
                    username = username,
                    password = password,
                    domain = domain,
                    useNla = useNla,
                    colorDepth = colorDepth,
                    verboseBuffer = verboseBuffer,
                    socksProxy = rdpSocksProxy,
                )
                session.onFrameUpdate = { bitmap -> frame.value = bitmap }
                session.onError = { e ->
                    Log.e(TAG, "RDP error on tab $tabId", e)
                    error.value = RdpViewModel.describeError(e, host, port)
                    connected.value = false
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                        }
                    }
                    // RdpSession.start() reports connect failures via this
                    // callback rather than throwing, so the catch block never
                    // runs for them — release the SSH tunnel lease + any WG
                    // dependent so nothing lingers with a green dot (#121,
                    // same shape as the VNC path). Idempotent with disconnectTab.
                    tunnelLease?.close()
                    releaseSshTunnelDependent(profileId)
                }
                session.onConnected = { _, _ ->
                    // Real handshake complete — only now flip the tab to
                    // "connected". Before this the UI stays on a Connecting
                    // state rather than a misleading empty framebuffer.
                    connected.value = true
                    desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTED)
                    if (profileId != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val startLog = session.drainVerboseLog()
                            connectionLogRepository.logEvent(profileId, ConnectionLog.Status.CONNECTED, verboseLog = startLog)
                        }
                    }
                }

                // Knock only on the direct path. SSH-forward goes via a
                // localhost tunnel (knock happened at the SSH connect)
                // and the SOCKS path runs through a userspace tunnel
                // that knockd can't observe.
                if (!sshForward && rdpSocksProxy == null && profileId != null) {
                    runVncKnockIfConfigured(profileId, actualHost)
                }

                desktopSessionRegistry.setStatus(profileId, DesktopStatus.CONNECTING)
                session.start()
                // NB: intentionally no `connected.value = true` here — that
                // happens in session.onConnected once the Rust worker
                // thread completes the handshake.

                val tab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = session,
                    _connected = connected,
                    _frame = frame,
                    _error = error,
                    tunnelPort = tunnelPort,
                    tunnelSessionId = tunnelSessionId,
                    tunnelLease = tunnelLease,
                    profileId = profileId,
                )

                val tabs = _tabs.value.toMutableList()
                tabs.add(tab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            } catch (e: Exception) {
                Log.e(TAG, "RDP connect failed", e)
                if (profileId != null) {
                    connectionLogRepository.logEvent(profileId, ConnectionLog.Status.FAILED, details = e.message)
                }
                // Connect-failure cleanup — same reasoning as the VNC
                // path above (#121): release the lease + WG dependent.
                tunnelLease?.close()
                releaseSshTunnelDependent(profileId)
                desktopSessionRegistry.setStatus(profileId, DesktopStatus.ERROR)
                // Show error in a temporary tab (no session to close)
                val errorTab = DesktopTab.Rdp(
                    id = tabId,
                    label = label,
                    colorTag = colorTag,
                    session = RdpSession("err", host, port, username, password, domain),
                    _error = MutableStateFlow(RdpViewModel.describeError(e, host, port)),
                    profileId = profileId,
                )
                val tabs = _tabs.value.toMutableList()
                tabs.add(errorTab)
                _tabs.value = tabs
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- Wayland ---

    fun addWaylandTab() {
        val tabs = _tabs.value
        val existing = tabs.indexOfFirst { it is DesktopTab.Wayland }
        if (existing >= 0) {
            selectTab(existing)
            return
        }
        val newTabs = tabs.toMutableList()
        newTabs.add(DesktopTab.Wayland())
        _tabs.value = newTabs
        _activeTabIndex.value = newTabs.size - 1
    }

    fun removeWaylandTab() {
        val tabs = _tabs.value.toMutableList()
        val index = tabs.indexOfFirst { it is DesktopTab.Wayland }
        if (index >= 0) {
            tabs.removeAt(index)
            _tabs.value = tabs
            if (_activeTabIndex.value >= tabs.size && tabs.isNotEmpty()) {
                _activeTabIndex.value = tabs.size - 1
            }
        }
    }

    // --- Input forwarding (operates on active tab) ---

    fun sendPointer(x: Int, y: Int) {
        // Mirror the latest pointer into per-tab state so the UI overlay
        // (cursor / virtual cursor seed) repaints immediately, without
        // waiting for the IO dispatch round-trip.
        when (val tab = activeTab.value) {
            is DesktopTab.Vnc -> tab._pointerPos.value = x to y
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseMove(x, y)
        }
    }

    fun pressButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseButton(button, pressed = true)
        }
    }

    fun releaseButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseButton(button, pressed = false)
        }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        when (val tab = activeTab.value) {
            is DesktopTab.Vnc -> tab._pointerPos.value = x to y
            is DesktopTab.Rdp -> tab._pointerPos.value = x to y
            else -> {}
        }
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseClick(x, y, button)
        }
    }

    fun sendVncKey(keySym: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Vnc)?.client?.updateKey(keySym, pressed)
        }
    }

    fun typeVncKey(keySym: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Vnc)?.client?.type(keySym)
        }
    }

    /**
     * Type a string sequentially via the active VNC tab. Single coroutine
     * so key down/up events stay in source order on the wire — see
     * [VncClient.typeText]. Also pushes the text to the remote VNC
     * clipboard as defence-in-depth (Ctrl+V on the remote then works
     * regardless of synth-typing fidelity).
     */
    fun typeVncText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = (activeTab.value as? DesktopTab.Vnc)?.client ?: return@launch
            client.copyText(text)
            client.typeText(text)
        }
    }

    fun sendRdpKey(scancode: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            (activeTab.value as? DesktopTab.Rdp)?.session?.sendKey(scancode, pressed)
        }
    }

    fun typeRdpUnicode(codepoint: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = (activeTab.value as? DesktopTab.Rdp)?.session ?: return@launch
            s.sendUnicodeKey(codepoint, true)
            s.sendUnicodeKey(codepoint, false)
        }
    }

    /**
     * Per-Desktop-view orientation override. Stored in the ViewModel
     * so it survives any composable subtree recreation that happens
     * when the conditional tab-bar in DesktopScreen comes/goes during
     * an orientation change — that recreation reset a Composable-
     * local `remember` state to its initial value and the
     * LaunchedEffect immediately wrote that back, so the lock never
     * took effect.
     *
     * Default is UNSPECIFIED (auto / follow system) so the Desktop
     * tab behaves like its neighbours when no desktop session is
     * active and the user hasn't explicitly chosen landscape —
     * the previous LANDSCAPE default rotated the activity as soon
     * as the user landed on the empty Desktop tab, breaking the
     * left/right swipe-to-change-tab gesture for anyone using
     * Haven without a desktop connection. Users who want landscape
     * cycle to it via the orientation toolbar button.
     *
     * Values are the raw `ActivityInfo.SCREEN_ORIENTATION_*`
     * constants since the enum is private to feature modules. Cycle
     * order from the toolbar button: Auto -> Landscape -> Portrait
     * -> Auto.
     */
    private val _desktopOrientation = MutableStateFlow(
        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    )
    val desktopOrientation: StateFlow<Int> = _desktopOrientation.asStateFlow()

    fun cycleDesktopOrientation() {
        _desktopOrientation.value = when (_desktopOrientation.value) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    fun scrollUp() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseWheel(deltaY = 1)
        }
    }

    fun scrollDown() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTab.value?.remoteDesktop?.sendMouseWheel(deltaY = -1)
        }
    }

    // --- SSH tunnel helpers ---

    fun getActiveSshSessions(): List<SshTunnelOption> {
        val ssh = sshSessionManager.activeSessions.map { session ->
            SshTunnelOption(session.sessionId, session.label, session.profileId)
        }
        val mosh = moshSessionManager.activeSessions
            .filter { it.sshClient != null }
            .map { SshTunnelOption(it.sessionId, "${it.label} (Mosh)", it.profileId) }
        val et = etSessionManager.activeSessions
            .filter { it.sshClient != null }
            .map { SshTunnelOption(it.sessionId, "${it.label} (ET)", it.profileId) }
        return ssh + mosh + et
    }

    /** Knock against the VNC/RDP host using the profile's saved sequence,
     *  if any. Failures are logged but not thrown; the real socket open
     *  surfaces the actual symptom. */
    private suspend fun runVncKnockIfConfigured(profileId: String, host: String) {
        val profile = connectionRepository.getById(profileId) ?: return
        val seq = KnockSequence.parse(
            profile.portKnockSequence,
            delayMs = profile.portKnockDelayMs,
        ).getOrNull() ?: return
        val result = portKnocker.knock(host, seq)
        Log.d(
            TAG,
            if (result.ok) "[knock] ${seq.format()} -> ok in ${result.totalDurationMs}ms"
            else "[knock] ${seq.format()} -> failed after ${result.totalDurationMs}ms: ${result.error?.message}"
        )
    }

    private fun findSshClient(sessionId: String): SshClient? {
        sshSessionManager.getSession(sessionId)?.let { return it.client }
        moshSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        etSessionManager.sessions.value[sessionId]?.sshClient?.let { return it as? SshClient }
        return null
    }

    // --- Lifecycle ---

    private fun disconnectTab(tab: DesktopTab) {
        viewModelScope.launch(Dispatchers.IO) {
            when (tab) {
                is DesktopTab.Vnc -> {
                    tab.client.stop()
                    // SSH side via the lease (removes the forward + releases
                    // the dependent, tearing the SSH down if it was opened
                    // solely for this tunnel). No-op if already closed by a
                    // parent-gone cascade. releaseSshTunnelDependent also
                    // covers the WG/Tailscale dependent (and is idempotent).
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                }
                is DesktopTab.Rdp -> {
                    if (tab.profileId != null) {
                        val verboseLog = tab.session.drainVerboseLog()
                        connectionLogRepository.logEvent(tab.profileId, ConnectionLog.Status.DISCONNECTED, verboseLog = verboseLog)
                    }
                    tab.session.close()
                    tab.tunnelLease?.close()
                    releaseSshTunnelDependent(tab.profileId)
                    desktopSessionRegistry.clear(tab.profileId)
                }
                is DesktopTab.Wayland -> {} // compositor lifecycle managed externally
            }
        }
    }

    /**
     * Decrement refcounts on any auto-opened tunnels this profile holds.
     *
     * SSH-side: the v5.24.85 wiring in [ConnectionsViewModel.disconnect]
     * called this for connections-tab disconnects, but the bottom-of-
     * Desktop-tab "Disconnect" button routes through [closeTab] /
     * [disconnectTab] instead — without this call the auto-opened SSH
     * idled on with a green dot in the connections list (#121,
     * KoriKraut on v5.24.89).
     *
     * WireGuard / Tailscale side: a profile that dialled through
     * [TunnelResolver.dial] holds a slot in [TunnelManager]'s dependent
     * set. Release here so the underlying tunnel tears down when the
     * last dependent disconnects (#149).
     *
     * No-op when [profileId] is null (older tabs / Wayland) or when the
     * profile was never registered as a tunnel dependent on either side.
     */
    private fun releaseSshTunnelDependent(profileId: String?) {
        if (profileId == null) return
        sshSessionManager.releaseTunnelDependent(profileId)
        viewModelScope.launch { tunnelResolver.release(profileId) }
    }


    private fun pauseAllExcept(activeIndex: Int) {
        _tabs.value.forEachIndexed { index, tab ->
            val rd = tab.remoteDesktop ?: return@forEachIndexed
            if (index == activeIndex) rd.resume() else rd.pause()
        }
    }

    private suspend fun resolveLabel(profileId: String?): String? {
        if (profileId == null) return null
        return try {
            connectionRepository.getById(profileId)?.label
        } catch (_: Exception) { null }
    }

    private suspend fun resolveColorTag(profileId: String?): Int {
        if (profileId == null) return 0
        return try {
            connectionRepository.getById(profileId)?.colorTag ?: 0
        } catch (_: Exception) { 0 }
    }

    override fun onCleared() {
        super.onCleared()
        _tabs.value.forEach { disconnectTab(it) }
    }
}
