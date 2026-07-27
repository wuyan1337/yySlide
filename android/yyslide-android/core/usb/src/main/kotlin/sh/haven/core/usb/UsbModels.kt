package sh.haven.core.usb

/** Endpoint descriptor as surfaced to the agent. */
data class UsbEndpointInfo(
    /** bEndpointAddress (bit 7 = direction). */
    val address: Int,
    /** "in" or "out". */
    val direction: String,
    /** "control" | "isochronous" | "bulk" | "interrupt" | "unknown". */
    val type: String,
    val maxPacketSize: Int,
)

/** Interface descriptor as surfaced to the agent. */
data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<UsbEndpointInfo>,
)

/**
 * A snapshot of one attached USB device. Descriptor strings are null until
 * permission is held; [hasPermission] tells the caller whether to request it.
 */
data class UsbDeviceInfo(
    /** Stable key for this attachment: the `/dev/bus/usb/BBB/DDD` path. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val hasPermission: Boolean,
    val isOpen: Boolean,
    val interfaces: List<UsbInterfaceInfo>,
) {
    /** "1234:5678" VID:PID, the conventional lsusb form. */
    val vidPid: String get() = "%04x:%04x".format(vendorId, productId)
}

/** Result of a control/bulk transfer: bytes moved, plus data read on IN. */
data class TransferResult(
    val bytesTransferred: Int,
    /** Data read for IN transfers; empty for OUT. */
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransferResult) return false
        return bytesTransferred == other.bytesTransferred && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * bytesTransferred + data.contentHashCode()
}
