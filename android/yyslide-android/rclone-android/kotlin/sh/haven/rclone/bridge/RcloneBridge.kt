package sh.haven.rclone.bridge

import sh.haven.rclone.binding.rcbridge.Rcbridge

/**
 * Thin Kotlin wrapper around the gomobile-generated Java bindings for the
 * Go rcbridge module.  All rclone functionality is accessed via the [rpc]
 * method which calls rclone's RC (Remote Control) API.
 */
object RcloneBridge {

    data class RpcResult(
        val status: Int,
        val output: String,
    ) {
        val isOk: Boolean get() = status == 200
    }

    private var initialized = false

    /**
     * Initialise the rclone library.  Must be called once before any [rpc]
     * calls.  Safe to call multiple times (subsequent calls are no-ops).
     *
     * @param configPath absolute path to the rclone config file
     *                   (e.g. `/data/data/sh.haven.app/files/rclone/rclone.conf`).
     *                   Pass empty string to use rclone's default.
     */
    fun initialize(configPath: String) {
        if (initialized) return
        Rcbridge.rbInitialize(configPath)
        initialized = true
    }

    /** Shut down the rclone library. Call once at app shutdown. */
    fun shutdown() {
        if (!initialized) return
        Rcbridge.rbFinalize()
        initialized = false
    }

    /**
     * Set HTTPS_PROXY / HTTP_PROXY for outgoing rclone HTTP traffic.
     * Routes the embedded rclone process through the given proxy URL —
     * typically `socks5://127.0.0.1:<port>` for one of Haven's per-tunnel
     * SOCKS5 listeners (#149).
     *
     * Pass null or an empty string to clear both vars and route direct.
     *
     * Caveat: rclone caches HTTP clients per-fs, so changing the proxy
     * mid-session may not take effect until a fresh fs is created. For
     * the per-profile use case the caller sets this before the first
     * RPC for the profile, which is fresh.
     */
    fun setProxy(proxyUrl: String?) {
        Rcbridge.rbSetProxy(proxyUrl.orEmpty())
    }

    /**
     * Call an rclone RC method.
     *
     * @param method RC method name, e.g. "operations/list", "config/create"
     * @param input  JSON string of method parameters, e.g. `{"fs":"remote:","remote":"/"}`
     * @return [RpcResult] with HTTP-style status code and JSON output
     */
    fun rpc(method: String, input: String = "{}"): RpcResult {
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbRPC(method, input)
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }

    /**
     * Start a local HTTP server that streams files from the given rclone
     * remote via VFS.  Binds to 127.0.0.1 with an auto-assigned port.
     *
     * @param remoteName rclone remote name without trailing colon, e.g. "gdrive"
     * @return [RpcResult] with JSON `{"port": N}` on success
     */
    fun startMediaServer(remoteName: String, preferredPort: Long = 0): RpcResult {
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbStartMediaServer(remoteName, preferredPort)
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }

    /** Query the current media server state. Returns JSON with "port" and optional "remote". */
    fun mediaServerStatus(): RpcResult {
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbMediaServerStatus()
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }

    /** Stop the media streaming HTTP server if running. */
    fun stopMediaServer(): RpcResult {
        check(initialized) { "RcloneBridge.initialize() must be called first" }
        val result = Rcbridge.rbStopMediaServer()
        return RpcResult(
            status = result.status.toInt(),
            output = result.output,
        )
    }
}
