package sh.haven.core.usb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Wire protocol for the USB proxy socket (Slice 2+).
 *
 * The guest shim ([libhaven_usb.so], via LD_PRELOAD or Mono DllMap) speaks this
 * to the [UsbProxyServer] over an abstract `LocalSocket`; the server fulfils
 * each request against the brokered [UsbDeviceConnection]. Keeping all USB I/O
 * on the Android side means the shim never needs a real device node or root.
 *
 * Frame: `u32 length` (of opcode+payload, big-endian) · `u8 opcode` · payload.
 * Big-endian throughout for predictable C-side parsing.
 *
 * Request payloads:
 *  - GET_DESCRIPTORS: empty.
 *  - CONTROL: u8 requestType, u8 request, u16 value, u16 index, u16 length,
 *             u32 timeoutMs, then `length`/data bytes (OUT payload; empty for IN).
 *  - BULK:    u8 endpoint, u32 length, u32 timeoutMs, then data bytes (OUT only).
 *  - CLOSE:   empty.
 *
 * Response payload: `i32 status` (>=0 = bytes transferred, <0 = error) followed
 * by `status` data bytes for IN/descriptor reads (absent on error or OUT).
 */
object UsbProxyProtocol {

    const val OP_AUTH: Int = 0x01
    const val OP_GET_DESCRIPTORS: Int = 0x02
    const val OP_CONTROL: Int = 0x03
    const val OP_BULK: Int = 0x04
    const val OP_CLOSE: Int = 0x05

    /** Max frame payload we'll read, a guard against a desync corrupting length. */
    const val MAX_FRAME: Int = 1 shl 20 // 1 MiB
    const val MAX_TRANSFER: Int = MAX_FRAME - 32
    const val MAX_TIMEOUT_MS: Int = 30_000

    sealed interface Request {
        data class Auth(val token: ByteArray) : Request {
            override fun equals(other: Any?) = other is Auth && token.contentEquals(other.token)
            override fun hashCode(): Int = token.contentHashCode()
        }
        data object GetDescriptors : Request
        data class Control(
            val requestType: Int,
            val request: Int,
            val value: Int,
            val index: Int,
            val length: Int,
            val timeoutMs: Int,
            val data: ByteArray,
        ) : Request {
            override fun equals(other: Any?) = other is Control &&
                requestType == other.requestType && request == other.request &&
                value == other.value && index == other.index && length == other.length &&
                timeoutMs == other.timeoutMs && data.contentEquals(other.data)
            override fun hashCode(): Int {
                var h = requestType
                h = 31 * h + request; h = 31 * h + value; h = 31 * h + index
                h = 31 * h + length; h = 31 * h + timeoutMs; h = 31 * h + data.contentHashCode()
                return h
            }
        }
        data class Bulk(
            val endpoint: Int,
            val length: Int,
            val timeoutMs: Int,
            val data: ByteArray,
        ) : Request {
            override fun equals(other: Any?) = other is Bulk &&
                endpoint == other.endpoint && length == other.length &&
                timeoutMs == other.timeoutMs && data.contentEquals(other.data)
            override fun hashCode(): Int {
                var h = endpoint; h = 31 * h + length; h = 31 * h + timeoutMs
                h = 31 * h + data.contentHashCode(); return h
            }
        }
        data object Close : Request
    }

    data class Response(val status: Int, val data: ByteArray) {
        override fun equals(other: Any?) = other is Response &&
            status == other.status && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * status + data.contentHashCode()
    }

    // ---- Request encode/decode -------------------------------------------

    fun encodeRequest(req: Request): ByteArray {
        val body = when (req) {
            is Request.Auth -> buildBytes {
                require(req.token.size in 16..128) { "invalid auth token length" }
                writeByte(OP_AUTH)
                write(req.token)
            }
            Request.GetDescriptors -> byteArrayOf(OP_GET_DESCRIPTORS.toByte())
            Request.Close -> byteArrayOf(OP_CLOSE.toByte())
            is Request.Control -> buildBytes {
                writeByte(OP_CONTROL)
                writeByte(req.requestType)
                writeByte(req.request)
                writeShort(req.value)
                writeShort(req.index)
                writeShort(req.length)
                writeInt(req.timeoutMs)
                write(req.data)
            }
            is Request.Bulk -> buildBytes {
                writeByte(OP_BULK)
                writeByte(req.endpoint)
                writeInt(req.length)
                writeInt(req.timeoutMs)
                write(req.data)
            }
        }
        return frame(body)
    }

    /** Read one request frame from [input], or null at clean end-of-stream. */
    fun readRequest(input: DataInputStream): Request? {
        val body = readFrame(input) ?: return null
        val payload = DataInputStream(body.inputStream())
        return when (val op = payload.readUnsignedByte()) {
            OP_AUTH -> Request.Auth(payload.readRemaining(MAX_AUTH_TOKEN_BYTES).also {
                require(it.size in 16..MAX_AUTH_TOKEN_BYTES) { "invalid auth token length" }
            })
            OP_GET_DESCRIPTORS -> Request.GetDescriptors.also { payload.requireFullyConsumed() }
            OP_CLOSE -> Request.Close.also { payload.requireFullyConsumed() }
            OP_CONTROL -> {
                val requestType = payload.readUnsignedByte()
                val request = payload.readUnsignedByte()
                val value = payload.readUnsignedShort()
                val index = payload.readUnsignedShort()
                val length = payload.readUnsignedShort()
                val timeoutMs = payload.readInt()
                require(length in 0..MAX_TRANSFER) { "control length $length out of range" }
                require(timeoutMs in 1..MAX_TIMEOUT_MS) { "control timeout $timeoutMs out of range" }
                val data = payload.readRemaining(MAX_TRANSFER)
                val isIn = (requestType and 0x80) != 0
                require((isIn && data.isEmpty()) || (!isIn && data.size == length)) {
                    "control payload length ${data.size} does not match direction/length $length"
                }
                Request.Control(requestType, request, value, index, length, timeoutMs, data)
            }
            OP_BULK -> {
                val endpoint = payload.readUnsignedByte()
                val length = payload.readInt()
                val timeoutMs = payload.readInt()
                require(length in 0..MAX_TRANSFER) { "bulk length $length out of range" }
                require(timeoutMs in 1..MAX_TIMEOUT_MS) { "bulk timeout $timeoutMs out of range" }
                val data = payload.readRemaining(MAX_TRANSFER)
                val isIn = (endpoint and 0x80) != 0
                require((isIn && data.isEmpty()) || (!isIn && data.size == length)) {
                    "bulk payload length ${data.size} does not match direction/length $length"
                }
                Request.Bulk(endpoint, length, timeoutMs, data)
            }
            else -> throw IllegalArgumentException("unknown opcode $op")
        }
    }

    // ---- Response encode/decode ------------------------------------------

    fun encodeResponse(resp: Response): ByteArray = frame(buildBytes {
        require(resp.status >= -1 && resp.status <= MAX_TRANSFER) { "response status out of range" }
        require(resp.status <= 0 || resp.data.size == resp.status) { "response data/status mismatch" }
        writeInt(resp.status)
        if (resp.status > 0) write(resp.data)
    })

    fun readResponse(input: DataInputStream): Response {
        val body = readFrame(input) ?: throw EOFException("stream closed before response")
        val payload = DataInputStream(body.inputStream())
        val status = payload.readInt()
        require(status >= -1 && status <= MAX_TRANSFER) { "response status $status out of range" }
        val data = if (status > 0) payload.readRemaining(MAX_TRANSFER) else ByteArray(0).also {
            payload.requireFullyConsumed()
        }
        require(status <= 0 || data.size == status) { "response data/status mismatch" }
        return Response(status, data)
    }

    // ---- Framing helpers -------------------------------------------------

    private fun frame(body: ByteArray): ByteArray = buildBytes {
        writeInt(body.size)
        write(body)
    }

    private fun readFrame(input: DataInputStream): ByteArray? {
        val len = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(len in 1..MAX_FRAME) { "frame length $len out of range" }
        val buf = ByteArray(len)
        input.readFully(buf)
        return buf
    }

    /** Read the bounded remainder of an already-bounded frame. */
    private fun DataInputStream.readRemaining(maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            require(out.size() + n <= maxBytes) { "payload exceeds $maxBytes bytes" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun DataInputStream.requireFullyConsumed() {
        require(read() == -1) { "unexpected trailing payload" }
    }

    private inline fun buildBytes(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }

    private const val MAX_AUTH_TOKEN_BYTES = 128
}
