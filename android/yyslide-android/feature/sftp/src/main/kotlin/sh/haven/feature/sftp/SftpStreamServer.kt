package sh.haven.feature.sftp

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "SftpStreamServer"
private const val DEFAULT_CLIENT_READ_TIMEOUT_MILLIS = 20_000
private const val MAX_CONCURRENT_CLIENTS = 8
private const val ACCEPT_BACKLOG = 16
private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_LINE_BYTES = 8 * 1024
private const val MAX_HEADER_BYTES = 32 * 1024
private const val MAX_HEADER_COUNT = 64
private const val MAX_CONTENT_TYPE_LENGTH = 256

private val LOOPBACK_V4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

private data class ServerConfig(
    val clientReadTimeoutMillis: Int,
    val maxConcurrentClients: Int,
)

private class HttpRequestException(
    val statusCode: Int,
    val reason: String,
) : Exception()

/**
 * Loopback HTTP server that exposes SFTP files to ffmpeg so it can read
 * them with Range requests rather than having the caller download the
 * whole file to cache first.
 *
 * Callers register one file at a time via [publish], passing a per-file
 * [Opener] that knows how to re-open an InputStream at a given byte
 * offset on whatever `ChannelSftp` they control. This keeps the server
 * independent of any specific session manager or profile lookup and lets
 * both the SFTP browser ViewModel and the MCP agent use the same server.
 *
 * SFTP channels are not safe to read from concurrently, so all body
 * writes are serialized on an internal lock. That's acceptable for
 * single-stream playback: ffmpeg issues one Range request at a time per
 * input.
 */
@Singleton
class SftpStreamServer private constructor(
    private val config: ServerConfig,
) : Closeable {

    @Inject
    constructor() : this(
        ServerConfig(
            clientReadTimeoutMillis = DEFAULT_CLIENT_READ_TIMEOUT_MILLIS,
            maxConcurrentClients = MAX_CONCURRENT_CLIENTS,
        ),
    )

    /** Allows socket timeout/concurrency behavior to be exercised without a 20-second unit test. */
    internal constructor(
        clientReadTimeoutMillis: Int,
        maxConcurrentClients: Int,
    ) : this(
        ServerConfig(
            clientReadTimeoutMillis = clientReadTimeoutMillis,
            maxConcurrentClients = maxConcurrentClients,
        ),
    ) {
        require(clientReadTimeoutMillis > 0)
        require(maxConcurrentClients in 1..MAX_CONCURRENT_CLIENTS)
    }

    fun interface Opener {
        @Throws(Exception::class)
        fun open(offset: Long): InputStream
    }

    private data class Entry(
        val size: Long,
        val contentType: String,
        val opener: Opener,
    )

    private data class Request(
        val method: String,
        val target: String,
        val rangeHeader: String?,
    )

    private data class ResolvedRange(
        val start: Long,
        val end: Long,
        val length: Long,
        val isPartial: Boolean,
    )

    private val lifecycleLock = Any()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val activeSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val channelLock = Any()
    private val tokenBytes = ByteArray(32)
    private val secureRandom = SecureRandom()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile
    private var clientPermits: Semaphore? = null

    @Volatile
    var port: Int = 0
        private set

    internal val activeClientCount: Int
        get() = clientPermits?.let { config.maxConcurrentClients - it.availablePermits() } ?: 0

    internal val boundAddress: InetAddress?
        get() = synchronized(lifecycleLock) { serverSocket?.inetAddress }

    /**
     * Per-server-start random token. Required as the first URL path
     * segment on every request — without it, requests get 404. Prevents
     * other apps on the device from reading SFTP files via the loopback
     * port (the port itself is enumerable, the token is not).
     */
    @Volatile
    private var token: String = ""

    @Volatile
    private var running: Boolean = false

    /** Start the IPv4 loopback-only server. Returns the bound port. Idempotent. */
    fun start(preferredPort: Int = 0): Int = synchronized(lifecycleLock) {
        if (running) return@synchronized port

        val ss = ServerSocket(preferredPort, ACCEPT_BACKLOG, LOOPBACK_V4)
        val permits = Semaphore(config.maxConcurrentClients, true)
        secureRandom.nextBytes(tokenBytes)
        token = encodeTokenUrlSafe(tokenBytes)
        serverSocket = ss
        clientPermits = permits
        port = ss.localPort
        running = true

        try {
            serverThread = thread(name = "sftp-stream-http", isDaemon = true) {
                acceptClients(ss, permits)
            }
        } catch (e: Exception) {
            running = false
            serverSocket = null
            clientPermits = null
            port = 0
            token = ""
            tokenBytes.fill(0)
            try {
                ss.close()
            } catch (_: Exception) {
                // Nothing else can use a server socket whose thread failed to start.
            }
            throw e
        }

        port
    }

    private fun acceptClients(ss: ServerSocket, permits: Semaphore) {
        Log.i(TAG, "listening on IPv4 loopback")
        while (running) {
            val client = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }

            val registered = synchronized(lifecycleLock) {
                if (running && serverSocket === ss) {
                    activeSockets.add(client)
                    true
                } else {
                    false
                }
            }
            if (!registered) {
                closeQuietly(client)
                break
            }

            if (!permits.tryAcquire()) {
                activeSockets.remove(client)
                rejectOverloaded(client)
                continue
            }

            try {
                thread(name = "sftp-stream-client", isDaemon = true) {
                    try {
                        handle(client)
                    } finally {
                        activeSockets.remove(client)
                        closeQuietly(client)
                        permits.release()
                    }
                }
            } catch (_: Exception) {
                activeSockets.remove(client)
                closeQuietly(client)
                permits.release()
            }
        }
    }

    private fun rejectOverloaded(socket: Socket) {
        socket.use { client ->
            try {
                writeStatus(client.getOutputStream(), 503, "Service Unavailable")
            } catch (_: Exception) {
                // A peer may disconnect before reading the fixed overload response.
            }
        }
    }

    /**
     * Register a file and return the absolute URL path (beginning with
     * `/`) to use. The path includes the per-server-start auth token —
     * callers concatenate it onto `http://127.0.0.1:$port` to form the
     * full URL. Re-publishing the same file path replaces the previous
     * opener so a caller can point ffmpeg at the same URL with a fresh
     * channel after reconnecting.
     */
    fun publish(
        path: String,
        size: Long,
        contentType: String = "application/octet-stream",
        opener: Opener,
    ): String {
        require(size >= 0) { "size must not be negative" }
        require(isSafeContentType(contentType)) { "invalid content type" }
        val key = path.trimStart('/').split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        entries[key] = Entry(size = size, contentType = contentType, opener = opener)
        return "/$token/$key"
    }

    override fun close() = stop()

    fun stop() {
        val socketsToClose: List<Socket>
        synchronized(lifecycleLock) {
            running = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {
                // Closing is best-effort; active client sockets are closed below.
            }
            serverSocket = null
            clientPermits = null
            serverThread = null
            socketsToClose = activeSockets.toList()
            entries.clear()
            token = ""
            tokenBytes.fill(0)
            port = 0
        }
        socketsToClose.forEach(::closeQuietly)
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = config.clientReadTimeoutMillis
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val out = client.getOutputStream()

                val request = try {
                    parseRequest(input)
                } catch (_: SocketTimeoutException) {
                    writeStatusQuietly(out, 408, "Request Timeout")
                    return
                } catch (e: HttpRequestException) {
                    writeStatusQuietly(out, e.statusCode, e.reason)
                    return
                } catch (_: Exception) {
                    writeStatusQuietly(out, 400, "Bad Request")
                    return
                }

                serve(request, out)
            }
        } catch (e: Exception) {
            // Do not include exception messages: network/provider errors can contain a secret URL or path.
            Log.d(TAG, "client connection ended (${e.javaClass.simpleName})")
        }
    }

    private fun parseRequest(input: InputStream): Request {
        val reader = BoundedLineReader(input)
        val requestLine = reader.readLine(MAX_REQUEST_LINE_BYTES, 414, "URI Too Long")
            ?: throw HttpRequestException(400, "Bad Request")
        val firstSpace = requestLine.indexOf(' ')
        val secondSpace = if (firstSpace > 0) requestLine.indexOf(' ', firstSpace + 1) else -1
        if (
            firstSpace <= 0 ||
            secondSpace <= firstSpace + 1 ||
            secondSpace == requestLine.lastIndex ||
            requestLine.indexOf(' ', secondSpace + 1) >= 0
        ) {
            throw HttpRequestException(400, "Bad Request")
        }

        val method = requestLine.substring(0, firstSpace)
        val target = requestLine.substring(firstSpace + 1, secondSpace)
        val version = requestLine.substring(secondSpace + 1)
        if (!isHttpToken(method) || target.firstOrNull() != '/' || containsControlCharacter(target)) {
            throw HttpRequestException(400, "Bad Request")
        }
        if (version != "HTTP/1.1" && version != "HTTP/1.0") {
            throw HttpRequestException(400, "Bad Request")
        }
        if (method != "GET" && method != "HEAD") {
            throw HttpRequestException(405, "Method Not Allowed")
        }

        var headerCount = 0
        var rangeHeader: String? = null
        val contentLengths = mutableListOf<String>()
        var hasTransferEncoding = false
        var hasExpect = false
        while (true) {
            val line = reader.readLine(MAX_HEADER_LINE_BYTES, 431, "Request Header Fields Too Large")
                ?: throw HttpRequestException(400, "Bad Request")
            if (line.isEmpty()) break

            headerCount += 1
            if (headerCount > MAX_HEADER_COUNT || line.startsWith(' ') || line.startsWith('\t')) {
                throw HttpRequestException(431, "Request Header Fields Too Large")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpRequestException(400, "Bad Request")
            val name = line.substring(0, colon)
            if (!isHttpToken(name)) throw HttpRequestException(400, "Bad Request")
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (containsInvalidHeaderValueCharacter(value)) {
                throw HttpRequestException(400, "Bad Request")
            }

            when {
                name.equals("Range", ignoreCase = true) -> {
                    if (rangeHeader != null) throw HttpRequestException(400, "Bad Request")
                    rangeHeader = value
                }
                name.equals("Content-Length", ignoreCase = true) -> contentLengths += value
                name.equals("Transfer-Encoding", ignoreCase = true) -> hasTransferEncoding = true
                name.equals("Expect", ignoreCase = true) -> hasExpect = true
            }
        }

        if (hasTransferEncoding || hasExpect) {
            throw HttpRequestException(400, "Bad Request")
        }
        validateNoRequestBody(contentLengths)
        return Request(method = method, target = target, rangeHeader = rangeHeader)
    }

    private fun validateNoRequestBody(contentLengthHeaders: List<String>) {
        if (contentLengthHeaders.isEmpty()) return
        val parsed = mutableListOf<Long>()
        for (header in contentLengthHeaders) {
            for (rawValue in header.split(',')) {
                val value = rawValue.trim()
                if (value.isEmpty() || value.any { it !in '0'..'9' }) {
                    throw HttpRequestException(400, "Bad Request")
                }
                parsed += value.toLongOrNull() ?: throw HttpRequestException(400, "Bad Request")
            }
        }
        if (parsed.isEmpty() || parsed.any { it != parsed.first() } || parsed.first() != 0L) {
            throw HttpRequestException(400, "Bad Request")
        }
    }

    private fun serve(request: Request, out: OutputStream) {
        val pathOnly = request.target.substringBefore('?').removePrefix("/")
        val slash = pathOnly.indexOf('/')
        if (slash <= 0) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val receivedToken = pathOnly.substring(0, slash)
        val key = pathOnly.substring(slash + 1)
        val expected = token
        if (expected.isEmpty() || !constantTimeEquals(receivedToken, expected)) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val entry = entries[key]
        if (entry == null) {
            writeStatus(out, 404, "Not Found")
            return
        }

        val range = resolveRange(request.rangeHeader, entry.size)
        if (range == null) {
            writeRangeNotSatisfiable(out, entry.size)
            return
        }

        writeFileHeaders(out, entry, range)
        if (request.method == "HEAD" || range.length == 0L) {
            out.flush()
            return
        }

        synchronized(channelLock) {
            val stream = try {
                entry.opener.open(range.start)
            } catch (e: Exception) {
                Log.e(TAG, "stream open failed (${e.javaClass.simpleName})")
                return
            }
            stream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = range.length
                while (remaining > 0) {
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.flush()
            }
        }
    }

    private fun resolveRange(rangeHeader: String?, size: Long): ResolvedRange? {
        if (rangeHeader == null) {
            return ResolvedRange(
                start = 0,
                end = if (size == 0L) -1 else size - 1,
                length = size,
                isPartial = false,
            )
        }
        val equals = rangeHeader.indexOf('=')
        if (equals <= 0 || !rangeHeader.substring(0, equals).equals("bytes", ignoreCase = true)) {
            return null
        }
        val spec = rangeHeader.substring(equals + 1)
        if (spec.isEmpty() || ',' in spec) return null
        val dash = spec.indexOf('-')
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0 || size == 0L) return null

        val first = spec.substring(0, dash)
        val last = spec.substring(dash + 1)
        val start: Long
        val end: Long
        if (first.isEmpty()) {
            val suffixLength = parseDecimal(last) ?: return null
            if (suffixLength == 0L) return null
            start = (size - suffixLength).coerceAtLeast(0L)
            end = size - 1
        } else {
            start = parseDecimal(first) ?: return null
            if (start >= size) return null
            end = if (last.isEmpty()) {
                size - 1
            } else {
                val requestedEnd = parseDecimal(last) ?: return null
                if (requestedEnd < start) return null
                requestedEnd.coerceAtMost(size - 1)
            }
        }
        return ResolvedRange(start, end, end - start + 1, isPartial = true)
    }

    private fun writeFileHeaders(out: OutputStream, entry: Entry, range: ResolvedRange) {
        val headers = buildString {
            if (range.isPartial) {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Range: bytes ${range.start}-${range.end}/${entry.size}\r\n")
            } else {
                append("HTTP/1.1 200 OK\r\n")
            }
            append("Content-Type: ${entry.contentType}\r\n")
            append("Content-Length: ${range.length}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
    }

    private fun writeRangeNotSatisfiable(out: OutputStream, size: Long) {
        val response = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
            "Content-Range: bytes */$size\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeStatusQuietly(out: OutputStream, code: Int, reason: String) {
        try {
            writeStatus(out, code, reason)
        } catch (_: Exception) {
            // The connection is being closed regardless.
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        val allow = if (code == 405) "Allow: GET, HEAD\r\n" else ""
        val response = "HTTP/1.1 $code $reason\r\n" +
            allow +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
            // Already closed or disconnected.
        }
    }

    private fun parseDecimal(value: String): Long? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toLongOrNull()
    }

    private fun isSafeContentType(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_CONTENT_TYPE_LENGTH &&
            value.all { it.code in 0x20..0x7e }

    private fun isHttpToken(value: String): Boolean =
        value.isNotEmpty() && value.all { character ->
            character.code in '0'.code..'9'.code ||
                character.code in 'A'.code..'Z'.code ||
                character.code in 'a'.code..'z'.code ||
                character in "!#$%&'*+-.^_`|~"
        }

    private fun containsControlCharacter(value: String): Boolean =
        value.any { it.code <= 0x1f || it.code == 0x7f }

    private fun containsInvalidHeaderValueCharacter(value: String): Boolean =
        value.any { (it.code <= 0x1f && it != '\t') || it.code == 0x7f }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeTokenUrlSafe(bytes: ByteArray): String =
        Base64.UrlSafe.encode(bytes).trimEnd('=')

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private class BoundedLineReader(
        private val input: InputStream,
    ) {
        private var totalBytes = 0

        fun readLine(maxLineBytes: Int, tooLongCode: Int, tooLongReason: String): String? {
            val bytes = ByteArrayOutputStream(minOf(maxLineBytes, 256))
            while (true) {
                val value = readByte() ?: return if (bytes.size() == 0) {
                    null
                } else {
                    throw HttpRequestException(400, "Bad Request")
                }
                when (value) {
                    '\r'.code -> {
                        if (readByte() != '\n'.code) throw HttpRequestException(400, "Bad Request")
                        return bytes.toString(Charsets.ISO_8859_1.name())
                    }
                    '\n'.code -> throw HttpRequestException(400, "Bad Request")
                    else -> {
                        if (bytes.size() >= maxLineBytes) {
                            throw HttpRequestException(tooLongCode, tooLongReason)
                        }
                        bytes.write(value)
                    }
                }
            }
        }

        private fun readByte(): Int? {
            val value = input.read()
            if (value < 0) return null
            totalBytes += 1
            if (totalBytes > MAX_HEADER_BYTES) {
                throw HttpRequestException(431, "Request Header Fields Too Large")
            }
            return value
        }
    }
}
