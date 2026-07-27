package sh.haven.core.data.agent

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which files have been published as MCP `serve_file` download
 * URLs and are still considered "live" — used by the Files view to
 * render a chip on rows the agent has fresh-fetched-or-can-fetch.
 *
 * Each entry has a TTL ([DEFAULT_TTL_MS], 5 minutes by default) that
 * matches typical consent staleness — if the agent didn't act on the
 * URL within that window the human is unlikely to still be looking at
 * an "in-flight" download. The TTL is also a backstop so a leaked URL
 * doesn't keep the chip on screen indefinitely.
 *
 * Tracker state is in-memory only. Restarting Haven (or the MCP server)
 * clears it; that matches the underlying [SftpStreamServer] which
 * regenerates its random auth token on every start, invalidating any
 * URLs from a previous run.
 */
@Singleton
class ServedFileTracker @Inject constructor() {

    /** A single live serve_file publication. */
    data class Entry(
        val profileId: String,
        val path: String,
        val publishedAtMillis: Long,
        val expiresAtMillis: Long,
    )

    private val _active = MutableStateFlow<Set<Entry>>(emptySet())
    val active: StateFlow<Set<Entry>> = _active.asStateFlow()

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Mark ([profileId], [path]) as currently being served. If an entry
     * already exists for the same pair, its expiry is refreshed —
     * calling serve_file twice on the same file shouldn't make two
     * chips appear.
     */
    fun register(profileId: String, path: String, ttlMs: Long = DEFAULT_TTL_MS) {
        val now = System.currentTimeMillis()
        val entry = Entry(
            profileId = profileId,
            path = path,
            publishedAtMillis = now,
            expiresAtMillis = now + ttlMs,
        )
        _active.value = _active.value.filterNot {
            it.profileId == profileId && it.path == path
        }.toSet() + entry
        handler.postDelayed({ unregisterIfExpired(profileId, path) }, ttlMs)
    }

    /** Drop ([profileId], [path]) immediately (called manually if the call fails). */
    fun unregister(profileId: String, path: String) {
        _active.value = _active.value.filterNot {
            it.profileId == profileId && it.path == path
        }.toSet()
    }

    private fun unregisterIfExpired(profileId: String, path: String) {
        val now = System.currentTimeMillis()
        _active.value = _active.value.filterNot {
            it.profileId == profileId && it.path == path && it.expiresAtMillis <= now
        }.toSet()
    }

    /** Convenience: is this (profile, path) pair currently being served? */
    fun isServed(profileId: String, path: String): Boolean =
        _active.value.any { it.profileId == profileId && it.path == path }

    companion object {
        const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000L
    }
}
