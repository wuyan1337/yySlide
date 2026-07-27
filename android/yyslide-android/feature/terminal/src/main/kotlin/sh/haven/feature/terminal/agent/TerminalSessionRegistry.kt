package sh.haven.feature.terminal.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.connectbot.terminal.GestureInjector
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulator
import sh.haven.feature.terminal.OscHandler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton index of live terminal handles by sessionId. Populated by
 * [sh.haven.feature.terminal.TerminalViewModel] when a tab is created
 * and by [sh.haven.feature.terminal.TerminalScreen] when its Compose
 * scope acquires the per-Composition controllers (selection, scroll).
 *
 * Exists so the MCP transport (`app/.../agent/McpTools.kt`) can read
 * and drive the terminal without going through a Compose scope: the
 * registry is the bridge between the agent thread and the controllers
 * that only exist while the Terminal composable is in the tree.
 *
 * Entries persist while the tab is open; they are cleared when the
 * tab is closed (TerminalViewModel) and the controller fields are
 * cleared/repopulated as the Composable enters/leaves the tree.
 */
@Singleton
class TerminalSessionRegistry @Inject constructor() {

    /**
     * Snapshot of one tab's agent-reachable handles. The emulator is
     * always present (created with the tab); selection/scroll are null
     * until the tab's Composable mounts and its callbacks fire.
     *
     * The remaining fields are populated by [setAgentHandles] from
     * TerminalViewModel's tab-sync loop. They stay null for headless
     * agent shells (which have no OSC handler / mouse tracker / output
     * pipeline of their own) — callers must null-check.
     *
     * @param mouseMode true while the remote app has any xterm mouse
     *        mode (1000/1002/1003) active.
     * @param activeMouseMode the highest active mouse mode number, or null.
     * @param bracketPasteMode true while bracketed-paste mode (2004) is on.
     * @param oscHandler the per-tab OSC scanner; exposes last-seen OSC
     *        events for test assertions.
     * @param feedOutput injects raw bytes through the tab's real output
     *        pipeline (OSC scan → mouse-mode scan → emulator), exactly as
     *        if the bytes had arrived from the remote.
     * @param gestureInjector drives synthetic touch gestures through the
     *        terminal's real pointer pipeline; null until the tab's
     *        Composable mounts (set via [setGestureInjector]).
     */
    data class Entry(
        val emulator: TerminalEmulator,
        val selectionController: SelectionController? = null,
        val scrollController: ScrollController? = null,
        val mouseMode: StateFlow<Boolean>? = null,
        val activeMouseMode: StateFlow<Int?>? = null,
        val bracketPasteMode: StateFlow<Boolean>? = null,
        val oscHandler: OscHandler? = null,
        val feedOutput: ((ByteArray, Int, Int) -> Unit)? = null,
        val gestureInjector: GestureInjector? = null,
    )

    private val _sessions = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val sessions: StateFlow<Map<String, Entry>> = _sessions

    fun register(sessionId: String, emulator: TerminalEmulator) {
        _sessions.value = _sessions.value + (sessionId to Entry(emulator))
    }

    fun setSelectionController(sessionId: String, controller: SelectionController?) {
        val current = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (sessionId to current.copy(selectionController = controller))
    }

    fun setScrollController(sessionId: String, controller: ScrollController?) {
        val current = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (sessionId to current.copy(scrollController = controller))
    }

    fun setGestureInjector(sessionId: String, injector: GestureInjector?) {
        val current = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (sessionId to current.copy(gestureInjector = injector))
    }

    /**
     * Attach the tab-owned handles used by the agent test tools
     * (mouse-mode flows, OSC handler, raw-output pipeline). Called from
     * TerminalViewModel's tab-sync loop once per tab; a no-op if the
     * session isn't registered yet.
     */
    fun setAgentHandles(
        sessionId: String,
        mouseMode: StateFlow<Boolean>,
        activeMouseMode: StateFlow<Int?>,
        bracketPasteMode: StateFlow<Boolean>,
        oscHandler: OscHandler,
        feedOutput: (ByteArray, Int, Int) -> Unit,
    ) {
        val current = _sessions.value[sessionId] ?: return
        _sessions.value = _sessions.value + (
            sessionId to current.copy(
                mouseMode = mouseMode,
                activeMouseMode = activeMouseMode,
                bracketPasteMode = bracketPasteMode,
                oscHandler = oscHandler,
                feedOutput = feedOutput,
            )
            )
    }

    fun unregister(sessionId: String) {
        _sessions.value = _sessions.value - sessionId
    }

    fun get(sessionId: String): Entry? = _sessions.value[sessionId]
}
