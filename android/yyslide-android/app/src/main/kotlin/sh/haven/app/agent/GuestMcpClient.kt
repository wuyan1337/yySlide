package sh.haven.app.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking client for a guest-resident streamable-HTTP MCP server
 * (e.g. a KiCad MCP on `127.0.0.1:<port>/mcp` inside the proot, reachable on
 * device loopback because proot shares the device netns). Used by [McpTools]
 * to aggregate a guest server's tools into Haven's own MCP surface so the
 * external agent only ever talks to Haven.
 *
 * Blocking is fine: [McpServer] handles each client connection on its own
 * thread, so an outbound call here never stalls other clients. All calls are
 * bounded by connect/read timeouts and the response stream is read only until
 * the first JSON-RPC frame (then the socket is closed) — so a server that
 * holds its SSE stream open (FastMCP does) never makes us hang.
 */
internal class GuestMcpClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 25000,
) {
    private class SessionExpired : RuntimeException("guest MCP session expired")

    @Volatile private var sessionId: String? = null
    private var nextId = 1

    /** Discover the guest server's tools (empty list on any failure). */
    fun listTools(): List<JSONObject> {
        ensureSession()
        val result = rpcWithRetry("tools/list", JSONObject())
        val arr = result?.optJSONArray("tools") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * Forward a tool call. Returns the guest's MCP `result` object verbatim
     * (typically `{ content:[...], structuredContent?, isError? }`) so the
     * caller can pass its content blocks through unchanged.
     */
    fun callTool(name: String, arguments: JSONObject): JSONObject {
        ensureSession()
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        return rpcWithRetry("tools/call", params) ?: JSONObject()
    }

    private fun ensureSession() {
        if (sessionId != null) return
        val params = JSONObject().apply {
            put("protocolVersion", "2025-06-18")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply { put("name", "haven-proxy"); put("version", "1") })
        }
        rpc("initialize", params)
        // Best-effort initialized notification (no id, no result expected).
        runCatching { post(JSONObject().apply { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }) }
    }

    private fun rpcWithRetry(method: String, params: JSONObject): JSONObject? =
        try {
            rpc(method, params)
        } catch (e: SessionExpired) {
            sessionId = null
            ensureSession()
            rpc(method, params)
        }

    private fun rpc(method: String, params: JSONObject): JSONObject? {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", nextId++)
            put("method", method)
            put("params", params)
        }
        val json = post(body)
        json?.optJSONObject("error")?.let { err ->
            throw RuntimeException("guest MCP error ${err.optInt("code")}: ${err.optString("message")}")
        }
        return json?.optJSONObject("result")
    }

    /** POST a JSON-RPC frame; return the parsed response object (or null for a notification). */
    private fun post(body: JSONObject): JSONObject? {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code == 404) throw SessionExpired()
            val ctype = conn.contentType ?: ""
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val obj = if (ctype.contains("event-stream", ignoreCase = true)) {
                readFirstSseFrame(stream)
            } else {
                val text = stream?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
                if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
            }
            if (code !in 200..299 && code != 404) {
                throw RuntimeException("guest MCP HTTP $code")
            }
            return obj
        } finally {
            // Aborts a still-open SSE stream so we never block on EOF.
            conn.disconnect()
        }
    }

    /** Read an SSE response only until the first `data:` JSON frame, then stop. */
    private fun readFirstSseFrame(ins: InputStream?): JSONObject? {
        ins ?: return null
        val r = ins.bufferedReader()
        while (true) {
            val line = r.readLine() ?: break
            val l = line.trim()
            if (l.startsWith("data:")) {
                val payload = l.removePrefix("data:").trim()
                if (payload.startsWith("{")) return runCatching { JSONObject(payload) }.getOrNull()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "GuestMcpClient"
    }
}
