package sh.haven.core.data.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Live connection state of a remote-desktop (VNC/RDP) tab. */
enum class DesktopStatus { CONNECTING, CONNECTED, ERROR }

/**
 * App-scoped mirror of the live state of remote-desktop tabs, keyed by the
 * connection profile id.
 *
 * Desktop tabs live in `DesktopViewModel`, but the connections list (a
 * separate ViewModel) needs to reflect whether a VNC/RDP profile's desktop is
 * connecting / connected so its row shows an accurate indicator. Rather than
 * coupling the two ViewModels, `DesktopViewModel` publishes per-profile status
 * here and `ConnectionsViewModel` observes it. This is the *real* desktop
 * status (the actual handshake), as opposed to merely "the SSH tunnel is up".
 */
@Singleton
class DesktopSessionRegistry @Inject constructor() {
    private val _statuses = MutableStateFlow<Map<String, DesktopStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DesktopStatus>> = _statuses.asStateFlow()

    /** Record [status] for [profileId] (no-op when [profileId] is null). */
    fun setStatus(profileId: String?, status: DesktopStatus) {
        if (profileId == null) return
        _statuses.update { it + (profileId to status) }
    }

    /** Drop any status for [profileId] — the desktop tab is gone. */
    fun clear(profileId: String?) {
        if (profileId == null) return
        _statuses.update { it - profileId }
    }
}
