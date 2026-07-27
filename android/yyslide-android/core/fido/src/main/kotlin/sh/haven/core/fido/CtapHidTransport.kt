package sh.haven.core.fido

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.io.Closeable
import java.io.IOException
import kotlin.random.Random

private const val TAG = "CtapHid"

/** CTAPHID command IDs. */
private const val CTAPHID_INIT: Byte = 0x06
private const val CTAPHID_CBOR: Byte = 0x10
private const val CTAPHID_KEEPALIVE: Byte = 0x3B.toByte()
private const val CTAPHID_ERROR: Byte = 0x3F

/** CTAPHID keepalive status. */
private const val STATUS_UPNEEDED: Byte = 0x02

/** HID report size for FIDO (always 64 bytes). */
private const val REPORT_SIZE = 64
private const val INIT_DATA_SIZE = 57 // 64 - 4(CID) - 1(CMD) - 2(LEN)
private const val CONT_DATA_SIZE = 59 // 64 - 4(CID) - 1(SEQ)

/** Broadcast channel ID for CTAPHID_INIT. */
private val BROADCAST_CID = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

/** Timeout for USB transfers (30s for user presence). */
private const val TRANSFER_TIMEOUT_MS = 30_000

/**
 * CTAPHID protocol implementation over Android USB.
 * Handles HID packet framing, channel allocation, and keepalive.
 */
class CtapHidTransport(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) : Closeable {

    private var channelId: ByteArray = BROADCAST_CID

    /**
     * Allocate a channel ID by sending CTAPHID_INIT on the broadcast channel.
     */
    fun init() {
        val nonce = Random.nextBytes(8)
        sendRaw(BROADCAST_CID, CTAPHID_INIT, nonce)

        val (_, cmd, payload) = recvRaw()
        require(cmd == (CTAPHID_INIT.toInt() or 0x80).toByte()) {
            "Expected CTAPHID_INIT response, got 0x${"%02x".format(cmd)}"
        }
        require(payload.size >= 17) { "CTAPHID_INIT response too short: ${payload.size}" }

        // Verify nonce matches
        for (i in 0 until 8) {
            require(payload[i] == nonce[i]) { "CTAPHID_INIT nonce mismatch at byte $i" }
        }

        // Extract allocated channel ID (bytes 8-11)
        channelId = payload.sliceArray(8..11)
        Log.d(TAG, "Allocated channel: ${channelId.joinToString("") { "%02x".format(it) }}")
    }

    /**
     * Send a CTAP2 CBOR command and return the response payload.
     * Handles CTAPHID_KEEPALIVE packets during user presence wait.
     *
     * @param onKeepAlive called when the authenticator signals user touch needed
     */
    fun sendCborCommand(data: ByteArray, onKeepAlive: (() -> Unit)? = null): ByteArray {
        sendRaw(channelId, CTAPHID_CBOR, data)

        while (true) {
            val (cid, cmd, payload) = recvRaw()
            val cmdByte = (cmd.toInt() and 0x7F).toByte()

            when (cmdByte) {
                CTAPHID_CBOR -> return payload
                CTAPHID_KEEPALIVE -> {
                    if (payload.isNotEmpty() && payload[0] == STATUS_UPNEEDED) {
                        Log.d(TAG, "Keepalive: user presence needed")
                        onKeepAlive?.invoke()
                    }
                    // Continue waiting
                }
                CTAPHID_ERROR -> {
                    val errCode = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1
                    throw IOException("CTAPHID error: 0x${"%02x".format(errCode)}")
                }
                else -> {
                    Log.w(TAG, "Unexpected CTAPHID command: 0x${"%02x".format(cmd)}")
                }
            }
        }
    }

    override fun close() {
        connection.close()
    }

    // --- HID packet framing ---

    private fun sendRaw(cid: ByteArray, cmd: Byte, data: ByteArray) {
        val packets = mutableListOf<ByteArray>()

        // Initialization packet
        val initPacket = ByteArray(REPORT_SIZE)
        cid.copyInto(initPacket, 0)
        initPacket[4] = (cmd.toInt() or 0x80).toByte() // Set bit 7 for init packet
        initPacket[5] = ((data.size shr 8) and 0xFF).toByte()
        initPacket[6] = (data.size and 0xFF).toByte()
        val initLen = minOf(data.size, INIT_DATA_SIZE)
        data.copyInto(initPacket, 7, 0, initLen)
        packets.add(initPacket)

        // Continuation packets
        var offset = initLen
        var seq = 0
        while (offset < data.size) {
            val contPacket = ByteArray(REPORT_SIZE)
            cid.copyInto(contPacket, 0)
            contPacket[4] = (seq and 0x7F).toByte()
            val contLen = minOf(data.size - offset, CONT_DATA_SIZE)
            data.copyInto(contPacket, 5, offset, offset + contLen)
            packets.add(contPacket)
            offset += contLen
            seq++
        }

        for (packet in packets) {
            val sent = connection.bulkTransfer(endpointOut, packet, packet.size, TRANSFER_TIMEOUT_MS)
            if (sent < 0) throw IOException("USB send failed")
        }
    }

    private fun recvRaw(): Triple<ByteArray, Byte, ByteArray> {
        val buf = ByteArray(REPORT_SIZE)

        // Read initialization packet
        val read = connection.bulkTransfer(endpointIn, buf, buf.size, TRANSFER_TIMEOUT_MS)
        if (read < 7) throw IOException("USB recv failed or too short: $read bytes")

        val cid = buf.sliceArray(0..3)
        val cmd = buf[4]
        val totalLen = ((buf[5].toInt() and 0xFF) shl 8) or (buf[6].toInt() and 0xFF)
        val initLen = minOf(totalLen, INIT_DATA_SIZE)

        val result = ByteArray(totalLen)
        buf.copyInto(result, 0, 7, 7 + initLen)

        // Read continuation packets
        var offset = initLen
        while (offset < totalLen) {
            val contRead = connection.bulkTransfer(endpointIn, buf, buf.size, TRANSFER_TIMEOUT_MS)
            if (contRead < 5) throw IOException("USB cont recv failed: $contRead bytes")
            val contLen = minOf(totalLen - offset, CONT_DATA_SIZE)
            buf.copyInto(result, offset, 5, 5 + contLen)
            offset += contLen
        }

        return Triple(cid, cmd, result)
    }
}
