package sh.haven.core.fido

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FidoAuthenticator"
private const val ACTION_USB_PERMISSION = "sh.haven.core.fido.USB_PERMISSION"
private const val USB_PERMISSION_TIMEOUT_MS = 30_000L

data class FidoAssertionResult(
    val signature: ByteArray,
    val flags: Byte,
    val counter: Int,
)

/**
 * UI state for an in-flight FIDO2 assertion. The UI should show a modal
 * prompt while [FidoAuthenticator.touchPrompt] is non-null and dismiss it
 * when the flow returns to null.
 */
sealed class FidoTouchPrompt {
    /** Discovery active — waiting for the user to plug in or tap a key. */
    data object WaitingForKey : FidoTouchPrompt()

    /**
     * Key detected; CTAP2 in flight; waiting for the user to physically
     * activate it. [transport] dictates the UI copy: USB users press the
     * key's button, NFC users hold the key against the phone for the
     * full exchange. The latter case is the load-bearing one — pulling
     * the key away too early raises `TagLostException` mid-CTAP and the
     * SSH auth path then disconnects with an opaque "Auth fail" error,
     * which is exactly the failure mode reported in `#15` for
     * SoloKey-via-NFC users.
     */
    data class TouchKey(val transport: Transport) : FidoTouchPrompt() {
        enum class Transport { USB, NFC }
    }

    /**
     * Key requires PIN (verify-required SK key). UI should show a password
     * field and call [submit] with the entered PIN, or [submit] with null
     * to cancel. [retriesRemaining] is the authenticator-reported count
     * after a previous wrong PIN attempt; null on the first attempt.
     */
    data class EnterPin(
        val submit: (String?) -> Unit,
        val retriesRemaining: Int? = null,
    ) : FidoTouchPrompt()
}

/**
 * Manages FIDO2 authenticator interactions using the generic CTAP2 protocol.
 * Works with any FIDO2 security key over USB HID or NFC ISO-DEP —
 * YubiKey, Nitrokey, SoloKeys, Feitian, Trezor, Google Titan, etc.
 *
 * Discovery is self-driven: calling [getAssertion] transparently enumerates
 * already-plugged USB devices, registers a receiver for USB attach events,
 * and — if a host [Activity] has been published via [setActiveActivity] —
 * enables NFC reader mode for the duration of the assertion. All of that
 * is torn down when the assertion completes or fails.
 *
 * For verify-required SK keys (`ssh-keygen -O verify-required`), the
 * [getAssertion] caller passes `requireUv = true`, which triggers a
 * full CTAP2 PIN/UV Auth Protocol exchange (see [Ctap2PinProtocol]) before
 * the GetAssertion call. The user is prompted via the
 * [FidoTouchPrompt.EnterPin] state.
 */
@Singleton
class FidoAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Sealed type for connected security key transport. */
    private sealed class ConnectedDevice {
        data class Usb(val device: UsbDevice) : ConnectedDevice()
        data class Nfc(val tag: Tag) : ConnectedDevice()
    }

    private var pendingDevice: CompletableDeferred<ConnectedDevice>? = null

    /**
     * Weak reference to the currently-resumed foreground [Activity], used
     * as the host for NFC reader mode during an in-flight assertion. Set
     * by the activity's `onResume` / cleared on `onPause`. NFC reader
     * mode is not available outside of a foreground activity context —
     * USB discovery still works without one.
     */
    private var activeActivity: WeakReference<Activity>? = null

    private val _touchPrompt = MutableStateFlow<FidoTouchPrompt?>(null)
    /**
     * Current FIDO2 prompt state observable by the UI. Non-null while an
     * assertion is in flight; transitions [FidoTouchPrompt.WaitingForKey]
     * → [FidoTouchPrompt.TouchKey] → null. Observers should render a
     * modal prompt while non-null.
     */
    val touchPrompt: StateFlow<FidoTouchPrompt?> = _touchPrompt.asStateFlow()

    /** Last assertion error message, readable by the ViewModel for user-facing display. */
    @Volatile var lastAssertionError: String? = null

    /**
     * Publish the foreground activity from `Activity.onResume`. Required to
     * enable NFC reader mode during [getAssertion]; USB-only flows work
     * without it.
     */
    fun setActiveActivity(activity: Activity) {
        activeActivity = WeakReference(activity)
    }

    /**
     * Clear the foreground activity reference from `Activity.onPause`. Only
     * clears when [activity] matches the currently-published one — guards
     * against races where a new activity resumes before the old pauses.
     */
    fun clearActiveActivity(activity: Activity) {
        if (activeActivity?.get() === activity) {
            activeActivity = null
        }
    }

    /**
     * Perform a FIDO2 assertion. Blocks until a security key is connected
     * (USB) or tapped (NFC) and the user touches it to authorise signing.
     *
     * Discovery is started inline and cleaned up in the finally block —
     * callers do not need to pre-arm anything. USB keys already plugged in
     * at call time are detected immediately; otherwise a broadcast receiver
     * catches the attach event.
     *
     * @param rpId         the relying party ID (for SSH this is "ssh:" or
     *                     a custom string set with `ssh-keygen -O application=…`)
     * @param message      the SSH sign data to hash and sign
     * @param credentialId the credential ID (key handle) from the SK key file
     * @param requireUv    true when the SK key was registered with
     *                     verify-required (`SSH_SK_USER_VERIFICATION_REQUIRED`).
     *                     Triggers the CTAP2 clientPIN exchange before the
     *                     actual GetAssertion call.
     */
    suspend fun getAssertion(
        rpId: String,
        message: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean = false,
    ): FidoAssertionResult = withContext(Dispatchers.IO) {
        lastAssertionError = null
        Log.d(TAG, "FIDO2 assertion requested: rpId=$rpId, message=${message.size}b, " +
            "credId=${credentialId.size}b, requireUv=$requireUv")

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(message)

        withDiscoveredFidoDevice { device ->
            // Device just landed — for the non-UV path, switch the prompt to
            // "touch your key now" before sending GetAssertion, with the
            // transport dictated by which discovery branch fired. The UV
            // path goes through `performXxxAssertion` which toggles
            // EnterPin then TouchKey at the right moments.
            if (!requireUv) {
                _touchPrompt.value = FidoTouchPrompt.TouchKey(
                    when (device) {
                        is ConnectedDevice.Usb -> FidoTouchPrompt.TouchKey.Transport.USB
                        is ConnectedDevice.Nfc -> FidoTouchPrompt.TouchKey.Transport.NFC
                    },
                )
            }

            val result = when (device) {
                is ConnectedDevice.Usb -> performUsbAssertion(
                    device.device, rpId, clientDataHash, credentialId, requireUv,
                )
                is ConnectedDevice.Nfc -> performNfcAssertion(
                    device.tag, rpId, clientDataHash, credentialId, requireUv,
                )
            }

            Log.d(TAG, "FIDO2 assertion success: sig=${result.signature.size}b, flags=0x${
                "%02x".format(result.flags)
            }, counter=${result.counter}")

            result
        }
    }

    /**
     * Run [perform] once a FIDO key has been discovered via USB or NFC.
     * Handles the shared discovery / touch-prompt / cleanup lifecycle.
     */
    private suspend fun <R> withDiscoveredFidoDevice(
        perform: suspend (ConnectedDevice) -> R,
    ): R {
        val deferred = CompletableDeferred<ConnectedDevice>()
        pendingDevice = deferred

        // ----- USB: check already connected, else register attach receiver
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val alreadyConnectedUsb = usbManager.deviceList.values.firstOrNull { isFidoHidDevice(it) }
        var usbReceiver: BroadcastReceiver? = null
        if (alreadyConnectedUsb != null) {
            Log.d(TAG, "FIDO key already plugged in: ${alreadyConnectedUsb.productName ?: "(unknown product)"}")
            deferred.complete(ConnectedDevice.Usb(alreadyConnectedUsb))
        } else {
            usbReceiver = registerUsbAttachReceiver(deferred)
            Log.d(TAG, "Registered USB attach receiver; waiting for key to be plugged in")
        }

        // ----- NFC: if an activity is in the foreground, enable reader mode
        val nfcActivity = activeActivity?.get()
        var nfcEnabled = false
        if (nfcActivity != null && !deferred.isCompleted) {
            nfcEnabled = startNfcReaderModeOnMain(nfcActivity, deferred)
            Log.d(TAG, "NFC reader mode ${if (nfcEnabled) "enabled" else "unavailable"} for current activity")
        } else if (nfcActivity == null) {
            Log.d(TAG, "No foreground activity — NFC path disabled, USB only")
        }

        _touchPrompt.value = if (deferred.isCompleted) {
            FidoTouchPrompt.TouchKey(FidoTouchPrompt.TouchKey.Transport.USB)
        } else {
            FidoTouchPrompt.WaitingForKey
        }

        try {
            Log.d(TAG, "Waiting for security key (USB${if (nfcEnabled) " or NFC" else ""})...")
            val device = deferred.await()
            return perform(device)
        } finally {
            pendingDevice = null
            usbReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (_: IllegalArgumentException) {
                    // already unregistered
                }
            }
            if (nfcEnabled && nfcActivity != null) {
                stopNfcReaderModeOnMain(nfcActivity)
            }
            _touchPrompt.value = null
        }
    }

    /**
     * Enumerate resident SSH-SK credentials on an inserted/tapped security
     * key via CTAP 2.1 `authenticatorCredentialManagement`. Lets the user
     * import keys that were registered with `ssh-keygen -O resident`
     * without first having to run `ssh-keygen -K` to extract local stubs
     * (issue #152).
     *
     * Filters returned credentials to RPs whose `id` starts with
     * [rpIdPrefix] (default "ssh", which matches the canonical "ssh:"
     * application name `ssh-keygen` uses for SK keys). Pass a different
     * prefix when discovering WebAuthn-style credentials, or empty string
     * to return everything.
     *
     * Always uses PIN/UV — the credentialManagement command requires a
     * cm-permission token, and the authenticator gates token issuance on
     * PIN entry. A key without a PIN configured raises [IOException]
     * pointing the user at `ykman fido access change-pin`.
     *
     * Returns a list of (rpId, [SkKeyData]) pairs. Each SkKeyData is
     * synthesised from the COSE_Key in the CTAP response — algorithm
     * detected from kty/alg, public key blob built in OpenSSH wire
     * format, application set to the RP id, flags defaulted to 0x01
     * (user-presence). The caller persists the chosen ones into the
     * SSH key store via the same path as file-imported SK keys.
     */
    suspend fun enumerateResidentCredentials(
        rpIdPrefix: String = "ssh",
    ): List<Pair<String, SkKeyData>> = withContext(Dispatchers.IO) {
        lastAssertionError = null
        Log.d(TAG, "FIDO2 enumerateResidentCredentials requested: rpIdPrefix='$rpIdPrefix'")

        withDiscoveredFidoDevice { device ->
            when (device) {
                is ConnectedDevice.Usb -> performUsbEnumerate(device.device, rpIdPrefix)
                is ConnectedDevice.Nfc -> performNfcEnumerate(device.tag, rpIdPrefix)
            }
        }
    }

    /**
     * Register a broadcast receiver for USB attach events that completes
     * [deferred] when a FIDO HID device is plugged in. Returns the receiver
     * for later unregistration.
     */
    private fun registerUsbAttachReceiver(
        deferred: CompletableDeferred<ConnectedDevice>,
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (device != null && isFidoHidDevice(device)) {
                    Log.d(TAG, "USB FIDO device attached: ${device.productName ?: "(unknown product)"}")
                    deferred.complete(ConnectedDevice.Usb(device))
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    /**
     * Enable NFC reader mode on [activity] to catch a FIDO tap and complete
     * [deferred]. NFC APIs must be called on the main thread, hence the
     * handler hop. Returns true if reader mode was successfully enabled,
     * false if the device lacks NFC or the call failed.
     */
    private suspend fun startNfcReaderModeOnMain(
        activity: Activity,
        deferred: CompletableDeferred<ConnectedDevice>,
    ): Boolean {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context) ?: run {
            Log.d(TAG, "NFC not available on this device")
            return false
        }
        return withContext(Dispatchers.Main) {
            try {
                nfcAdapter.enableReaderMode(
                    activity,
                    { tag ->
                        if (IsoDep.get(tag) != null) {
                            Log.d(TAG, "NFC FIDO tag detected")
                            deferred.complete(ConnectedDevice.Nfc(tag))
                        }
                    },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable NFC reader mode: ${e.message}")
                false
            }
        }
    }

    /**
     * Disable NFC reader mode on [activity]. Must be called on the main thread.
     * Swallows exceptions — NFC teardown errors should not mask the assertion
     * result.
     */
    private suspend fun stopNfcReaderModeOnMain(activity: Activity) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context) ?: return
        withContext(Dispatchers.Main) {
            try {
                nfcAdapter.disableReaderMode(activity)
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    /**
     * Request USB permission for [device] if not already held, waiting for
     * the system-dialog response. Used by both the assertion and the
     * credentialManagement enumerate paths so they share the same FLAG_MUTABLE
     * + explicit-package fix from #15 (olmari, Pixel 9 Fold).
     */
    private suspend fun ensureUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB permission already held for ${device.productName}")
            return
        }
        val deviceName = device.productName ?: "(unknown product)"
        Log.d(TAG, "Requesting USB permission for $deviceName")
        val permDeferred = CompletableDeferred<Boolean>()
        val permReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val g = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission callback: granted=$g")
                    permDeferred.complete(g)
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permReceiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        // Android 14+ (targetSdk 34+) rejects FLAG_MUTABLE PendingIntents
        // built around an implicit Intent. UsbManager fills the granted-flag
        // extra back into the intent so it must stay MUTABLE — make the
        // intent explicit by package instead. See #15 (olmari, Pixel 9 Fold).
        val permIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usbManager.requestPermission(
            device,
            PendingIntent.getBroadcast(context, 0, permIntent, flags),
        )
        val granted = try {
            withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) { permDeferred.await() }
        } finally {
            try { context.unregisterReceiver(permReceiver) } catch (_: IllegalArgumentException) {}
        }
        if (granted == null) {
            Log.e(TAG, "USB permission timed out for $deviceName")
            throw IOException("USB permission timed out — no response from system dialog")
        }
        if (!granted) {
            Log.e(TAG, "USB permission denied for $deviceName")
            throw IOException("USB permission denied")
        }
        Log.d(TAG, "USB permission granted for $deviceName")
    }

    private suspend fun performUsbAssertion(
        device: UsbDevice,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean,
    ): FidoAssertionResult {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        ensureUsbPermission(usbManager, device)

        // Find HID interface and endpoints
        val hidInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: throw IOException("No HID interface on FIDO device")

        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until hidInterface.endpointCount) {
            val ep = hidInterface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) endpointOut = ep
        }
        requireNotNull(endpointIn) { "No IN endpoint on HID interface" }
        requireNotNull(endpointOut) { "No OUT endpoint on HID interface" }

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device")
        connection.claimInterface(hidInterface, true)

        try {
            CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
                Log.d(TAG, "CTAPHID init...")
                transport.init()

                val (pinUvAuthParam, pinProtocol) = if (requireUv) {
                    val (token, proto) = runUvPinProtocol(
                        rpId, Ctap2Cbor.PERMISSION_GET_ASSERTION,
                    ) { transport.sendCborCommand(it) }
                    proto.authenticate(token, clientDataHash) to proto.version
                } else null to null

                Log.d(TAG, "CTAPHID init complete, sending GetAssertion (rpId=$rpId, uv=${pinUvAuthParam != null})")

                _touchPrompt.value = FidoTouchPrompt.TouchKey(FidoTouchPrompt.TouchKey.Transport.USB)
                val command = Ctap2Cbor.encodeGetAssertionCommand(
                    rpId = rpId,
                    clientDataHash = clientDataHash,
                    credentialId = credentialId,
                    pinUvAuthParam = pinUvAuthParam,
                    pinUvAuthProtocol = pinProtocol,
                )
                val response = transport.sendCborCommand(command) {
                    _touchPrompt.value = FidoTouchPrompt.TouchKey(FidoTouchPrompt.TouchKey.Transport.USB)
                }

                Log.d(TAG, "CTAP response: ${response.size} bytes, status=0x${
                    if (response.isNotEmpty()) "%02x".format(response[0]) else "empty"
                }")
                return parseCtap2AssertionResponse(response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB FIDO assertion failed: ${e.javaClass.simpleName}: ${e.message}")
            lastAssertionError = e.message
            throw e
        }
    }

    private suspend fun performNfcAssertion(
        tag: Tag,
        rpId: String,
        clientDataHash: ByteArray,
        credentialId: ByteArray,
        requireUv: Boolean,
    ): FidoAssertionResult {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")

        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()

            val (pinUvAuthParam, pinProtocol) = if (requireUv) {
                val (token, proto) = runUvPinProtocol(
                    rpId, Ctap2Cbor.PERMISSION_GET_ASSERTION,
                ) { transport.sendCborCommand(it) }
                proto.authenticate(token, clientDataHash) to proto.version
            } else null to null

            _touchPrompt.value = FidoTouchPrompt.TouchKey(FidoTouchPrompt.TouchKey.Transport.NFC)
            val command = Ctap2Cbor.encodeGetAssertionCommand(
                rpId = rpId,
                clientDataHash = clientDataHash,
                credentialId = credentialId,
                pinUvAuthParam = pinUvAuthParam,
                pinUvAuthProtocol = pinProtocol,
            )
            val response = transport.sendCborCommand(command)
            return parseCtap2AssertionResponse(response)
        }
    }

    // ---------- credentialManagement enumeration ----------

    private suspend fun performUsbEnumerate(
        device: UsbDevice,
        rpIdPrefix: String,
    ): List<Pair<String, SkKeyData>> {
        // Open the USB transport and run the shared enumerate. USB permission
        // handling is duplicated from performUsbAssertion — pulling it out
        // into a generic openUsbCtapTransport helper is a follow-up.
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        ensureUsbPermission(usbManager, device)

        val hidInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: throw IOException("No HID interface on FIDO device")
        var endpointIn: android.hardware.usb.UsbEndpoint? = null
        var endpointOut: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until hidInterface.endpointCount) {
            val ep = hidInterface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT) endpointOut = ep
        }
        requireNotNull(endpointIn) { "No IN endpoint on HID interface" }
        requireNotNull(endpointOut) { "No OUT endpoint on HID interface" }

        val connection = usbManager.openDevice(device)
            ?: throw IOException("Failed to open USB device")
        connection.claimInterface(hidInterface, true)

        try {
            CtapHidTransport(connection, endpointIn, endpointOut).use { transport ->
                Log.d(TAG, "CTAPHID init (enumerate)...")
                transport.init()
                return runCredentialManagementEnumerate(
                    rpIdPrefix = rpIdPrefix,
                    touchTransport = FidoTouchPrompt.TouchKey.Transport.USB,
                ) { transport.sendCborCommand(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB enumerate failed: ${e.javaClass.simpleName}: ${e.message}")
            lastAssertionError = e.message
            throw e
        }
    }

    private suspend fun performNfcEnumerate(
        tag: Tag,
        rpIdPrefix: String,
    ): List<Pair<String, SkKeyData>> {
        val isoDep = IsoDep.get(tag) ?: throw IOException("Tag does not support ISO-DEP")
        CtapNfcTransport(isoDep).use { transport ->
            transport.connect()
            transport.select()
            return runCredentialManagementEnumerate(
                rpIdPrefix = rpIdPrefix,
                touchTransport = FidoTouchPrompt.TouchKey.Transport.NFC,
            ) { transport.sendCborCommand(it) }
        }
    }

    /**
     * Drive the CTAP2.1 `authenticatorCredentialManagement` enumeration loop:
     * PIN/UV exchange (cm-permission token) → enumerateRPs Begin/GetNext for
     * each registered RP → for each matching RP, enumerateCredentials
     * Begin/GetNext.
     *
     * The per-subcommand `pinUvAuthParam` is derived freshly from the token
     * each call — `LEFT(16, HMAC-SHA-256(token, subCommand_byte ||
     * subCommandParams_cbor))`. Subcommands without params (RPs Begin,
     * RPs GetNext, Creds GetNext) hash just the subCommand byte; the only
     * subcommand that takes params here is CredsBegin (rpIdHash map).
     *
     * Transport-agnostic — caller passes the `send` lambda wired to either
     * CTAPHID or ISO-DEP-NFC. Returns (rpId, [SkKeyData]) pairs ready for
     * the SSH key store.
     */
    private suspend fun runCredentialManagementEnumerate(
        rpIdPrefix: String,
        touchTransport: FidoTouchPrompt.TouchKey.Transport,
        send: (ByteArray) -> ByteArray,
    ): List<Pair<String, SkKeyData>> {
        // 1. PIN/UV exchange — cm token has no rpId scoping.
        val (token, protocol) = runUvPinProtocol(
            rpId = null,
            permission = Ctap2Cbor.PERMISSION_CREDENTIAL_MANAGEMENT,
            send = send,
        )

        // 2. enumerateRPs: Begin (subCmd 2) returns first RP + totalRPs,
        //    then GetNext (subCmd 3) for the rest. No subCommandParams.
        _touchPrompt.value = FidoTouchPrompt.TouchKey(touchTransport)
        val firstRp = sendCmCommand(
            send, protocol, token,
            subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_BEGIN,
            params = null,
            includeAuth = true,
            describe = "enumerateRPsBegin",
        )?.let { Ctap2Cbor.decodeEnumerateRPsResponse(it) }
            ?: return emptyList() // STATUS_NO_CREDENTIALS — no resident creds at all
        val rpList = mutableListOf(firstRp)
        val total = firstRp.totalRPs ?: 1
        for (i in 1 until total) {
            val resp = sendCmCommand(
                send, protocol, token,
                subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_RPS_GET_NEXT,
                params = null,
                includeAuth = false,
                describe = "enumerateRPsGetNext[$i]",
            ) ?: break
            rpList += Ctap2Cbor.decodeEnumerateRPsResponse(resp)
        }
        Log.d(TAG, "Found ${rpList.size} RPs: ${rpList.map { it.id }}")

        // 3. For each RP whose id matches the prefix, enumerate its credentials.
        val out = mutableListOf<Pair<String, SkKeyData>>()
        for (rp in rpList) {
            if (rpIdPrefix.isNotEmpty() && !rp.id.startsWith(rpIdPrefix)) continue
            val paramsBytes = Ctap2Cbor.encodeEnumerateCredentialsParams(rp.rpIdHash)
            val firstCred = sendCmCommand(
                send, protocol, token,
                subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_CREDS_BEGIN,
                params = paramsBytes,
                includeAuth = true,
                describe = "enumerateCredsBegin(${rp.id})",
            )?.let { Ctap2Cbor.decodeEnumerateCredentialsResponse(it) } ?: continue
            val creds = mutableListOf(firstCred)
            val totalCreds = firstCred.totalCredentials ?: 1
            for (i in 1 until totalCreds) {
                val resp = sendCmCommand(
                    send, protocol, token,
                    subCommand = Ctap2Cbor.CM_SUB_ENUMERATE_CREDS_GET_NEXT,
                    params = null,
                    includeAuth = false,
                    describe = "enumerateCredsGetNext[$i](${rp.id})",
                ) ?: break
                creds += Ctap2Cbor.decodeEnumerateCredentialsResponse(resp)
            }
            Log.d(TAG, "RP ${rp.id}: ${creds.size} credentials")
            for (cred in creds) {
                // We can't tell from CTAP whether the key was registered with
                // verify-required, so default flags to 0x01 (user-presence).
                // If the user's key actually requires UV, the GetAssertion
                // path will still prompt for PIN when signing — the flags
                // bit is advisory metadata for the SSH stub, not a gate.
                val sk = SkKeyParser.buildFromCtapCredential(cred, rp.id, flags = 0x01)
                out += rp.id to sk
            }
        }
        Log.d(TAG, "Enumerate complete: ${out.size} SSH-SK credential(s) returned")
        return out
    }

    /**
     * Send one credentialManagement subcommand, derive the per-subcommand
     * pinUvAuthParam when [includeAuth] is true, and return the response
     * payload (without the status byte) or null on STATUS_NO_CREDENTIALS.
     */
    private fun sendCmCommand(
        send: (ByteArray) -> ByteArray,
        protocol: Ctap2PinProtocol,
        token: ByteArray,
        subCommand: Int,
        params: ByteArray?,
        includeAuth: Boolean,
        describe: String,
    ): ByteArray? {
        val authParam = if (includeAuth) {
            // pinUvAuthParam = LEFT(16, HMAC(token, subCommand_byte || params))
            val msg = byteArrayOf(subCommand.toByte()) + (params ?: ByteArray(0))
            protocol.authenticate(token, msg)
        } else null
        val cmd = Ctap2Cbor.encodeCredentialManagementCommand(
            subCommand = subCommand,
            subCommandParams = params,
            pinUvAuthProtocol = if (authParam != null) protocol.version else null,
            pinUvAuthParam = authParam,
        )
        val resp = send(cmd)
        if (resp.isEmpty()) throw IOException("$describe: empty CTAP response")
        val status = resp[0]
        if (status == Ctap2Cbor.STATUS_NO_CREDENTIALS) {
            Log.d(TAG, "$describe: STATUS_NO_CREDENTIALS")
            return null
        }
        if (status != Ctap2Cbor.STATUS_OK) {
            throw IOException("$describe: CTAP2 error 0x${"%02x".format(status)}")
        }
        return resp.copyOfRange(1, resp.size)
    }

    /**
     * Run the CTAP2 clientPIN protocol against an authenticator over [send]
     * (one round-trip per CBOR command, status byte preserved). Returns
     * `(pinUvAuthToken, protocol)` — the *raw* token, not a derived
     * authParam. Callers compute their own authParam(s) per command via
     * `protocol.authenticate(token, message)`:
     *   - GetAssertion uses `message = clientDataHash`.
     *   - CredentialManagement uses `message = subCommand_byte ||
     *     subCommandParams_cbor`, recomputed per subcommand.
     *
     * [permission] is one of the `Ctap2Cbor.PERMISSION_*` flags. The
     * token is scoped by the authenticator to that permission, so e.g. a
     * cm-permission token will be rejected if the caller then tries to
     * use it for a GetAssertion authParam (and vice versa).
     *
     * Throws [IOException] if the key has no PIN configured, the user
     * cancels, or PIN entry exhausts the authenticator's retry counter.
     */
    private suspend fun runUvPinProtocol(
        rpId: String?,
        permission: Int,
        send: (ByteArray) -> ByteArray,
    ): Pair<ByteArray, Ctap2PinProtocol> {
        // 1. authenticatorGetInfo to learn supported protocols and PIN state
        val infoResp = send(Ctap2Cbor.encodeGetInfoCommand())
        ensureOk(infoResp, "GetInfo")
        val info = Ctap2Cbor.decodeGetInfoResponse(infoResp.copyOfRange(1, infoResp.size))
        Log.d(TAG, "GetInfo: pinProtocols=${info.pinUvAuthProtocols}, " +
            "clientPinSet=${info.clientPinSet}, uvBuiltIn=${info.uvBuiltIn}, " +
            "pinUvAuthToken=${info.pinUvAuthTokenSupported}")

        if (!info.clientPinSet) {
            throw IOException(
                "This SK key requires verification, but the security key has no " +
                "PIN configured. Set a PIN with `ykman fido access change-pin` " +
                "(YubiKey) or your manufacturer's tool, then try again."
            )
        }

        val protocol = Ctap2PinProtocol.pick(info.pinUvAuthProtocols)
            ?: throw IOException("Authenticator does not support PIN protocol v1 or v2")
        // CTAP 2.1 keys advertise `pinUvAuthToken: true` and accept subCommand
        // 0x09 (with permissions). CTAP 2.0 / 2.1-PRE keys (e.g. older YubiKey 5
        // firmware) only support legacy 0x05; calling 0x09 returns INVALID_COMMAND.
        val useWithPermissions = info.pinUvAuthTokenSupported
        Log.d(TAG, "Using PIN/UV auth protocol v${protocol.version}, " +
            "subCmd=${if (useWithPermissions) "0x09 (with permissions)" else "0x05 (legacy)"}")

        // 2. clientPIN getKeyAgreement → authenticator's COSE_Key
        val kaResp = send(Ctap2Cbor.encodeClientPinGetKeyAgreement(protocol.version))
        ensureOk(kaResp, "clientPIN getKeyAgreement")
        val cose = Ctap2Cbor.decodeClientPinKeyAgreementResponse(kaResp.copyOfRange(1, kaResp.size))
        val authenticatorPub = protocol.coseKeyToEcPublic(cose.x, cose.y)

        // 3. ECDH on platform side → shared secret
        val ephemeral = protocol.generateEphemeralKeyPair()
        val z = protocol.ecdh(ephemeral.private as ECPrivateKey, authenticatorPub)
        val sharedSecret = protocol.deriveSharedSecret(z)
        val (ephX, ephY) = protocol.ecPublicToCoseCoords(ephemeral.public as ECPublicKey)
        val platformKa = Ctap2Cbor.CoseEcdhPubKey(ephX, ephY)

        // 4. Loop: prompt PIN → getPinUvAuthToken. On wrong PIN, retry until
        //    the user cancels or the authenticator returns a hard failure.
        var retriesNote: Int? = null
        while (true) {
            val pin = promptPin(retriesNote)
                ?: throw IOException("PIN entry cancelled")
            if (pin.length < 4) throw IOException("PIN must be at least 4 characters")

            val pinHash = MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray(Charsets.UTF_8))
                .copyOfRange(0, 16)
            val pinHashEnc = protocol.encrypt(sharedSecret, pinHash)

            val tokReq = if (useWithPermissions) {
                Ctap2Cbor.encodeClientPinGetTokenWithPermissions(
                    protocol = protocol.version,
                    platformKeyAgreement = platformKa,
                    pinHashEnc = pinHashEnc,
                    permissions = permission,
                    rpId = rpId,
                )
            } else {
                Ctap2Cbor.encodeClientPinGetTokenLegacy(
                    protocol = protocol.version,
                    platformKeyAgreement = platformKa,
                    pinHashEnc = pinHashEnc,
                )
            }
            val tokResp = send(tokReq)
            when (val status = tokResp[0]) {
                Ctap2Cbor.STATUS_OK -> {
                    val encToken = Ctap2Cbor.decodeClientPinTokenResponse(
                        tokResp.copyOfRange(1, tokResp.size)
                    )
                    val token = protocol.decrypt(sharedSecret, encToken)
                    Log.d(TAG, "PIN verified; token=${token.size}b, permission=0x${"%02x".format(permission)}")
                    return token to protocol
                }
                Ctap2Cbor.STATUS_PIN_INVALID -> {
                    retriesNote = (retriesNote ?: 8) - 1
                    Log.w(TAG, "Wrong PIN; ~$retriesNote attempts remain (estimated)")
                    if (retriesNote <= 0) {
                        throw IOException("Too many wrong PIN attempts.")
                    }
                    // Loop and prompt again.
                }
                Ctap2Cbor.STATUS_PIN_BLOCKED -> throw IOException(
                    "Security key PIN is blocked. Reset the FIDO2 application " +
                        "with the manufacturer's tool (e.g. `ykman fido reset`) " +
                        "and re-enroll."
                )
                Ctap2Cbor.STATUS_PIN_AUTH_BLOCKED -> throw IOException(
                    "PIN auth temporarily blocked. Unplug and replug the key, " +
                        "then try again."
                )
                Ctap2Cbor.STATUS_PIN_NOT_SET -> throw IOException(
                    "Security key has no PIN set; configure one and try again."
                )
                else -> throw IOException(
                    "PIN exchange failed: CTAP2 error 0x${"%02x".format(status)}"
                )
            }
        }
        // unreachable — the while(true) only exits via return or throw
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * Show the PIN entry dialog and await the user's response. Returns the
     * entered PIN, or null when the user cancels.
     */
    private suspend fun promptPin(retriesRemaining: Int?): String? {
        val deferred = CompletableDeferred<String?>()
        _touchPrompt.value = FidoTouchPrompt.EnterPin(
            submit = { pin -> if (!deferred.isCompleted) deferred.complete(pin) },
            retriesRemaining = retriesRemaining,
        )
        return try {
            deferred.await()
        } finally {
            // Caller flips _touchPrompt to TouchKey or null next; clearing here
            // would briefly hide the dialog. Leaving the EnterPin state in place
            // is fine since the next line of the caller reassigns it.
        }
    }

    /** Throw a descriptive IOException if [response] does not lead with STATUS_OK. */
    private fun ensureOk(response: ByteArray, context: String) {
        if (response.isEmpty()) throw IOException("$context: empty CTAP2 response")
        val status = response[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            throw IOException("$context: CTAP2 error 0x${"%02x".format(status)}")
        }
    }

    private fun parseCtap2AssertionResponse(response: ByteArray): FidoAssertionResult {
        require(response.isNotEmpty()) { "Empty CTAP2 response" }

        val status = response[0]
        if (status != Ctap2Cbor.STATUS_OK) {
            val desc = when (status) {
                Ctap2Cbor.STATUS_NO_CREDENTIALS ->
                    "No matching credential on this key (or the credential requires " +
                        "PIN verification — re-check that the PIN was accepted)"
                Ctap2Cbor.STATUS_ACTION_TIMEOUT -> "User did not touch the key in time"
                else -> "CTAP2 error 0x${"%02x".format(status)}"
            }
            throw IOException("FIDO2 assertion failed: $desc")
        }

        val parsed = Ctap2Cbor.decodeGetAssertionResponse(response.copyOfRange(1, response.size))

        val authData = parsed.authData
        require(authData.size >= 37) { "authenticatorData too short: ${authData.size}" }
        val flags = authData[32]
        val counter = ((authData[33].toInt() and 0xFF) shl 24) or
            ((authData[34].toInt() and 0xFF) shl 16) or
            ((authData[35].toInt() and 0xFF) shl 8) or
            (authData[36].toInt() and 0xFF)

        return FidoAssertionResult(
            signature = parsed.signature,
            flags = flags,
            counter = counter,
        )
    }

    /** Check if a USB device exposes a HID interface (FIDO keys always do). */
    private fun isFidoHidDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
                return true
            }
        }
        return false
    }
}
