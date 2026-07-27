package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GuestServiceManager"

/**
 * Supervises long-lived helper processes that run *inside* the proot guest
 * — most importantly app-native MCP servers (KiCad/FreeCAD/OpenSCAD) that
 * an agent drives for structured control. Modelled on [DesktopManager]'s
 * long-lived-process pattern (a tracked [Process] per service + a daemon
 * reader thread that tees output to a rolling tail and flips state on exit).
 *
 * Unlike the `run_in_proot` background-job path (which is tied to the MCP
 * tool's coroutine scope and never restarts), a registered service is owned
 * by this singleton and re-launched on app start via [startAutostart], so it
 * survives Haven restarts. The registry is persisted as a JSON marker in the
 * active distro's rootfs (`root/.haven-services`), mirroring how
 * [ProotManager] persists `root/.haven-desktop` / `root/.haven-addons` — so
 * it is naturally per-distro and survives restarts.
 *
 * Services run in the **active** distro (commands go through
 * [ProotManager.startCommandInProot], which targets `activeRootfsDir`). The
 * registry lives in that rootfs, so reading it only ever yields the active
 * distro's services.
 */
@Singleton
class GuestServiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prootManager: ProotManager,
) {
    enum class ServiceState { STOPPED, STARTING, RUNNING, ERROR }

    data class GuestServiceSpec(
        val id: String,
        val label: String,
        /** Shell command run via `/bin/sh -lc` in the active proot guest. */
        val command: String,
        /** Loopback TCP port the service listens on inside the guest. */
        val port: Int,
        /** Re-launch automatically when Haven's MCP endpoint comes up. */
        val autostart: Boolean,
        /**
         * True when this service is itself a streamable-HTTP MCP server
         * (e.g. a KiCad MCP). Haven aggregates such servers' tools into its
         * own MCP surface, namespaced, so the agent talks only to Haven.
         */
        val isMcp: Boolean = false,
        /** HTTP path of the guest MCP endpoint (when [isMcp]). */
        val mcpPath: String = "/mcp",
    )

    data class GuestServiceInstance(
        val spec: GuestServiceSpec,
        val state: ServiceState,
        val errorMessage: String? = null,
    )

    private val _services = MutableStateFlow<Map<String, GuestServiceInstance>>(emptyMap())
    val services: StateFlow<Map<String, GuestServiceInstance>> = _services.asStateFlow()

    private val processes = mutableMapOf<String, Process>()
    private val logTails = mutableMapOf<String, ArrayDeque<String>>()
    private val logTailLimit = 30

    // ---- registry persistence (active rootfs marker) ----

    private fun markerFile(): File = File(prootManager.activeRootfsDir, "root/.haven-services")

    /** Registered service specs for the active distro (filesystem-backed). */
    fun registered(): List<GuestServiceSpec> {
        val f = markerFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifBlank { return@mapNotNull null }
                GuestServiceSpec(
                    id = id,
                    label = o.optString("label", id),
                    command = o.optString("command"),
                    port = o.optInt("port"),
                    autostart = o.optBoolean("autostart", false),
                    isMcp = o.optBoolean("isMcp", false),
                    mcpPath = o.optString("mcpPath", "/mcp"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $f", e)
            emptyList()
        }
    }

    private fun persist(specs: List<GuestServiceSpec>) {
        val arr = JSONArray()
        specs.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("label", s.label)
                    put("command", s.command)
                    put("port", s.port)
                    put("autostart", s.autostart)
                    put("isMcp", s.isMcp)
                    put("mcpPath", s.mcpPath)
                },
            )
        }
        val f = markerFile()
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
    }

    /** Register (or replace) a service spec by id and persist it. */
    fun register(spec: GuestServiceSpec) {
        val updated = registered().filterNot { it.id == spec.id } + spec
        persist(updated)
    }

    /** Stop (if running) and remove a service from the registry. */
    fun unregister(id: String) {
        stop(id)
        persist(registered().filterNot { it.id == id })
        _services.update { it - id }
    }

    // ---- supervision ----

    /** Start a registered service by id. No-op if already running. */
    fun start(id: String) {
        val spec = registered().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("No registered guest service '$id'")
        if (processes.containsKey(id)) {
            Log.d(TAG, "Service ${spec.label} already running")
            return
        }
        _services.update { it + (id to GuestServiceInstance(spec, ServiceState.STARTING)) }
        synchronized(logTails) { logTails[id] = ArrayDeque() }
        try {
            val process = prootManager.startCommandInProot(spec.command)
            processes[id] = process
            _services.update { it + (id to GuestServiceInstance(spec, ServiceState.RUNNING)) }

            // Daemon reader: tee stdout/stderr into the rolling tail; on exit,
            // flip state. A long-lived server only exits on crash/stop, so an
            // exit while we still think it's RUNNING is surfaced as ERROR with
            // the tail attached (same shape as DesktopManager's exit thread).
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(logTails) {
                            val tail = logTails[id] ?: return@synchronized
                            tail.addLast(line)
                            while (tail.size > logTailLimit) tail.removeFirst()
                        }
                    }
                } catch (_: Exception) {
                }
                val exit = try { process.waitFor() } catch (_: Exception) { -1 }
                processes.remove(id)
                val wasRunning = _services.value[id]?.state == ServiceState.RUNNING
                val tail = synchronized(logTails) { logTails.remove(id)?.toList().orEmpty() }
                _services.update { current ->
                    val inst = current[id] ?: return@update current
                    if (wasRunning) {
                        current + (
                            id to inst.copy(
                                state = ServiceState.ERROR,
                                errorMessage = "exited (code $exit): " +
                                    tail.takeLast(6).joinToString("\n"),
                            )
                            )
                    } else {
                        // Expected stop() — drop to STOPPED.
                        current + (id to inst.copy(state = ServiceState.STOPPED))
                    }
                }
                Log.d(TAG, "Guest service ${spec.label} exited: $exit")
            }, "guest-svc-${spec.id}").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start guest service ${spec.label}", e)
            processes.remove(id)
            synchronized(logTails) { logTails.remove(id) }
            _services.update {
                it + (id to GuestServiceInstance(spec, ServiceState.ERROR, e.message))
            }
        }
    }

    /** Stop a running service (best-effort). Children die via proot --kill-on-exit. */
    fun stop(id: String) {
        val inst = _services.value[id]
        // Mark STOPPED first so the reader thread treats the exit as expected.
        if (inst != null) {
            _services.update { it + (id to inst.copy(state = ServiceState.STOPPED)) }
        }
        processes.remove(id)?.destroyForcibly()
    }

    /** Re-launch every registered service flagged autostart (called on app start). */
    fun startAutostart() {
        registered().filter { it.autostart }.forEach { spec ->
            try {
                start(spec.id)
            } catch (e: Exception) {
                Log.w(TAG, "autostart of ${spec.label} failed", e)
            }
        }
    }

    /** Stop all running services. */
    fun stopAll() {
        processes.keys.toList().forEach { stop(it) }
    }

    /** Ports of currently-RUNNING services — used to multiplex reverse forwards. */
    fun runningPorts(): List<Int> =
        _services.value.values
            .filter { it.state == ServiceState.RUNNING }
            .map { it.spec.port }
            .filter { it > 0 }

    /**
     * Registered MCP services (isMcp) that are currently RUNNING on a valid
     * loopback port — the set Haven aggregates into its own MCP surface.
     */
    fun runningMcpServices(): List<GuestServiceSpec> {
        val running = _services.value
        return registered().filter { spec ->
            spec.isMcp && spec.port > 0 && running[spec.id]?.state == ServiceState.RUNNING
        }
    }

    /** Rolling output tail for a service (diagnostics). */
    fun logTailFor(id: String): List<String> =
        synchronized(logTails) { logTails[id]?.toList().orEmpty() }
}
