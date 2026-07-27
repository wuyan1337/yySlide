package sh.haven.core.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream

/** Round-trip coverage for the USB proxy wire protocol (Slice 2). */
class UsbProxyProtocolTest {

    private fun roundTripRequest(req: UsbProxyProtocol.Request): UsbProxyProtocol.Request? {
        val bytes = UsbProxyProtocol.encodeRequest(req)
        return UsbProxyProtocol.readRequest(DataInputStream(bytes.inputStream()))
    }

    private fun roundTripResponse(resp: UsbProxyProtocol.Response): UsbProxyProtocol.Response {
        val bytes = UsbProxyProtocol.encodeResponse(resp)
        return UsbProxyProtocol.readResponse(DataInputStream(bytes.inputStream()))
    }

    @Test
    fun `get descriptors and close round-trip`() {
        assertEquals(UsbProxyProtocol.Request.GetDescriptors, roundTripRequest(UsbProxyProtocol.Request.GetDescriptors))
        assertEquals(UsbProxyProtocol.Request.Close, roundTripRequest(UsbProxyProtocol.Request.Close))
    }

    @Test
    fun `control request round-trips fields and payload`() {
        val req = UsbProxyProtocol.Request.Control(
            requestType = 0x21, request = 9, value = 0x0300, index = 0,
            length = 0, timeoutMs = 1000, data = byteArrayOf(1, 2, 3, 4, 5),
        )
        assertEquals(req, roundTripRequest(req))
    }

    @Test
    fun `control IN request preserves wide unsigned fields`() {
        // requestType 0x80 (IN), wValue/wIndex at full u16 range.
        val req = UsbProxyProtocol.Request.Control(
            requestType = 0x80, request = 6, value = 0xFF00, index = 0xFFFF,
            length = 64, timeoutMs = 2000, data = ByteArray(0),
        )
        assertEquals(req, roundTripRequest(req))
    }

    @Test
    fun `bulk request round-trips`() {
        val req = UsbProxyProtocol.Request.Bulk(
            endpoint = 0x81, length = 64, timeoutMs = 500, data = ByteArray(0),
        )
        assertEquals(req, roundTripRequest(req))
        val out = UsbProxyProtocol.Request.Bulk(
            endpoint = 0x01, length = 0, timeoutMs = 500, data = byteArrayOf(9, 8, 7),
        )
        assertEquals(out, roundTripRequest(out))
    }

    @Test
    fun `response with data round-trips`() {
        val descriptor = byteArrayOf(0x12, 0x01, 0x00, 0x02, 0xEF.toByte(), 0x02, 0x01, 0x40)
        assertEquals(
            UsbProxyProtocol.Response(descriptor.size, descriptor),
            roundTripResponse(UsbProxyProtocol.Response(descriptor.size, descriptor)),
        )
    }

    @Test
    fun `error response carries no data`() {
        val resp = roundTripResponse(UsbProxyProtocol.Response(-110, byteArrayOf(1, 2, 3)))
        assertEquals(-110, resp.status)
        assertEquals(0, resp.data.size)
    }

    @Test
    fun `clean end of stream yields null request`() {
        assertNull(UsbProxyProtocol.readRequest(DataInputStream(ByteArray(0).inputStream())))
    }
}
