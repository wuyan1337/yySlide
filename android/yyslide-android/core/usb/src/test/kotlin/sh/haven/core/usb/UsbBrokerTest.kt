package sh.haven.core.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [UsbBroker.listDevices] / describe(): the parts that run
 * without the system permission round-trip or a live connection. Transfer
 * dispatch is verified on-device (Slice 1 acceptance) since it routes entirely
 * through Android framework objects.
 */
class UsbBrokerTest {

    private fun endpoint(address: Int, dir: Int, type: Int): UsbEndpoint = mockk {
        every { this@mockk.address } returns address
        every { direction } returns dir
        every { this@mockk.type } returns type
        every { maxPacketSize } returns 64
    }

    private fun iface(id: Int, cls: Int, eps: List<UsbEndpoint>): UsbInterface = mockk {
        every { this@mockk.id } returns id
        every { interfaceClass } returns cls
        every { interfaceSubclass } returns 0
        every { interfaceProtocol } returns 0
        every { endpointCount } returns eps.size
        eps.forEachIndexed { i, ep -> every { getEndpoint(i) } returns ep }
    }

    private fun device(
        name: String,
        vid: Int,
        pid: Int,
        ifaces: List<UsbInterface>,
    ): UsbDevice = mockk {
        every { deviceName } returns name
        every { vendorId } returns vid
        every { productId } returns pid
        every { deviceClass } returns 0
        every { manufacturerName } returns "Evolv"
        every { productName } returns "DNA 100C"
        every { serialNumber } returns "SN123"
        every { interfaceCount } returns ifaces.size
        ifaces.forEachIndexed { i, f -> every { getInterface(i) } returns f }
    }

    private fun broker(usbManager: UsbManager): UsbBroker {
        val context: Context = mockk {
            every { getSystemService(Context.USB_SERVICE) } returns usbManager
        }
        return UsbBroker(context)
    }

    @Test
    fun `describes endpoints and interfaces`() {
        val epIn = endpoint(0x81, UsbConstants.USB_DIR_IN, UsbConstants.USB_ENDPOINT_XFER_INT)
        val epOut = endpoint(0x01, UsbConstants.USB_DIR_OUT, UsbConstants.USB_ENDPOINT_XFER_BULK)
        val dev = device("/dev/bus/usb/001/004", 0x9999, 0x0001, listOf(
            iface(0, UsbConstants.USB_CLASS_HID, listOf(epIn, epOut)),
        ))
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns true
        }

        val info = broker(usb).listDevices().single()

        assertEquals("/dev/bus/usb/001/004", info.deviceName)
        assertEquals("9999:0001", info.vidPid)
        assertTrue(info.hasPermission)
        assertFalse(info.isOpen)
        assertEquals("Evolv", info.manufacturerName)
        assertEquals("DNA 100C", info.productName)
        assertEquals("SN123", info.serialNumber)

        val iface = info.interfaces.single()
        assertEquals(UsbConstants.USB_CLASS_HID, iface.interfaceClass)
        assertEquals(2, iface.endpoints.size)
        val inEp = iface.endpoints.first { it.direction == "in" }
        assertEquals(0x81, inEp.address)
        assertEquals("interrupt", inEp.type)
        val outEp = iface.endpoints.first { it.direction == "out" }
        assertEquals("bulk", outEp.type)
    }

    @Test
    fun `hides descriptor strings without permission`() {
        val dev = device("/dev/bus/usb/001/005", 0x1, 0x2, emptyList())
        val usb: UsbManager = mockk {
            every { deviceList } returns hashMapOf(dev.deviceName to dev)
            every { hasPermission(dev) } returns false
        }

        val info = broker(usb).listDevices().single()

        assertFalse(info.hasPermission)
        assertNull(info.manufacturerName)
        assertNull(info.productName)
        assertNull(info.serialNumber)
    }

    @Test
    fun `transfer result equality is content-based`() {
        val a = TransferResult(3, byteArrayOf(1, 2, 3))
        val b = TransferResult(3, byteArrayOf(1, 2, 3))
        val c = TransferResult(3, byteArrayOf(1, 2, 4))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
