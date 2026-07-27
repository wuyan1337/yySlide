package sh.haven.app.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import sh.haven.app.R
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentRequest
import javax.inject.Inject

/**
 * ViewModel wrapper around the app-scoped [AgentConsentManager] so the
 * [ConsentHost] composable can subscribe with the standard Hilt + Compose
 * idiom (`hiltViewModel()`). The manager is `@Singleton` so the
 * subscription survives configuration changes; the ViewModel is just a
 * thin pass-through.
 */
@HiltViewModel
internal class ConsentHostViewModel @Inject constructor(
    private val consentManager: AgentConsentManager,
) : ViewModel() {

    val pending: StateFlow<List<ConsentRequest>> = consentManager.pending

    fun respond(
        requestId: Long,
        decision: ConsentDecision,
        bypassClient: Boolean = false,
        allowForMinutes: Int? = null,
    ) {
        viewModelScope.launch {
            consentManager.respond(requestId, decision, bypassClient, allowForMinutes)
        }
    }
}

/**
 * Top-of-tree host for agent-driven consent prompts. Mounted from
 * `MainActivity.setContent { ... }` so the modal sheet floats above
 * whatever screen is active.
 *
 * The host renders the **oldest** pending [ConsentRequest] — usually the
 * only one, since [AgentConsentManager.requestConsent] suspends on a
 * single deferred per call — and dismisses itself when the user resolves
 * it. If a second request piles up while the first is on screen, this
 * composable just keeps showing the first; the second slides into view
 * once the first is answered.
 *
 * The sheet is intentionally non-skippable: tapping outside the sheet
 * does *not* dismiss it. The user must explicitly tap Allow or Deny so
 * we never accidentally swallow a destructive request as "denied by
 * default" via a stray tap. Pressing the system back button maps to
 * Deny, which is the safe direction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsentHost(viewModel: ConsentHostViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val upstream = pending.firstOrNull()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Render against a locally-held snapshot rather than `upstream`
    // directly. When a request resolves or times out the manager clears it
    // from `pending`; removing the ModalBottomSheet from composition the
    // instant that happens tears its modal window down mid-animation and
    // leaves an invisible full-screen scrim that swallows ALL touch input
    // until the process is killed — the "UI frozen after a consent timeout"
    // bug, which also makes a subsequent Allow look like it never
    // registered (so the sheet reappears on the next resume). Instead we
    // keep the sheet composed and animate it out via sheetState.hide(),
    // dropping the snapshot only once the sheet is actually gone.
    var displayed by remember { mutableStateOf<ConsentRequest?>(null) }
    LaunchedEffect(upstream) {
        val u = upstream
        if (u != null) {
            displayed = u
            if (!sheetState.isVisible) sheetState.show()
        } else if (displayed != null) {
            runCatching { sheetState.hide() }
            displayed = null
        }
    }
    val current = displayed ?: return

    val isPairing = current.toolName == AgentConsentManager.PAIRING_TOOL_NAME
    val clientHint = current.clientHint?.takeIf { it.isNotBlank() }

    // "Allow all MCP requests from this client until app restart" — an
    // opt-in escape hatch for sessions where the user is iterating with
    // an agent and is happy to skip per-call prompts. Suppressed on
    // pairing requests (the user hasn't authorised the client at all
    // yet — bypass on the same dialog would be a contradiction) and
    // when there's no clientHint to key the bypass against.
    val canOfferBypass = !isPairing && clientHint != null
    var bypassChecked by remember(current.id) { mutableStateOf(false) }

    // Treat any dismissal that isn't an explicit Allow as a Deny so the
    // wheel-stays-with-the-user invariant holds even on edge cases.
    fun resolve(decision: ConsentDecision, allowForMinutes: Int? = null) {
        val bypass = canOfferBypass && bypassChecked && decision == ConsentDecision.ALLOW
        viewModel.respond(current.id, decision, bypass, allowForMinutes)
        // respond() clears the request from `pending`; the sync LaunchedEffect
        // above then animates the sheet out and tears its window down cleanly.
        // Don't call sheetState.hide() here too — a manual hide racing the
        // composition removal is what left the stuck input scrim behind.
    }

    ModalBottomSheet(
        onDismissRequest = { resolve(ConsentDecision.DENY) },
        sheetState = sheetState,
        // Lock the sheet open against accidental tap-outs. The user has
        // to make an explicit choice.
        scrimColor = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(
                    if (isPairing) R.string.agent_consent_pair_title else R.string.agent_consent_action_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            // For non-pairing requests show the "From: <client>" line —
            // pairing requests already have the client name in their
            // summary body so it'd be redundant.
            if (!isPairing) {
                clientHint?.let { hint ->
                    Text(
                        text = stringResource(R.string.agent_consent_from, hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text(
                text = current.summary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            if (!isPairing) {
                Text(
                    text = stringResource(R.string.agent_consent_tool, current.toolName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canOfferBypass) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { bypassChecked = !bypassChecked },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = bypassChecked,
                        onCheckedChange = { bypassChecked = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.agent_consent_allow_all_from_client, clientHint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.agent_consent_allow_all_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // "Allow for N min" — power-user time-windowed allow. Only
            // surfaces on requests that opt in (currently the
            // queue_terminal_input pre-delivery prompt). Subsequent
            // identical requests within the window short-circuit to
            // ALLOW so an agent driving the REPL doesn't trigger a
            // prompt per character.
            var allowMinutes by remember(current.id) { mutableStateOf(10) }
            if (current.offerTimedAllow) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_consent_auto_allow_window), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    listOf(5, 10, 30).forEach { minutes ->
                        OutlinedButton(
                            onClick = { allowMinutes = minutes },
                            colors = if (allowMinutes == minutes) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else ButtonDefaults.outlinedButtonColors(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) { Text(stringResource(R.string.agent_consent_minutes_short, minutes)) }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = { resolve(ConsentDecision.DENY) }) {
                    Text(stringResource(R.string.agent_deny))
                }
                if (current.offerTimedAllow) {
                    OutlinedButton(
                        onClick = { resolve(ConsentDecision.ALLOW, allowForMinutes = allowMinutes) },
                    ) {
                        Text(stringResource(R.string.agent_consent_allow_for_minutes, allowMinutes))
                    }
                }
                Button(
                    onClick = { resolve(ConsentDecision.ALLOW) },
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(
                        if (isPairing) stringResource(R.string.agent_consent_pair_action) else stringResource(R.string.common_allow),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
