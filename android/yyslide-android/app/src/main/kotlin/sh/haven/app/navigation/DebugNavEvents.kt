package sh.haven.app.navigation

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Singleton channel for debug-build navigation requests.
 *
 * The debug-only [sh.haven.app.debug.DebugReceiver] emits route names here;
 * [HavenNavHost] collects them and scrolls the pager. In release builds nothing
 * ever emits, so the collector is effectively a no-op.
 */
object DebugNavEvents {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val requests: SharedFlow<String> = _requests

    fun emit(route: String) {
        _requests.tryEmit(route)
    }
}
