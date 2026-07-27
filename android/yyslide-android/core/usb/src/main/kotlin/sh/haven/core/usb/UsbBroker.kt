package sh.haven.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broker for the phone's USB devices.
 *
 * This is the Android side of Haven's USB Layer-B primitive: a non-rooted
 * phone denies `/dev/bus/usb` to both the app uid and the proot tracee, so the
 * only way a Linux guest (or the agent) can touch a USB device is through an
 * Android [UsbManager] app that owns the connection. [UsbBroker] is that owner.
 *
 * Slice 1 exposes enumeration + runtime permission + raw control/bulk transfers
 * directly to the MCP agent. Later slices add a userspace proxy socket that a
 * guest shim ([sh.haven.core.usb] over an abstract `LocalSocket`) speaks, so
 * unmodified guest apps reach the device through these same transfers.
 *
 * Permission handling mirrors
 * `sh.haven.core.fido.FidoAuthenticator.ensureUsbPermission` — same
 * `FLAG_MUTABLE` + explicit-package fix (#15) so the system grant dialog
 * round-trips correctly on Android 12+.
 *
 * Devices are keyed by [UsbDevice.getDeviceName] (the `/dev/bus/usb/BBB/DDD`
 * path), which is stable for the life of an attachment and unique per device.
 */
@Singleton
class UsbBroker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Open connections keyed by device name, plus the interfaces we claimed. */
    private data class OpenHandle(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val claimedInterfaces: MutableSet<Int> = mutableSetOf(),
    )

    private val open = ConcurrentHashMap<String, OpenHandle>()

    // ---- Enumeration ------------------------------------------------------

    /**
     * Snapshot every attached USB device. Descriptor strings (manufacturer,
     * product, serial) are only populated when permission is already held —
     * Android returns null for them otherwise, and reading them must not
     * trigger a permission prompt from a read-only listing.
     */
    fun listDevices(): List<UsbDeviceInfo> =
        usbManager.deviceList.values.map { describe(it) }

    private fun describe(device: UsbDevice): UsbDeviceInfo {
        val permitted = usbManager.hasPermission(device)
        val interfaces = (0 until device.interfaceCount).map { i ->
            val iface: UsbInterface = device.getInterface(i)
            val endpoints = (0 until iface.endpointCount).map { e ->
                val ep: UsbEndpoint = iface.getEndpoint(e)
                UsbEndpointInfo(
                    address = ep.address,
                    direction = if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                    type = endpointTypeName(ep.type),
                    maxPacketSize = ep.maxPacketSize,
                )
            }
            UsbInterfaceInfo(
                id = iface.id,
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                interfaceProtocol = iface.interfaceProtocol,
                endpoints = endpoints,
            )
        }
        return UsbDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            manufacturerName = if (permitted) device.manufacturerName else null,
            productName = if (permitted) device.productName else null,
            serialNumber = runCatching { if (permitted) device.serialNumber else null }.getOrNull(),
            hasPermission = permitted,
            isOpen = open.containsKey(device.deviceName),
            interfaces = interfaces,
        )
    }

    private fun endpointTypeName(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "unknown"
    }

    private fun findDevice(deviceName: String): UsbDevice =
        usbManager.deviceList[deviceName]
            ?: throw IOException("USB device '$deviceName' not attached")

    // ---- Permission -------------------------------------------------------

    fun hasPermission(deviceName: String): Boolean =
        usbManager.deviceList[deviceName]?.let { usbManager.hasPermission(it) } ?: false

    /**
     * Request runtime permission for [deviceName], waiting for the system
     * dialog. Returns true if granted (or already held). Mirrors the FIDO
     * permission flow, including the FLAG_MUTABLE + explicit-package fix.
     */
    suspend fun requestPermission(deviceName: String): Boolean {
        val device = findDevice(deviceName)
        if (usbManager.hasPermission(device)) return true

        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    deferred.complete(
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                    )
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        // UsbManager fills the granted-flag extra back into the intent, so it
        // must stay MUTABLE; an explicit-by-package intent keeps Android 14+
        // from rejecting a mutable PendingIntent around an implicit Intent (#15).
        val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(context, 0, permIntent, flags),
        )
        val granted = try {
            withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { deferred.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        return when (granted) {
            null -> throw IOException("USB permission timed out — no response from system dialog")
            else -> granted
        }
    }

    // ---- Open / close -----------------------------------------------------

    /** Open [deviceName] (requesting permission first if needed) and cache the connection. */
    suspend fun openDevice(deviceName: String): UsbDeviceInfo {
        val device = findDevice(deviceName)
        open[deviceName]?.let { return describe(device) }
        if (!requestPermission(deviceName)) throw IOException("USB permission denied for $deviceName")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device $deviceName")
        val handle = OpenHandle(device, connection)
        val existing = open.putIfAbsent(deviceName, handle)
        if (existing != null) {
            runCatching { connection.close() }
        }
        Log.d(TAG, "Opened $deviceName (${"%04x".format(device.vendorId)}:${"%04x".format(device.productId)})")
        return describe(device)
    }

    fun closeDevice(deviceName: String) {
        open.remove(deviceName)?.let { handle -> synchronized(handle) {
                handle.claimedInterfaces.forEach { id ->
                    runCatching {
                        handle.connection.releaseInterface(handle.device.getInterface(indexOfInterfaceId(handle.device, id)))
                    }
                }
                handle.claimedInterfaces.clear()
                runCatching { handle.connection.close() }
                Log.d(TAG, "Closed $deviceName")
            }
        }
    }

    /** Release all open connections — call on broker teardown / app stop. */
    fun closeAll() {
        open.keys.toList().forEach { closeDevice(it) }
    }

    private fun handleFor(deviceName: String): OpenHandle =
        open[deviceName] ?: throw IOException("USB device '$deviceName' not open — call openDevice first")

    /**
     * The device + active-config descriptors as the kernel would return them
     * (the bytes `UsbDeviceConnection.getRawDescriptors()` exposes). The proxy
     * server hands these to the guest shim so it can synthesize the HID report
     * descriptor / libudev attributes without a real device node.
     */
    fun rawDescriptors(deviceName: String): ByteArray {
        val handle = handleFor(deviceName)
        return synchronized(handle) {
            (handle.connection.rawDescriptors ?: ByteArray(0)).copyOf()
        }
    }

    // ---- Transfers --------------------------------------------------------

    /**
     * Endpoint-0 control transfer. [data] is the OUT payload (ignored for IN
     * transfers, where [length] sizes the read buffer). Returns the bytes
     * transferred plus, for IN transfers, the data read.
     */
    fun controlTransfer(
        deviceName: String,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): TransferResult {
        require(length in 0..MAX_TRANSFER_BYTES) { "USB control length out of range" }
        require(timeoutMs in 1..MAX_TRANSFER_TIMEOUT_MS) { "USB control timeout out of range" }
        val handle = handleFor(deviceName)
        val isIn = (requestType and UsbConstants.USB_DIR_IN) != 0
        if (!isIn && (data?.size ?: 0) != length) {
            throw IOException("USB control OUT payload does not match declared length")
        }
        val buffer = if (isIn) ByteArray(length) else (data ?: ByteArray(0))
        val transferred = synchronized(handle) {
            handle.connection.controlTransfer(
                requestType, request, value, index, buffer, if (isIn) length else buffer.size, timeoutMs,
            )
        }
        if (transferred < 0) throw IOException("controlTransfer failed (rc=$transferred)")
        val out = if (isIn) buffer.copyOf(transferred) else ByteArray(0)
        return TransferResult(transferred, out)
    }

    /**
     * Bulk or interrupt transfer on the endpoint with [endpointAddress]. The
     * owning interface is claimed on first use. Direction is taken from the
     * endpoint descriptor; [data] is the OUT payload, [length] the IN buffer size.
     */
    fun bulkTransfer(
        deviceName: String,
        endpointAddress: Int,
        data: ByteArray?,
        length: Int,
        timeoutMs: Int,
    ): TransferResult {
        require(length in 0..MAX_TRANSFER_BYTES) { "USB bulk length out of range" }
        require(timeoutMs in 1..MAX_TRANSFER_TIMEOUT_MS) { "USB bulk timeout out of range" }
        val handle = handleFor(deviceName)
        val (iface, endpoint) = locateEndpoint(handle.device, endpointAddress)
        val isIn = endpoint.direction == UsbConstants.USB_DIR_IN
        if (!isIn && (data?.size ?: 0) != length) {
            throw IOException("USB bulk OUT payload does not match declared length")
        }
        val buffer = if (isIn) ByteArray(length) else (data ?: ByteArray(0))
        val transferred = synchronized(handle) {
            if (handle.claimedInterfaces.add(iface.id)) {
                if (!handle.connection.claimInterface(iface, true)) {
                    handle.claimedInterfaces.remove(iface.id)
                    throw IOException("Failed to claim interface ${iface.id} for endpoint $endpointAddress")
                }
            }
            handle.connection.bulkTransfer(
                endpoint, buffer, if (isIn) length else buffer.size, timeoutMs,
            )
        }
        if (transferred < 0) throw IOException("bulkTransfer failed (rc=$transferred)")
        val out = if (isIn) buffer.copyOf(transferred) else ByteArray(0)
        return TransferResult(transferred, out)
    }

    private fun locateEndpoint(device: UsbDevice, address: Int): Pair<UsbInterface, UsbEndpoint> {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.address == address) return iface to ep
            }
        }
        throw IOException("No endpoint with address 0x${"%02x".format(address)} on ${device.deviceName}")
    }

    private fun indexOfInterfaceId(device: UsbDevice, id: Int): Int =
        (0 until device.interfaceCount).first { device.getInterface(it).id == id }

    companion object {
        private const val TAG = "UsbBroker"
        private const val PERMISSION_TIMEOUT_MS = 30_000L
        private const val MAX_TRANSFER_BYTES = 1 shl 20
        private const val MAX_TRANSFER_TIMEOUT_MS = 30_000

        /** Broadcast action for the runtime-permission grant callback. */
        const val ACTION_USB_PERMISSION = "sh.haven.core.usb.USB_PERMISSION"
    }
}
