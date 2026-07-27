package sh.haven.app

import android.app.Application
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.stepca.CertRenewalWorker
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application(), Configuration.Provider {

    @Inject lateinit var mcpServer: sh.haven.app.agent.McpServer
    @Inject lateinit var mcpTunnelManager: sh.haven.app.agent.McpTunnelManager
    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var workspaceShortcutManager: sh.haven.app.workspace.WorkspaceShortcutManager
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prootManager: sh.haven.core.local.ProotManager
    @Inject lateinit var guestServiceManager: sh.haven.core.local.GuestServiceManager
    @Inject lateinit var sessionManagerRegistry: sh.haven.core.ssh.SessionManagerRegistry

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Required by [Configuration.Provider] so the Hilt-aware worker
     * factory is wired before any [androidx.work.WorkManager] lookup.
     * [CertRenewalWorker] (#133 phase 2b) needs `@AssistedInject` deps,
     * which require this. Other workers (e.g. ReticulumWorker) keep
     * working unchanged — plain-constructor workers don't go through
     * the factory.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Apply the saved theme override to AppCompatDelegate before any
        // activity is created, so the cold-launch splash window uses the
        // user's chosen mode rather than only the system uiMode (#153).
        // Synchronous DataStore read is intentional here — must happen
        // before the first activity, and the cost is bounded.
        val savedMode = try {
            runBlocking { preferencesRepository.theme.first() }
        } catch (_: Exception) {
            UserPreferencesRepository.ThemeMode.SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(savedMode.toNightMode())

        // Register Shizuku binder listeners early so the async callback
        // has time to fire before any UI checks isShizukuAvailable().
        sh.haven.core.local.WaylandSocketHelper.initShizukuListeners()

        // Mirror saved workspaces into Android launcher long-press
        // shortcuts so the home-screen icon offers "Open <workspace>"
        // entries. Self-observing — recomputes on every repo change.
        workspaceShortcutManager.start()

        // MCP agent endpoint is OFF by default — it exposes state that
        // local processes (or an AI agent you've pointed at it) can
        // query, so it must be an explicit opt-in. When the user toggles
        // it in Settings we react by starting or stopping the server.
        //
        // We also advertise the endpoint to the PRoot rootfs by writing
        // a ready-to-merge MCP server config JSON to
        // /root/.config/haven/mcp-servers.json, so any MCP client the
        // user has installed in PRoot can pick it up with a one-liner.
        // When the endpoint is disabled the file is removed again.
        preferencesRepository.mcpAgentEndpointEnabled
            .distinctUntilChanged()
            .onEach { enabled ->
                if (enabled) {
                    mcpServer.start()
                    advertiseEndpointToProot()
                    // Relaunch supervised guest MCP servers (e.g. a KiCad MCP)
                    // for the active distro before the tunnel comes up, so
                    // establish() snapshots their ports into the forward set.
                    guestServiceManager.startAutostart()
                    // Bring up the dedicated, headless reverse tunnel that
                    // keeps the loopback endpoint reachable off-device as
                    // the network roams. No-op when no endpoint profile is
                    // configured (on-device / adb-forward only). Uses the
                    // live bound port so an 8731+ fallback follows.
                    mcpTunnelManager.start(mcpServer.port)
                    // Bind the MCP HTTP listener to the foreground
                    // service's lifecycle. Without this, the OS reclaims
                    // the app process within seconds of backgrounding
                    // and the accept loop dies — agents lose their
                    // connection and have to re-pair. The MCP endpoint
                    // is itself a "long-lived user-initiated connection"
                    // in Android 14+/16 FGS terms, so it belongs to the
                    // same `specialUse` FGS the SSH/Mosh/VNC/RDP/SFTP
                    // sessions use. The McpForegroundParticipantModule
                    // contributes both a session-listing entry (so the
                    // notification shows "MCP agent endpoint") and a
                    // ForegroundKeepAlive entry (so an SSH disconnect
                    // doesn't tear down the FGS while MCP is still on).
                    startSessionServiceIfPossible()
                } else {
                    mcpTunnelManager.stop()
                    mcpServer.stop()
                    guestServiceManager.stopAll()
                    removeEndpointFromProot()
                    // Stop the FGS only if nothing else is keeping it
                    // alive. hasActiveSessions now includes the MCP
                    // keep-alive, which is `false` post-stop, so this
                    // boils down to "any other transport active?".
                    if (!sessionManagerRegistry.hasActiveSessions()) {
                        stopService(Intent(this, sh.haven.core.ssh.SshConnectionService::class.java))
                    }
                }
            }
            .launchIn(appScope)

        // Re-home the MCP reverse tunnel when the user changes the endpoint
        // profile while the server is already running. drop(1) skips the
        // initial replayed value — startup is handled by the toggle observer
        // above, so this only reacts to genuine later changes.
        preferencesRepository.mcpTunnelEndpointProfileId
            .drop(1)
            .distinctUntilChanged()
            .onEach {
                if (mcpServer.isRunning) {
                    mcpTunnelManager.start(mcpServer.port)
                }
            }
            .launchIn(appScope)

        // Bind/unbind the MCP WireGuard listener when the user toggles it
        // while the server is running. drop(1): start() reads the initial
        // value itself, so this only reacts to later changes.
        preferencesRepository.mcpWireguardEnabled
            .drop(1)
            .distinctUntilChanged()
            .onEach { mcpServer.setWireguardEnabled(it) }
            .launchIn(appScope)

        // Re-home the MCP reverse tunnel when the set of RUNNING guest services
        // changes (e.g. one started/stopped via the MCP tools after the tunnel
        // was already up), so its loopback port is multiplexed onto -R. drop(1)
        // skips the initial replay — startup autostart is handled in the enable
        // branch before the tunnel starts. No-op when the server isn't running.
        guestServiceManager.services
            .map { svcs ->
                svcs.values
                    .filter { it.state == sh.haven.core.local.GuestServiceManager.ServiceState.RUNNING }
                    .map { it.spec.port }
                    .toSortedSet()
            }
            .distinctUntilChanged()
            .drop(1)
            .onEach { if (mcpServer.isRunning) mcpTunnelManager.refreshForwards() }
            .launchIn(appScope)

        // Schedule the daily step-ca cert-renewal check (#133 phase 2b).
        // Idempotent (KEEP policy); cheap when the user has no CAs
        // configured — the worker enumerates SshKeys and exits early.
        CertRenewalWorker.schedule(this)
    }

    /**
     * Path to the advertised MCP config file inside the extracted
     * rootfs. The file is visible as `/root/.config/haven/
     * mcp-servers.json` from inside PRoot.
     */
    private val prootMcpConfigFile: File
        get() = File(
            prootManager.activeRootfsDir,
            "root/.config/haven/mcp-servers.json",
        )

    private fun advertiseEndpointToProot() {
        val rootfsDir = prootManager.activeRootfsDir
        if (!rootfsDir.exists()) return
        val json = mcpServer.mcpServerConfigJson ?: return
        try {
            val target = prootMcpConfigFile
            target.parentFile?.mkdirs()
            target.writeText(json)
            android.util.Log.d("yySlideApp", "advertised MCP endpoint to ${target.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.w("yySlideApp", "failed to advertise MCP endpoint to PRoot: ${e.message}")
        }
    }

    /**
     * Start [SshConnectionService] from the Application context. The
     * service's onStartCommand calls startForeground within Android's
     * 5-second budget, so this is safe from a non-Activity context
     * provided the MCP toggle was changed while the app is in the
     * foreground (it always is — the toggle lives in Settings). If a
     * background restart ever flips the preference (none currently
     * does), the OS may refuse the start; the catch keeps Haven alive.
     */
    private fun startSessionServiceIfPossible() {
        try {
            val intent = Intent(this, sh.haven.core.ssh.SshConnectionService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.w("yySlideApp", "startForegroundService for MCP keep-alive failed: ${e.message}")
        }
    }

    private fun removeEndpointFromProot() {
        try {
            val target = prootMcpConfigFile
            if (target.exists()) {
                target.delete()
                android.util.Log.d("yySlideApp", "removed advertised MCP endpoint from PRoot")
            }
        } catch (_: Exception) {
            // Best-effort cleanup
        }
    }
}

internal fun UserPreferencesRepository.ThemeMode.toNightMode(): Int = when (this) {
    UserPreferencesRepository.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
    UserPreferencesRepository.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    UserPreferencesRepository.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}
