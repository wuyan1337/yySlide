package sh.haven.core.rclone

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.rclone.bridge.RcloneBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RcloneClient"

/**
 * High-level Kotlin API over rclone's RC (Remote Control) JSON-RPC interface.
 * All methods are blocking and should be called from a background thread.
 */
@Singleton
class RcloneClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var initialized = false

    /** Initialize the rclone library. Safe to call multiple times. */
    fun initialize() {
        if (initialized) return
        val configDir = File(context.filesDir, "rclone")
        configDir.mkdirs()
        val configPath = File(configDir, "rclone.conf").absolutePath
        RcloneBridge.initialize(configPath)
        initialized = true
        Log.d(TAG, "Initialized with config: $configPath")
    }

    /** Shut down the rclone library. */
    fun shutdown() {
        if (!initialized) return
        RcloneBridge.shutdown()
        initialized = false
    }

    /**
     * Route outgoing rclone HTTP traffic through [socksUrl]
     * (e.g. `socks5://127.0.0.1:41234`) — typically the localhost SOCKS5
     * listener of one of Haven's per-profile WireGuard / Tailscale
     * tunnels (#149). Pass null to clear and route direct.
     *
     * Caveat: rclone caches HTTP clients per-fs, so this only reliably
     * affects fs instances created AFTER the call. The per-profile
     * connect path sets it before the first RPC for the profile.
     */
    fun setProxy(socksUrl: String?) {
        RcloneBridge.setProxy(socksUrl)
    }

    // ── Config operations ───────────────────────────────────────────────

    /** List all configured remote names. */
    fun listRemotes(): List<String> {
        val result = rpc("config/listremotes")
        val remotes = result.optJSONArray("remotes") ?: return emptyList()
        return (0 until remotes.length()).map { remotes.getString(it) }
    }

    /** Get configuration for a specific remote. */
    fun getRemoteConfig(name: String): Map<String, String> {
        val result = rpc("config/get", JSONObject().put("name", name))
        val map = mutableMapOf<String, String>()
        result.keys().forEach { key -> map[key] = result.optString(key, "") }
        return map
    }

    /** List available provider types with their metadata. */
    fun listProviders(): List<ProviderInfo> {
        val result = rpc("config/providers")
        val providers = result.optJSONArray("providers") ?: return emptyList()
        return (0 until providers.length()).map { i ->
            val p = providers.getJSONObject(i)
            ProviderInfo(
                name = p.getString("Name"),
                description = p.getString("Description"),
                prefix = p.optString("Prefix", ""),
                options = parseOptions(p.optJSONArray("Options")),
            )
        }
    }

    /**
     * Create a new remote configuration.
     *
     * For OAuth providers, this initiates the OAuth flow. The returned
     * [ConfigState] may contain an [ConfigState.authUrl] that needs to be
     * opened in a browser.
     */
    fun createRemote(name: String, type: String, parameters: Map<String, String> = emptyMap()): ConfigState {
        val params = JSONObject()
        params.put("name", name)
        params.put("type", type)
        params.put("parameters", JSONObject(parameters))
        val result = rpc("config/create", params)
        return parseConfigState(result)
    }

    /** Update an existing remote's configuration. */
    fun updateRemote(name: String, parameters: Map<String, String>) {
        val params = JSONObject()
        params.put("name", name)
        params.put("parameters", JSONObject(parameters))
        rpc("config/update", params)
    }

    /** Delete a remote configuration. */
    fun deleteRemote(name: String) {
        rpc("config/delete", JSONObject().put("name", name))
    }

    // ── File operations ─────────────────────────────────────────────────

    /**
     * List entries in a directory.
     *
     * @param remote remote name (e.g. "gdrive")
     * @param path   directory path within the remote (e.g. "/" or "Documents")
     */
    fun listDirectory(remote: String, path: String): List<RcloneFileEntry> {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        val result = rpc("operations/list", params)
        val list = result.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            RcloneFileEntry(
                name = item.getString("Name"),
                path = item.getString("Path"),
                size = item.optLong("Size", 0),
                mimeType = item.optString("MimeType", ""),
                modTime = item.optString("ModTime", ""),
                isDir = item.optBoolean("IsDir", false),
            )
        }
    }

    /** Create a directory. */
    fun mkdir(remote: String, path: String) {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        rpc("operations/mkdir", params)
    }

    /**
     * Copy a single file between remotes or within a remote.
     *
     * For local filesystem paths, pass the absolute directory as the remote
     * and the filename as the path (e.g. srcRemote="/data/.../cache", srcPath="file.txt").
     * For cloud remotes, pass the remote name (e.g. "gdrive") and the full path.
     */
    fun copyFile(
        srcRemote: String, srcPath: String,
        dstRemote: String, dstPath: String,
    ) {
        val params = JSONObject()
        // Local paths (absolute) don't get a colon suffix; named remotes do
        params.put("srcFs", if (srcRemote.startsWith("/")) srcRemote else "$srcRemote:")
        params.put("srcRemote", srcPath.trimStart('/'))
        params.put("dstFs", if (dstRemote.startsWith("/")) dstRemote else "$dstRemote:")
        params.put("dstRemote", dstPath.trimStart('/'))
        rpc("operations/copyfile", params)
    }

    /** Delete a single file. */
    fun deleteFile(remote: String, path: String) {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        rpc("operations/deletefile", params)
    }

    /** Delete a directory and all its contents. */
    fun deleteDir(remote: String, path: String) {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        rpc("operations/purge", params)
    }

    /** Get information about the remote (total/used/free space). */
    fun about(remote: String): RemoteInfo {
        val result = rpc("operations/about", JSONObject().put("fs", "$remote:"))
        return RemoteInfo(
            total = result.optLong("total", -1),
            used = result.optLong("used", -1),
            free = result.optLong("free", -1),
        )
    }

    /**
     * Move (rename) a single file within or between remotes.
     *
     * For local paths, pass the absolute directory as the remote.
     * For cloud remotes, pass the remote name (e.g. "gdrive").
     */
    fun moveFile(
        srcRemote: String, srcPath: String,
        dstRemote: String, dstPath: String,
    ) {
        val params = JSONObject()
        params.put("srcFs", if (srcRemote.startsWith("/")) srcRemote else "$srcRemote:")
        params.put("srcRemote", srcPath.trimStart('/'))
        params.put("dstFs", if (dstRemote.startsWith("/")) dstRemote else "$dstRemote:")
        params.put("dstRemote", dstPath.trimStart('/'))
        rpc("operations/movefile", params)
    }

    /** Generate a public link for a file or directory. */
    fun publicLink(remote: String, path: String): String {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        val result = rpc("operations/publiclink", params)
        return result.getString("url")
    }

    /**
     * Calculate total size of a directory tree.
     *
     * Uses operations/list with recurse + filesOnly + stripped metadata instead of
     * operations/size, which can be very slow on some backends due to per-file stat calls.
     * Also enables fast-list (UseListR) for backends that support single-call recursive listing.
     */
    fun directorySize(remote: String, path: String): DirectorySize {
        val params = JSONObject()
        params.put("fs", "$remote:")
        params.put("remote", path.trimStart('/'))
        params.put("opt", JSONObject().apply {
            put("recurse", true)
            put("filesOnly", true)
            put("noModTime", true)
            put("noMimeType", true)
        })
        params.put("_config", JSONObject().put("UseListR", true))
        val result = rpc("operations/list", params)
        val list = result.optJSONArray("list") ?: return DirectorySize(0, 0)
        var totalBytes = 0L
        for (i in 0 until list.length()) {
            totalBytes += list.getJSONObject(i).optLong("Size", 0)
        }
        return DirectorySize(count = list.length().toLong(), bytes = totalBytes)
    }

    // ── Remote capabilities ────────────────────────────────────────────

    private val capabilitiesCache = mutableMapOf<String, RemoteCapabilities>()

    /** Query what features a remote supports. Cached per remote name. */
    fun getCapabilities(remote: String): RemoteCapabilities {
        capabilitiesCache[remote]?.let { return it }
        val result = rpc("operations/fsinfo", JSONObject().put("fs", "$remote:"))
        val features = result.optJSONObject("Features") ?: return RemoteCapabilities()
        val caps = RemoteCapabilities(
            publicLink = features.optBoolean("PublicLink", false),
            move = features.optBoolean("Move", false),
            copy = features.optBoolean("Copy", false),
            purge = features.optBoolean("Purge", false),
            about = features.optBoolean("About", false),
        )
        capabilitiesCache[remote] = caps
        return caps
    }

    // ── Transfer stats ──────────────────────────────────────────────────

    /** Get current transfer statistics. */
    fun getStats(): TransferStats {
        val result = rpc("core/stats")
        return TransferStats(
            bytes = result.optLong("bytes", 0),
            totalBytes = result.optLong("totalBytes", 0),
            speed = result.optDouble("speed", 0.0),
            transfers = result.optInt("transfers", 0),
            totalTransfers = result.optInt("totalTransfers", 0),
            errors = result.optInt("errors", 0),
            deletes = result.optInt("deletes", 0),
            deletedDirs = result.optInt("deletedDirs", 0),
            lastError = result.optString("lastError", ""),
        )
    }

    // ── Sync operations ──────────────────────────────────────────────

    /** Active sync job ID, stored here (singleton) so it survives ViewModel recreation. */
    @Volatile
    var activeSyncJobId: Long? = null

    /**
     * Start an async rclone sync/copy/move operation.
     * Returns the job ID for status polling.
     */
    fun startSync(config: SyncConfig): Long {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val params = JSONObject()
        params.put("srcFs", config.srcFs)
        params.put("dstFs", config.dstFs)
        params.put("_async", true)

        // Build filter object
        val filters = config.filters
        if (filters.includePatterns.isNotEmpty() || filters.excludePatterns.isNotEmpty()
            || filters.minSize != null || filters.maxSize != null
        ) {
            val filterObj = JSONObject()
            if (filters.includePatterns.isNotEmpty()) {
                filterObj.put("IncludeRule", org.json.JSONArray(filters.includePatterns))
            }
            if (filters.excludePatterns.isNotEmpty()) {
                filterObj.put("ExcludeRule", org.json.JSONArray(filters.excludePatterns))
            }
            filters.minSize?.let { filterObj.put("MinSize", it) }
            filters.maxSize?.let { filterObj.put("MaxSize", it) }
            params.put("_filter", filterObj)
        }

        // Build config object (dry run, bandwidth limit)
        if (config.dryRun || filters.bandwidthLimit != null) {
            val configObj = JSONObject()
            if (config.dryRun) configObj.put("DryRun", true)
            filters.bandwidthLimit?.let { configObj.put("BwLimit", it) }
            params.put("_config", configObj)
        }

        val result = rpc(config.mode.rcMethod, params)
        val jobId = result.getLong("jobid")
        activeSyncJobId = jobId
        return jobId
    }

    /** Query the status of an async rclone job. */
    fun getJobStatus(jobId: Long): SyncJobStatus {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val result = rpc("job/status", JSONObject().put("jobid", jobId))
        return SyncJobStatus(
            jobId = jobId,
            finished = result.optBoolean("finished", false),
            success = result.optBoolean("success", false),
            error = result.optString("error", "").ifEmpty { null },
            duration = result.optDouble("duration", 0.0),
        )
    }

    /** Cancel a running async rclone job. */
    fun cancelJob(jobId: Long) {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        try {
            rpc("job/stop", JSONObject().put("jobid", jobId))
        } catch (_: Exception) {
            // Job may already be finished
        }
        if (activeSyncJobId == jobId) activeSyncJobId = null
    }

    /** Reset global transfer statistics (call before starting a new sync). */
    fun resetStats() {
        if (!initialized) return
        try {
            rpc("core/stats-reset", JSONObject())
        } catch (_: Exception) {
            // Not critical
        }
    }

    // ── Media server ──────────────────────────────────────────────────

    /**
     * Start a local HTTP server that streams files from the given remote
     * via rclone VFS. Returns the port number the server is listening on.
     * The server binds to 127.0.0.1 (loopback only).
     */
    fun startMediaServer(remote: String, preferredPort: Int = 0): Int {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val result = RcloneBridge.startMediaServer(remote, preferredPort.toLong())
        if (!result.isOk) {
            val error = try {
                JSONObject(result.output).optString("error", result.output)
            } catch (_: Exception) {
                result.output
            }
            throw RcloneException("startMediaServer", result.status, error)
        }
        return JSONObject(result.output).getInt("port")
    }

    /**
     * Get the current media server port for the given remote, or null if not running.
     * Does not start a new server.
     */
    fun mediaServerPort(remote: String): Int? {
        if (!initialized) return null
        val result = RcloneBridge.mediaServerStatus()
        if (!result.isOk) return null
        val json = JSONObject(result.output)
        val port = json.optInt("port", 0)
        if (port == 0) return null
        val running = json.optString("remote", "")
        return if (running == remote) port else null
    }

    /** Stop the local media streaming HTTP server if running. */
    fun stopMediaServer() {
        if (!initialized) return
        RcloneBridge.stopMediaServer()
    }

    // ── DLNA server ──────────────────────────────────────────────────

    @Volatile
    var activeDlnaServerId: String? = null

    /**
     * Start a DLNA media server for the given remote.
     * Requires the `cmd/serve/dlna` import in the Go bridge.
     * Returns the server ID for later stopping.
     */
    fun startDlnaServer(remote: String, friendlyName: String = "yySlide"): String {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val params = JSONObject()
        params.put("type", "dlna")
        params.put("fs", "$remote:")
        params.put("opt", JSONObject().put("name", friendlyName))
        val result = rpc("serve/start", params)
        val id = result.getString("id")
        activeDlnaServerId = id
        return id
    }

    /** Stop the DLNA media server if running. */
    fun stopDlnaServer() {
        val id = activeDlnaServerId ?: return
        try {
            rpc("serve/stop", JSONObject().put("id", id))
        } catch (_: Exception) {
            // Server may already be stopped
        }
        activeDlnaServerId = null
    }

    /** Check whether the DLNA server is still running. */
    fun dlnaServerStatus(): Boolean {
        val id = activeDlnaServerId ?: return false
        return try {
            val result = rpc("serve/list")
            val list = result.optJSONArray("list") ?: return false
            (0 until list.length()).any {
                list.getJSONObject(it).optString("id") == id
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        check(initialized) { "RcloneClient.initialize() must be called first" }
        val result = RcloneBridge.rpc(method, params.toString())
        if (!result.isOk) {
            val error = try {
                JSONObject(result.output).optString("error", result.output)
            } catch (_: Exception) {
                result.output
            }
            Log.w(TAG, "RPC $method failed (${result.status}): $error")
            throw RcloneException(method, result.status, error)
        }
        return try {
            JSONObject(result.output)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun parseOptions(arr: JSONArray?): List<ProviderOption> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ProviderOption(
                name = o.getString("Name"),
                help = o.optString("Help", ""),
                provider = o.optString("Provider", ""),
                default = o.optString("Default", ""),
                required = o.optBoolean("Required", false),
                isPassword = o.optBoolean("IsPassword", false),
                advanced = o.optBoolean("Advanced", false),
                type = o.optString("Type", "string"),
                examples = parseExamples(o.optJSONArray("Examples")),
            )
        }
    }

    private fun parseExamples(arr: JSONArray?): List<ProviderOptionExample> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            ProviderOptionExample(
                value = e.optString("Value", ""),
                help = e.optString("Help", ""),
            )
        }
    }

    private fun parseConfigState(json: JSONObject): ConfigState {
        return ConfigState(
            state = json.optString("State", ""),
            option = json.optJSONObject("Option")?.let { opt ->
                ConfigOption(
                    name = opt.optString("Name", ""),
                    help = opt.optString("Help", ""),
                    default = opt.optString("Default", ""),
                    required = opt.optBoolean("Required", false),
                    isPassword = opt.optBoolean("IsPassword", false),
                    type = opt.optString("Type", ""),
                )
            },
            error = json.optString("error", ""),
        )
    }
}

class RcloneException(
    val method: String,
    val statusCode: Int,
    val error: String,
) : Exception("rclone $method failed ($statusCode): $error")
