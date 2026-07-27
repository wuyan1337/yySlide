package sh.haven.core.ffmpeg

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "HlsStreamServer"
private const val HLS_JS_VERSION = "1.6.16"
private const val HLS_JS_ASSET = "hls-$HLS_JS_VERSION.min.js"
private const val HLS_JS_PATH = "/$HLS_JS_ASSET"
private const val HLS_JS_SHA256 = "442f599c34f103c3355b375a23bdff560592d7117d09a8c847242ea3de2d40e0"
private const val PLAYER_JS_ASSET = "hls-player.js"
private const val PLAYER_JS_PATH = "/$PLAYER_JS_ASSET"
private const val PLAYER_CSS_ASSET = "hls-player.css"
private const val PLAYER_CSS_PATH = "/$PLAYER_CSS_ASSET"
private const val STATIC_ASSET_CACHE = "private, max-age=31536000, immutable"
private const val HTTP_SOCKET_TIMEOUT_MS = 5_000
private const val MAX_REQUEST_LINE_BYTES = 4_096
private const val MAX_HEADER_LINE_BYTES = 8_192
private const val MAX_HEADER_BYTES = 32_768
private const val MAX_HEADER_COUNT = 64
private const val MAX_CONCURRENT_CLIENTS = 6
private const val PLAYER_CSP = "default-src 'self'; base-uri 'none'; connect-src 'self'; " +
    "frame-ancestors 'none'; form-action 'none'; img-src 'self'; media-src 'self' blob:; " +
    "object-src 'none'; script-src 'self'; style-src 'self'; worker-src 'none'"
private val ACCESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{32,}$")
private val HLS_MEDIA_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.(?:aac|bin|key|m4s|mp4|ts)$")
private val HTTP_URL_IN_LOG_PATTERN = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)

internal data class HttpRequest(
    val method: String,
    val target: String,
)

internal class HttpRequestException(
    val status: String,
) : Exception(status)

internal class ClientLimiter(maxClients: Int) {
    private val permits = Semaphore(maxClients, true)

    init {
        require(maxClients > 0) { "maxClients must be positive" }
    }

    fun tryAcquire(): Boolean = permits.tryAcquire()

    fun release() = permits.release()

    internal fun availablePermits(): Int = permits.availablePermits()
}

/**
 * Streams media via HLS (HTTP Live Streaming).
 *
 * Pipeline: input → ffmpeg (HLS muxer) → .m3u8 + .ts segments → HTTP server
 *
 * Call [playerUrl] or [playlistUrl] after startup to obtain the authenticated
 * per-session URL. Local playback can be restricted to IPv4 loopback; LAN
 * sharing binds the wildcard address and requires the same random URL token.
 */
@Singleton
class HlsStreamServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ffmpegExecutor: FfmpegExecutor,
) : Closeable {

    /** One entry in a folder-as-playlist stream. */
    data class PlaylistItem(val title: String, val input: String)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private var ffmpegJob: FfmpegJob? = null
    private var hlsDir: File? = null
    private val assetCache = ConcurrentHashMap<String, ByteArray>()
    private val clientLimiter = ClientLimiter(MAX_CONCURRENT_CLIENTS)
    private val activeClients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var accessToken: String = ""

    /** Non-empty when streaming a folder playlist. */
    @Volatile
    private var playlist: List<PlaylistItem> = emptyList()

    @Volatile
    private var currentIndex: Int = 0

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    private var localOnly: Boolean = false

    /**
     * Optional observer for ffmpeg stderr lines. Lets callers capture the
     * transcoder output into their own verbose-log buffer (Haven's audit
     * log) alongside the normal Log.w broadcast to logcat.
     */
    @Volatile
    private var onStderr: ((String) -> Unit)? = null

    /** Authenticated browser player URL for the current stream session. */
    fun playerUrl(host: String): String = authenticatedUrl(host, "/")

    /** Authenticated HLS manifest URL for the current stream session. */
    fun playlistUrl(host: String): String = authenticatedUrl(host, "/stream.m3u8")

    private fun authenticatedUrl(host: String, path: String): String {
        check(isRunning && port > 0 && accessToken.isNotEmpty()) { "HLS stream is not running" }
        val urlHost = when {
            host.startsWith("[") && host.endsWith("]") -> host
            ':' in host -> "[$host]"
            else -> host
        }
        return "http://$urlHost:$port$path?token=$accessToken"
    }

    /**
     * Start streaming a media file.
     *
     * @param inputPath Absolute path to the input file
     * @param preferredPort Port to listen on (0 = auto)
     * @return The port the server is listening on
     */
    /**
     * @param localOnly when true, bind to 127.0.0.1 (loopback only — not
     *                  reachable from other devices on the network). Use
     *                  for on-device playback in Chrome.
     */
    @Synchronized
    fun startFile(
        inputPath: String,
        preferredPort: Int = 8080,
        localOnly: Boolean = false,
        stderrObserver: ((String) -> Unit)? = null,
    ): Int {
        stop()
        onStderr = stderrObserver
        return try {
            this.localOnly = localOnly
            accessToken = newAccessToken()
            val dir = prepareHlsDir()
            playlist = emptyList()
            currentIndex = 0
            startFfmpegForInput(inputPath, dir)
            startHttpServer(preferredPort, dir, localOnly)
            port
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /**
     * Start streaming a folder playlist. The first item begins playing
     * immediately; the browser UI lets the viewer skip to any other item.
     * Each switch restarts ffmpeg with fresh segments.
     *
     * @param items list of media files to stream, already resolved to local
     *              paths or HTTP loopback URLs
     */
    @Synchronized
    fun startPlaylist(
        items: List<PlaylistItem>,
        preferredPort: Int = 8080,
        localOnly: Boolean = false,
        stderrObserver: ((String) -> Unit)? = null,
    ): Int {
        require(items.isNotEmpty()) { "playlist must not be empty" }
        stop()
        onStderr = stderrObserver
        return try {
            this.localOnly = localOnly
            accessToken = newAccessToken()
            val dir = prepareHlsDir()
            playlist = items
            currentIndex = 0
            startFfmpegForInput(items[0].input, dir)
            startHttpServer(preferredPort, dir, localOnly)
            port
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** Switch a running playlist stream to a different item. */
    @Synchronized
    fun switchTo(index: Int) {
        val items = playlist
        if (items.isEmpty() || index !in items.indices) return
        val dir = hlsDir ?: return
        ffmpegJob?.cancel()
        ffmpegJob = null
        // Wipe old segments so the player doesn't splice frames together.
        dir.listFiles()?.forEach { if (it.name.startsWith("seg") || it.name.endsWith(".m3u8")) it.delete() }
        currentIndex = index
        startFfmpegForInput(items[index].input, dir)
    }

    private fun prepareHlsDir(): File {
        val dir = File(context.cacheDir, "hls_stream").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        hlsDir = dir
        return dir
    }

    private fun startFfmpegForInput(inputPath: String, dir: File) {
        val playlistPath = File(dir, "stream.m3u8").absolutePath

        // Probe input for video + audio streams (excluding attached pictures like album art)
        val probeResult = ffmpegExecutor.probe(listOf(
            "-v", "error",
            "-show_entries", "stream=codec_type:stream_disposition=attached_pic",
            "-of", "flat", inputPath,
        ))
        val probeOut = probeResult.stdout
        val hasVideoStream = probeOut.contains("codec_type=\"video\"")
        val isAttachedPic = probeOut.contains("attached_pic=1")
        val hasRealVideo = hasVideoStream && !isAttachedPic
        val hasAudio = probeOut.contains("codec_type=\"audio\"")
        Log.w(TAG, "Probe: hasRealVideo=$hasRealVideo hasAudio=$hasAudio stdout=${probeOut.take(300)}")

        // Start ffmpeg: transcode to HLS segments.
        // For video inputs, force H.264 Main profile, level 4.0, max 30fps
        // and max 1920x1080 dimensions so the output works with Android
        // Chrome's MediaCodec (it rejects non-standard resolutions and
        // framerates — e.g. portrait 1344x2992 at 55fps from screen
        // recordings would fail with MEDIA_ERR_SRC_NOT_SUPPORTED).
        val args = buildList {
            add("-y")
            add("-i"); add(inputPath)
            if (hasRealVideo) {
                add("-map"); add("0:v:0")
                if (hasAudio) {
                    add("-map"); add("0:a:0?")
                    add("-c:a"); add("aac"); add("-b:a"); add("128k")
                } else {
                    add("-an")
                }
                add("-c:v"); add("libx264")
                // `ultrafast` disables CABAC + 8x8dct + weight_p and makes
                // libx264 write "Constrained Baseline" into the SPS regardless
                // of `-profile:v main`. Chrome's MSE rejects Constrained
                // Baseline > 720p, so 1080p inputs silently fail with
                // MEDIA_ERR_SRC_NOT_SUPPORTED. `veryfast` is the cheapest
                // preset that actually honours Main profile. The `zerolatency`
                // tune is dropped because it's a live-streaming knob with no
                // benefit in a VOD pipeline and it bloats output ~2.7x.
                add("-preset"); add("veryfast")
                add("-profile:v"); add("main")
                add("-level"); add("4.0")
                add("-pix_fmt"); add("yuv420p")
                // Scale to fit within 1920x1080 (or the other way for portrait),
                // preserving aspect ratio. Even dimensions required by libx264.
                add("-vf"); add("scale='min(1920,iw)':'min(1080,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2")
                // Cap framerate at 30fps — screen recordings at 54.80fps
                // trip MediaCodec on some Android devices
                add("-r"); add("30")
            } else {
                // Audio-only: map only audio stream to avoid decoding
                // attached pictures (album art) that lack a decoder
                add("-map"); add("0:a:0?")
                add("-c:a"); add("aac"); add("-b:a"); add("128k")
            }
            add("-f"); add("hls")
            add("-hls_time"); add("2")
            add("-hls_list_size"); add("0")              // 0 = keep all segments
            add("-hls_playlist_type"); add("event")      // growing playlist — hls.js plays segments as they appear
            add("-hls_flags"); add("independent_segments")
            add("-hls_segment_filename"); add(File(dir, "seg%03d.ts").absolutePath)
            add(playlistPath)
        }

        val loggedArgs = args.joinToString(" ") { redactUrlsForLog(it) }
        Log.w(TAG, "Starting ffmpeg HLS: $loggedArgs")
        val observer = onStderr
        observer?.invoke("cmd=ffmpeg $loggedArgs")
        val job = ffmpegExecutor.startJob(args) { line ->
            val safeLine = redactUrlsForLog(line)
            Log.w(TAG, "ffmpeg: $safeLine")
            observer?.invoke(safeLine)
        }
        ffmpegJob = job

        // Monitor ffmpeg in a background thread — log if it exits early
        Thread({
            val result = job.await()
            Log.w(TAG, "ffmpeg exited: code=${result.exitCode} stderr=${redactUrlsForLog(result.stderr.take(500))}")
        }, "hls-ffmpeg-monitor").apply { isDaemon = true }.start()
    }

    private fun startHttpServer(preferredPort: Int, dir: File, localOnly: Boolean = false) {
        // Local playback is intentionally IPv4-loopback-only because callers
        // advertise 127.0.0.1. LAN sharing binds the IPv4 wildcard and relies
        // on the per-session URL token for authorization.
        val bindAddress = InetAddress.getByName(bindAddressFor(localOnly))
        val ss = ServerSocket(preferredPort, 10, bindAddress)
        serverSocket = ss
        port = ss.localPort
        isRunning = true

        serverThread = thread(name = "hls-http", isDaemon = true) {
            Log.i(TAG, "HLS server listening on port $port")
            while (isRunning) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                if (!isRunning || serverSocket !== ss) {
                    closeQuietly(client)
                    break
                }
                activeClients.add(client)
                if (!isRunning || serverSocket !== ss) {
                    activeClients.remove(client)
                    closeQuietly(client)
                    break
                }
                try {
                    client.soTimeout = HTTP_SOCKET_TIMEOUT_MS
                    if (!clientLimiter.tryAcquire()) {
                        activeClients.remove(client)
                        try {
                            client.use { writeEmptyResponse(it.getOutputStream(), "503 Service Unavailable") }
                        } catch (_: Exception) {
                            closeQuietly(client)
                        }
                        continue
                    }
                    try {
                        thread(name = "hls-client", isDaemon = true) {
                            try {
                                handleClient(client, dir)
                            } finally {
                                activeClients.remove(client)
                                closeQuietly(client)
                                clientLimiter.release()
                            }
                        }
                    } catch (_: Exception) {
                        activeClients.remove(client)
                        closeQuietly(client)
                        clientLimiter.release()
                    }
                } catch (_: Exception) {
                    activeClients.remove(client)
                    closeQuietly(client)
                }
            }
        }
    }

    /**
     * Start streaming from a camera or microphone pipe.
     *
     * @param ffmpegArgs Full ffmpeg args (caller provides -i pipe: or device input)
     * @param preferredPort Port to listen on
     * @return The port the server is listening on
     */
    @Synchronized
    fun startCustom(
        ffmpegArgs: List<String>,
        preferredPort: Int = 8080,
        stderrObserver: ((String) -> Unit)? = null,
    ): Int {
        stop()
        onStderr = stderrObserver
        return try {
            localOnly = false
            accessToken = newAccessToken()
            val dir = prepareHlsDir()
            playlist = emptyList()
            currentIndex = 0

            val observer = onStderr
            ffmpegJob = ffmpegExecutor.startJob(ffmpegArgs) { line ->
                val safeLine = redactUrlsForLog(line)
                Log.d(TAG, "ffmpeg: $safeLine")
                observer?.invoke(safeLine)
            }

            startHttpServer(preferredPort, dir)
            port
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    override fun close() = stop()

    @Synchronized
    fun stop() {
        isRunning = false
        ffmpegJob?.cancel()
        ffmpegJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        activeClients.toList().forEach(::closeQuietly)
        serverThread = null
        port = 0
        hlsDir?.listFiles()?.forEach { it.delete() }
        hlsDir = null
        playlist = emptyList()
        currentIndex = 0
        localOnly = false
        accessToken = ""
        onStderr = null
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
            // Already closed or disconnected.
        }
    }

    private fun handleClient(socket: Socket, hlsDir: File) {
        try {
            socket.use { s ->
                // Defense in depth: local-only sockets are already bound to
                // 127.0.0.1, but reject a non-loopback peer if a platform ever
                // reports or remaps the accepted address unexpectedly.
                if (localOnly && !s.inetAddress.isLoopbackAddress) {
                    Log.w(TAG, "Rejected non-local connection from ${s.inetAddress}")
                    writeEmptyResponse(s.getOutputStream(), "403 Forbidden")
                    return
                }

                val out = s.getOutputStream()
                val request = try {
                    readHttpRequest(s.getInputStream())
                } catch (e: HttpRequestException) {
                    writeEmptyResponse(out, e.status)
                    return
                }
                val requestPath = request.target.substringBefore('?')
                Log.d(TAG, "HTTP ${s.inetAddress}: ${request.method} $requestPath")
                if (!isSupportedHttpMethod(request.method)) {
                    writeEmptyResponse(out, "405 Method Not Allowed", listOf("Allow" to "GET, HEAD"))
                    return
                }

                val requestTarget = request.target
                val path = requestPath
                val includeBody = request.method == "GET"
                val token = accessToken
                if (!hasValidAccessToken(requestTarget, token)) {
                    writeEmptyResponse(out, "403 Forbidden")
                    out.flush()
                    return
                }

                when {
                    path == "/" || path == "/index.html" -> {
                        serveSecurePlayerPage(out, token, includeBody)
                    }
                    staticAssetForPath(path) != null -> {
                        serveStaticAsset(out, staticAssetForPath(path)!!, includeBody)
                    }
                    path == "/playlist.json" -> {
                        servePlaylistJson(out, includeBody)
                    }
                    path == "/switch" -> {
                        if (!includeBody) {
                            writeEmptyResponse(out, "405 Method Not Allowed", listOf("Allow" to "GET"))
                            return
                        }
                        val idx = parseSwitchIndex(requestTarget)
                        if (idx != null && idx in playlist.indices) {
                            switchTo(idx)
                            val body = "{\"ok\":true,\"index\":$idx}"
                            val bytes = body.toByteArray()
                            writeBytesResponse(out, bytes, "application/json", "no-store", includeBody = true)
                        } else {
                            writeEmptyResponse(out, "400 Bad Request")
                        }
                    }
                    path == "/stream.m3u8" -> {
                        servePlaylist(out, File(hlsDir, "stream.m3u8"), token, includeBody)
                    }
                    resolveHlsMediaName(path) != null -> {
                        val name = resolveHlsMediaName(path)!!
                        serveFile(out, File(hlsDir, name), contentTypeForHlsMedia(name), includeBody)
                    }
                    else -> {
                        writeEmptyResponse(out, "404 Not Found")
                    }
                }
                out.flush()
            }
        } catch (_: Exception) {
            // Client disconnected
        }
    }

    private fun serveFile(
        out: java.io.OutputStream,
        file: File,
        contentType: String,
        includeBody: Boolean,
    ) {
        if (!file.exists()) {
            writeEmptyResponse(out, "404 Not Found")
            return
        }
        writeBytesResponse(out, file.readBytes(), contentType, "no-cache", includeBody = includeBody)
    }

    private fun servePlaylist(
        out: java.io.OutputStream,
        file: File,
        token: String,
        includeBody: Boolean,
    ) {
        if (!file.exists()) {
            writeEmptyResponse(out, "404 Not Found")
            return
        }
        val secured = try {
            securePlaylist(file.readText(), token)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Refusing unsafe HLS playlist", e)
            writeEmptyResponse(out, "500 Internal Server Error")
            return
        }
        writeBytesResponse(
            out,
            secured.toByteArray(StandardCharsets.UTF_8),
            "application/vnd.apple.mpegurl",
            "no-cache",
            includeBody = includeBody,
        )
    }

    private fun serveStaticAsset(
        out: java.io.OutputStream,
        spec: StaticAssetSpec,
        includeBody: Boolean,
    ) {
        val bytes = try {
            assetCache.computeIfAbsent(spec.assetName) { assetName ->
                context.assets.open(assetName).use { it.readBytes() }.also { loaded ->
                    if (assetName == HLS_JS_ASSET) {
                        val actual = sha256Hex(loaded)
                        check(actual == HLS_JS_SHA256) {
                            "Vendored hls.js digest mismatch: expected $HLS_JS_SHA256, got $actual"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to serve static HLS asset ${spec.assetName}", e)
            writeEmptyResponse(out, "500 Internal Server Error")
            return
        }
        writeBytesResponse(out, bytes, spec.contentType, spec.cacheControl, includeBody = includeBody)
    }

    private fun writeBytesResponse(
        out: java.io.OutputStream,
        bytes: ByteArray,
        contentType: String,
        cacheControl: String,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        includeBody: Boolean = true,
    ) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: $cacheControl\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (includeBody) out.write(bytes)
    }

    private fun writeEmptyResponse(
        out: java.io.OutputStream,
        status: String,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ) {
        val response = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Length: 0\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        out.write(response.toByteArray(StandardCharsets.ISO_8859_1))
    }

    internal companion object {
        data class StaticAssetSpec(
            val assetName: String,
            val contentType: String,
            val cacheControl: String = STATIC_ASSET_CACHE,
        )

        fun bindAddressFor(localOnly: Boolean): String = if (localOnly) "127.0.0.1" else "0.0.0.0"

        fun staticAssetForPath(path: String): StaticAssetSpec? = when (path) {
            HLS_JS_PATH -> StaticAssetSpec(HLS_JS_ASSET, "application/javascript; charset=utf-8")
            PLAYER_JS_PATH -> StaticAssetSpec(PLAYER_JS_ASSET, "application/javascript; charset=utf-8")
            PLAYER_CSS_PATH -> StaticAssetSpec(PLAYER_CSS_ASSET, "text/css; charset=utf-8")
            else -> null
        }

        fun resolveHlsMediaName(path: String): String? {
            if (!path.startsWith('/') || path.indexOf('/', startIndex = 1) >= 0) return null
            val name = path.drop(1)
            if (".." in name || !HLS_MEDIA_NAME_PATTERN.matches(name)) return null
            return name
        }

        fun contentTypeForHlsMedia(name: String): String = when (name.substringAfterLast('.')) {
            "aac" -> "audio/aac"
            "key", "bin" -> "application/octet-stream"
            "m4s", "mp4" -> "video/mp4"
            else -> "video/MP2T"
        }

        fun isSupportedHttpMethod(method: String): Boolean = method == "GET" || method == "HEAD"

        /**
         * Read one HTTP/1.x request without allowing an unauthenticated peer
         * to grow memory indefinitely. The server handles exactly one request
         * per connection, so only the request line and headers are accepted.
         */
        fun readHttpRequest(input: InputStream): HttpRequest = try {
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES, "414 URI Too Long")
            if (requestLine.isEmpty()) throw HttpRequestException("400 Bad Request")

            val firstSpace = requestLine.indexOf(' ')
            val secondSpace = requestLine.indexOf(' ', firstSpace + 1)
            if (
                firstSpace <= 0 ||
                secondSpace <= firstSpace + 1 ||
                requestLine.indexOf(' ', secondSpace + 1) >= 0
            ) {
                throw HttpRequestException("400 Bad Request")
            }

            val method = requestLine.substring(0, firstSpace)
            val target = requestLine.substring(firstSpace + 1, secondSpace)
            val version = requestLine.substring(secondSpace + 1)
            if (
                method.any { it !in 'A'..'Z' } ||
                !target.startsWith('/') ||
                target.any { it <= ' ' || it.code >= 0x7f } ||
                version !in setOf("HTTP/1.0", "HTTP/1.1")
            ) {
                throw HttpRequestException("400 Bad Request")
            }

            var headerBytes = 0
            var headerCount = 0
            while (true) {
                val line = readAsciiLine(input, MAX_HEADER_LINE_BYTES, "431 Request Header Fields Too Large")
                headerBytes += line.length + 2
                if (headerBytes > MAX_HEADER_BYTES) {
                    throw HttpRequestException("431 Request Header Fields Too Large")
                }
                if (line.isEmpty()) break

                headerCount++
                if (headerCount > MAX_HEADER_COUNT) {
                    throw HttpRequestException("431 Request Header Fields Too Large")
                }
                val separator = line.indexOf(':')
                if (
                    separator <= 0 ||
                    line[0] == ' ' ||
                    line[0] == '\t' ||
                    line.substring(0, separator).any { !isHeaderNameCharacter(it) }
                ) {
                    throw HttpRequestException("400 Bad Request")
                }
            }
            HttpRequest(method = method, target = target)
        } catch (_: SocketTimeoutException) {
            throw HttpRequestException("408 Request Timeout")
        }

        private fun readAsciiLine(input: InputStream, maxBytes: Int, tooLongStatus: String): String {
            val bytes = ByteArrayOutputStream(minOf(maxBytes, 256))
            while (true) {
                val value = input.read()
                if (value < 0) throw HttpRequestException("400 Bad Request")
                when (value) {
                    '\r'.code -> {
                        val following = input.read()
                        if (following != '\n'.code) throw HttpRequestException("400 Bad Request")
                        return bytes.toString(StandardCharsets.US_ASCII.name())
                    }
                    '\n'.code -> throw HttpRequestException("400 Bad Request")
                    '\t'.code -> {
                        if (bytes.size() >= maxBytes) throw HttpRequestException(tooLongStatus)
                        bytes.write(value)
                    }
                    in 0x20..0x7e -> {
                        if (bytes.size() >= maxBytes) throw HttpRequestException(tooLongStatus)
                        bytes.write(value)
                    }
                    else -> throw HttpRequestException("400 Bad Request")
                }
            }
        }

        private fun isHeaderNameCharacter(value: Char): Boolean =
            value.isLetterOrDigit() || value in "!#$%&'*+-.^_`|~"

        fun hasValidAccessToken(requestTarget: String, expectedToken: String): Boolean {
            if (!ACCESS_TOKEN_PATTERN.matches(expectedToken)) return false
            val supplied = requestTarget.substringAfter('?', "")
                .split('&')
                .filter { it.substringBefore('=', "") == "token" }
                .map { it.substringAfter('=', "") }
            if (supplied.size != 1) return false
            return MessageDigest.isEqual(
                supplied.single().toByteArray(StandardCharsets.UTF_8),
                expectedToken.toByteArray(StandardCharsets.UTF_8),
            )
        }

        fun securePlaylist(source: String, token: String): String {
            require(ACCESS_TOKEN_PATTERN.matches(token)) { "invalid HLS access token" }
            val secured = source.lineSequence().joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith('#') -> {
                        val uriAttribute = Regex("""URI="([^"]+)"""")
                        val matches = uriAttribute.findAll(line).toList()
                        require("URI=" !in line || matches.isNotEmpty()) {
                            "Malformed HLS URI attribute"
                        }
                        uriAttribute.replace(line) { match ->
                            "URI=\"${tokenizePlaylistUri(match.groupValues[1], token)}\""
                        }
                    }
                    else -> tokenizePlaylistUri(line, token)
                }
            }
            return if (source.endsWith('\n')) "$secured\n" else secured
        }

        private fun tokenizePlaylistUri(uri: String, token: String): String {
            require('#' !in uri) { "HLS URI fragments are not allowed" }
            val rawPath = uri.substringBefore('?')
            val normalizedPath = if (rawPath.startsWith('/')) rawPath else "/$rawPath"
            require(resolveHlsMediaName(normalizedPath) != null) { "Unsafe HLS URI: $uri" }
            val query = uri.substringAfter('?', "")
            require(query.split('&').none { it.substringBefore('=', "") == "token" }) {
                "HLS URI already contains a token"
            }
            return uri + if ('?' in uri) "&token=$token" else "?token=$token"
        }

        fun buildPlayerHtml(token: String): String {
            require(ACCESS_TOKEN_PATTERN.matches(token)) { "invalid HLS access token" }
            return """
<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>yySlide Stream</title>
<link rel="stylesheet" href="$PLAYER_CSS_PATH?token=$token">
</head><body>
<div class="info">yySlide Stream</div>
<video id="v" controls autoplay muted playsinline></video>
<div id="unmute">🔇 Tap to unmute</div>
<div id="playlist-toggle">☰ Playlist</div>
<div id="playlist"><div class="head">Playlist</div><div id="playlist-items"></div></div>
<div id="status"></div>
<script src="$HLS_JS_PATH?token=$token"></script>
<script src="$PLAYER_JS_PATH?token=$token"></script>
</body></html>
            """.trimIndent()
        }

        fun playerContentSecurityPolicy(): String = PLAYER_CSP

        fun redactUrlsForLog(value: String): String =
            HTTP_URL_IN_LOG_PATTERN.replace(value, "<redacted-url>")

        private fun newAccessToken(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        /** Parse the selected playlist index without discarding the query string. */
        fun parseSwitchIndex(requestTarget: String): Int? {
            if (requestTarget.substringBefore('?') != "/switch") return null
            return requestTarget.substringAfter('?', "")
                .split('&')
                .firstOrNull { it.substringBefore('=') == "i" }
                ?.substringAfter('=', "")
                ?.toIntOrNull()
        }
    }

    private fun servePlaylistJson(out: java.io.OutputStream, includeBody: Boolean) {
        val items = playlist
        val json = buildString {
            append("{\"current\":").append(currentIndex).append(",\"items\":[")
            items.forEachIndexed { i, it ->
                if (i > 0) append(',')
                append('"').append(jsonEscape(it.title)).append('"')
            }
            append("]}")
        }
        val bytes = json.toByteArray()
        writeBytesResponse(out, bytes, "application/json", "no-store", includeBody = includeBody)
    }

    private fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }

    private fun serveSecurePlayerPage(
        out: java.io.OutputStream,
        token: String,
        includeBody: Boolean,
    ) {
        val bytes = buildPlayerHtml(token).toByteArray(StandardCharsets.UTF_8)
        writeBytesResponse(
            out = out,
            bytes = bytes,
            contentType = "text/html; charset=utf-8",
            cacheControl = "no-store",
            extraHeaders = listOf(
                "Content-Security-Policy" to PLAYER_CSP,
                "Referrer-Policy" to "no-referrer",
                "X-Frame-Options" to "DENY",
            ),
            includeBody = includeBody,
        )
    }
}
