package sh.haven.core.ssh

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NetworkMonitor"

/**
 * Monitors default network changes via ConnectivityManager.
 * Emits [Event.Available] when a network becomes usable and [Event.Lost] when lost.
 * Used by [SshConnectionService] to trigger immediate reconnect instead of waiting
 * for TCP timeout (15-30s).
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed class Event {
        data object Available : Event()
        data object Lost : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE declared in app manifest
    fun start() {
        if (callback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
                _events.tryEmit(Event.Available)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                _events.tryEmit(Event.Lost)
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        Log.d(TAG, "Started monitoring network changes")
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
        Log.d(TAG, "Stopped monitoring network changes")
    }
}
