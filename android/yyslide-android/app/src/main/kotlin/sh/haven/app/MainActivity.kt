package sh.haven.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import sh.haven.app.agent.ConsentHost
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.app.navigation.HavenNavHost
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ui.KeyEventInterceptor
import sh.haven.core.ui.theme.HavenTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var biometricAuthenticator: BiometricAuthenticator
    // Eagerly injected to trigger one-time password encryption migration
    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository
    @Inject lateinit var stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository
    @Inject lateinit var renewalRequestStore: sh.haven.core.stepca.RenewalRequestStore
    // Published to FidoAuthenticator in onResume so NFC reader mode can
    // be enabled during FIDO2 SSH assertions. Without this, Nitrokey /
    // SoloKey / YubiKey-over-NFC flows never saw a Tag (#15).
    @Inject lateinit var fidoAuthenticator: FidoAuthenticator
    // Tracks foreground state so AgentConsentManager can fail-closed
    // when there's no activity to render the prompt. The §85 rule
    // forbids letting destructive agent calls slip through silently.
    @Inject lateinit var agentConsentManager: AgentConsentManager
    // Cross-tab navigation verbs: HavenNavHost collects from this so an
    // MCP `navigate_sftp_browser` switches the pager to the right tab.
    @Inject lateinit var agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus
    // The Settings screen must use the listener's actual bound port and
    // authenticated client configuration. McpServer can fall back from
    // 8730 to 8731..8739, so reconstructing this data in the feature
    // module produces stale and unauthenticated copy-to-clipboard values.
    @Inject lateinit var mcpServer: sh.haven.app.agent.McpServer
    // Holds a validated notification request until the Keys screen is
    // mounted. A StateFlow avoids losing a cold-start request before the
    // pager and its ViewModel collectors exist.
    private val pendingRenewalKeyId = MutableStateFlow<String?>(null)
    // Keystore biometric gate: a fetch on a BIOMETRIC_PROTECTED entry
    // queues a request here, this Activity collects, runs
    // BiometricPrompt, and posts the decision back. Foreground tracking
    // mirrors AgentConsentManager (fail-closed when backgrounded).
    @Inject lateinit var biometricGate: sh.haven.core.data.keystore.BiometricGate
    // Workspace launcher (singleton) — invoked when a launcher
    // long-press shortcut routes the workspace id through onCreate /
    // onNewIntent.
    @Inject lateinit var workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher

    private fun exitIfDisconnected() {
        if (SshConnectionService.disconnectedAll) {
            Log.d("MainActivity", "Disconnect All detected — exiting")
            SshConnectionService.clearDisconnectedAll()
            finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        exitIfDisconnected()
        fidoAuthenticator.setActiveActivity(this)
        agentConsentManager.setForegroundActive(true)
        biometricGate.setForegroundActive(true)
    }

    override fun onPause() {
        fidoAuthenticator.clearActiveActivity(this)
        agentConsentManager.setForegroundActive(false)
        biometricGate.setForegroundActive(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        exitIfDisconnected()
        handleWorkspaceShortcut(intent)
        handleRenewCertIntent(intent)
    }

    /**
     * Validate and consume the one-time capability attached to a renewal
     * notification. MainActivity is exported for the launcher, so checking
     * only an action or key-id extra would let another app trigger key
     * rotation. The private nonce makes this an authenticated app-internal
     * request even though the target activity itself is exported.
     */
    private fun handleRenewCertIntent(intent: Intent?) {
        if (intent?.action != sh.haven.core.stepca.RenewalNotifier.ACTION_RENEW_CERT) return
        val keyId = intent.getStringExtra(sh.haven.core.stepca.RenewalNotifier.EXTRA_KEY_ID).orEmpty()
        val nonce = intent.getStringExtra(sh.haven.core.stepca.RenewalNotifier.EXTRA_NONCE).orEmpty()

        // Clear first so configuration changes and task re-delivery cannot
        // replay the request, irrespective of whether validation succeeds.
        intent.action = null
        intent.removeExtra(sh.haven.core.stepca.RenewalNotifier.EXTRA_KEY_ID)
        intent.removeExtra(sh.haven.core.stepca.RenewalNotifier.EXTRA_NONCE)

        if (!renewalRequestStore.consume(keyId, nonce)) {
            Log.w("MainActivity", "Rejected invalid or replayed certificate renewal intent")
            return
        }
        lifecycleScope.launch {
            val key = sshKeyRepository.getById(keyId)
            if (key == null || key.caConfigId == null) {
                Log.w("MainActivity", "Rejected renewal intent for missing/non-step-ca key")
                return@launch
            }
            pendingRenewalKeyId.value = keyId
        }
    }

    /**
     * If [intent] carries a workspace launch action (long-press shortcut
     * tap, or a `compose_workspace` MCP call routed through here in a
     * future patch), kick off the launcher. Idempotent — extras are
     * cleared once consumed so a configuration change doesn't replay.
     */
    private fun handleWorkspaceShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.workspace.WorkspaceShortcutManager.ACTION_LAUNCH_WORKSPACE) return
        val workspaceId = intent.getStringExtra(
            sh.haven.app.workspace.WorkspaceShortcutManager.EXTRA_WORKSPACE_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.workspace.WorkspaceShortcutManager.EXTRA_WORKSPACE_ID)
        Log.d("MainActivity", "launching workspace $workspaceId from shortcut")
        MainScope().launch {
            workspaceLauncher.launch(workspaceId)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyEventInterceptor.handler?.let { interceptor ->
            if (interceptor(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWorkspaceShortcut(intent)
        handleRenewCertIntent(intent)
        setContent {
            // Prevent screenshots/screen recording when enabled
            val screenSecurity by preferencesRepository.screenSecurity
                .collectAsState(initial = false)
            LaunchedEffect(screenSecurity) {
                if (screenSecurity) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val themeMode by preferencesRepository.theme
                .collectAsState(initial = UserPreferencesRepository.ThemeMode.SYSTEM)
            // Mirror the user's choice to AppCompatDelegate so the next
            // cold-launch splash window matches without waiting for the
            // Application to re-read DataStore (#153 follow-up).
            LaunchedEffect(themeMode) {
                AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
            }
            val darkTheme = when (themeMode) {
                UserPreferencesRepository.ThemeMode.LIGHT -> false
                UserPreferencesRepository.ThemeMode.DARK -> true
                UserPreferencesRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            HavenTheme(darkTheme = darkTheme) {
                val renewalKeyId by pendingRenewalKeyId.collectAsState()
                val mcpEndpointUrl by mcpServer.endpointUrl.collectAsState()
                val mcpWireguardEndpointUrl by mcpServer.wireguardEndpointUrl.collectAsState()
                val biometricEnabled by preferencesRepository.biometricEnabled
                    .collectAsState(initial = false)
                val lockTimeout by preferencesRepository.lockTimeout
                    .collectAsState(initial = sh.haven.core.data.preferences.UserPreferencesRepository.LockTimeout.IMMEDIATE)
                var unlocked by remember { mutableStateOf(false) }
                var backgroundedAt by remember { mutableStateOf(0L) }

                // Re-lock when app goes to background, respecting timeout.
                // Minimum 5s grace period so file pickers, permission dialogs,
                // and other brief system activities don't trigger re-lock.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            backgroundedAt = System.currentTimeMillis()
                        }
                        if (event == Lifecycle.Event.ON_START && unlocked && backgroundedAt > 0) {
                            val elapsed = (System.currentTimeMillis() - backgroundedAt) / 1000
                            val effectiveTimeout = maxOf(lockTimeout.seconds, 5L)
                            if (elapsed >= effectiveTimeout) {
                                unlocked = false
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (biometricEnabled && !unlocked) {
                    BiometricLockScreen(
                        authenticator = biometricAuthenticator,
                        onUnlocked = { unlocked = true },
                    )
                } else {
                    HavenNavHost(
                        preferencesRepository = preferencesRepository,
                        connectionRepository = connectionRepository,
                        sshKeyRepository = sshKeyRepository,
                        stepCaConfigRepository = stepCaConfigRepository,
                        agentUiCommandBus = agentUiCommandBus,
                        mcpEndpointUrl = mcpEndpointUrl,
                        mcpWireguardEndpointUrl = mcpWireguardEndpointUrl,
                        mcpServerConfigJson = mcpServer.mcpServerConfigJson,
                        mcpWireguardServerConfigJson = mcpServer.wireguardMcpServerConfigJson,
                        renewalKeyId = renewalKeyId,
                        onRenewalRequestConsumed = { consumedKeyId ->
                            pendingRenewalKeyId.compareAndSet(consumedKeyId, null)
                        },
                    )
                    // Floats above whatever screen is active so an
                    // agent's consent prompt is unmissable. No-op when
                    // there are no pending requests.
                    ConsentHost()
                    // Same pattern for BIOMETRIC_PROTECTED keystore
                    // fetches — the gate publishes; this host renders
                    // BiometricPrompt; the result resumes the
                    // suspending fetch caller.
                    sh.haven.app.agent.BiometricGateHost()
                }
            }
        }
    }
}
