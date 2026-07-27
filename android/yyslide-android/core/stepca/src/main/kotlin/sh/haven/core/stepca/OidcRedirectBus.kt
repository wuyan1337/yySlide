package sh.haven.core.stepca

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process bridge between [OidcAuthClient] (which awaits the auth code)
 * and [OidcRedirectActivity] (which receives the deep-link with the code).
 * Keyed by the OAuth `state` parameter so concurrent flows don't cross.
 *
 * Lives in a singleton object because the redirect Activity is launched
 * by the system and has no way to inject scoped dependencies — the bus
 * is the only thing it can talk to, and the cost of a process-wide map
 * with at-most-a-handful-of-entries is negligible.
 */
internal object OidcRedirectBus {

    sealed interface Result {
        data class Code(val code: String) : Result
        data class Error(val message: String) : Result
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result>>()

    fun register(state: String): CompletableDeferred<Result> {
        val deferred = CompletableDeferred<Result>()
        pending[state] = deferred
        return deferred
    }

    fun complete(state: String, code: String) {
        pending.remove(state)?.complete(Result.Code(code))
    }

    fun fail(state: String, message: String) {
        pending.remove(state)?.complete(Result.Error(message))
    }

    /** Drop a pending entry without resolving it. */
    fun forget(state: String) {
        pending.remove(state)
    }
}
