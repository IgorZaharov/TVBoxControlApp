package com.tvboxremote.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

sealed interface HidState {
    data object Unavailable : HidState
    data object Idle : HidState
    data object Registering : HidState
    /** The HID app is registered. The box can connect, or the phone can connect to a bonded box. */
    data object Registered : HidState
    data class Connecting(val device: BluetoothDevice) : HidState
    data class Connected(val device: BluetoothDevice) : HidState
}

/**
 * Makes the phone act as a Bluetooth keyboard, mouse and remote (HID device profile).
 * The caller must hold BLUETOOTH_CONNECT on Android 12 and later.
 */
@SuppressLint("MissingPermission")
class BluetoothHidRemote private constructor(context: Context) {
    companion object {
        @Volatile private var instance: BluetoothHidRemote? = null

        /** One HID app per process: the app and the home screen widget share it. */
        fun get(context: Context): BluetoothHidRemote =
            instance ?: synchronized(this) { instance ?: BluetoothHidRemote(context).also { instance = it } }
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val executor = Executors.newSingleThreadExecutor()
    // The app screen (main thread), the widget (IO) and the Bluetooth callbacks (executor) all use this object.
    // The public methods that change the link are synchronized, and the fields are volatile.
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var appRegistered = false
    @Volatile private var pendingConnect: BluetoothDevice? = null
    /** A device to connect once the current device has disconnected. The profile serves one host at a time. */
    @Volatile private var connectAfterDisconnect: BluetoothDevice? = null
    @Volatile private var registeringSince = 0L

    private val _state = MutableStateFlow<HidState>(if (adapter == null) HidState.Unavailable else HidState.Idle)
    val state: StateFlow<HidState> = _state.asStateFlow()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    val connectedDevice: BluetoothDevice? get() = (_state.value as? HidState.Connected)?.device

    fun bondedDevices(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) = synchronized(this@BluetoothHidRemote) {
            appRegistered = registered
            if (!registered) {
                _state.value = HidState.Idle
                return
            }
            _state.value = HidState.Registered
            // Connect only to a box that the user selected. The system can report a
            // "plugged" device on its own, and that must not end a Wi-Fi session.
            val target = pendingConnect
            pendingConnect = null
            if (target != null) connectNow(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) = synchronized(this@BluetoothHidRemote) {
            _state.value = when (state) {
                BluetoothProfile.STATE_CONNECTED -> HidState.Connected(device)
                BluetoothProfile.STATE_CONNECTING -> HidState.Connecting(device)
                else -> if (hid != null) HidState.Registered else HidState.Idle
            }
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                resetGamepad()
                val next = connectAfterDisconnect
                connectAfterDisconnect = null
                if (next != null && next.address != device.address) connectNow(next)
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = HidReports.reportSize(id.toInt())
            if (size == 0) {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            } else {
                val report = if (id.toInt() == HidReports.ID_GAMEPAD) lastGamepad else ByteArray(size)
                hid?.replyReport(device, type, id, report)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    /** Registers the HID app. Call before the box tries to connect. */
    @Synchronized
    fun register() {
        val a = adapter ?: return
        if (appRegistered) return
        // A registration that got no answer in 10 s is stale: try again.
        val stale = System.currentTimeMillis() - registeringSince > 10_000
        if (_state.value == HidState.Registering && !stale) return
        registeringSince = System.currentTimeMillis()
        val existing = hid
        if (existing != null) {
            // The proxy is alive but the app was unregistered (for example by the system). Register again.
            _state.value = HidState.Registering
            if (!existing.registerApp(sdpSettings(), null, null, executor, callback)) _state.value = HidState.Idle
            return
        }
        _state.value = HidState.Registering
        val ok = a.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val device = proxy as BluetoothHidDevice
                hid = device
                if (!device.registerApp(sdpSettings(), null, null, executor, callback)) {
                    _state.value = HidState.Idle
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
                appRegistered = false
                _state.value = HidState.Idle
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) _state.value = HidState.Unavailable
    }

    private fun sdpSettings() = BluetoothHidDeviceAppSdpSettings(
        "TVBox Remote",
        appContext.getString(com.tvboxremote.app.R.string.hid_description),
        "TVBox Remote",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HidReports.DESCRIPTOR,
    )

    /** Connects to a bonded box. Registers the HID app first if necessary. */
    @Synchronized
    fun connect(device: BluetoothDevice) {
        if (hid == null || !appRegistered) {
            pendingConnect = device
            register()
            return
        }
        connectNow(device)
    }

    @Synchronized
    private fun connectNow(device: BluetoothDevice) {
        val h = hid ?: return
        val current = connectedDevice
        if (current != null && current.address != device.address) {
            // Disconnect the current host first. The connection to [device] follows in onConnectionStateChanged.
            connectAfterDisconnect = device
            h.disconnect(current)
            return
        }
        if (current != null) return
        val before = _state.value
        _state.value = HidState.Connecting(device)
        if (!h.connect(device)) _state.value = if (before is HidState.Connecting) HidState.Registered else before
    }

    @Synchronized
    fun disconnect() {
        val device = connectedDevice ?: return
        hid?.disconnect(device)
    }

    @Synchronized
    fun unregister() {
        val h = hid ?: return
        connectedDevice?.let { h.disconnect(it) }
        h.unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        hid = null
        appRegistered = false
        _state.value = HidState.Idle
    }

    private fun send(id: Int, data: ByteArray): Boolean {
        val device = connectedDevice ?: return false
        return hid?.sendReport(device, id, data) == true
    }

    fun pressKey(usage: Int, modifiers: Int = 0): Boolean {
        val down = send(HidReports.ID_KEYBOARD, HidReports.keyboard(modifiers, usage))
        send(HidReports.ID_KEYBOARD, HidReports.KEYBOARD_RELEASE)
        return down
    }

    fun pressConsumer(usage: Int): Boolean {
        val down = send(HidReports.ID_CONSUMER, HidReports.consumer(usage))
        send(HidReports.ID_CONSUMER, HidReports.CONSUMER_RELEASE)
        return down
    }

    /** Types text on a US keyboard layout. Returns the number of characters that have no key. */
    fun type(text: String): Int {
        var skipped = 0
        for (c in text) {
            val stroke = KeyUsage.forChar(c)
            if (stroke == null) { skipped++; continue }
            pressKey(stroke.usage, if (stroke.shift) HidReports.MOD_LEFT_SHIFT else 0)
        }
        return skipped
    }

    /** Moves the mouse. Large moves are split, because one report holds -127..127. */
    fun moveMouse(dx: Int, dy: Int, buttons: Int = 0) {
        var rx = dx
        var ry = dy
        do {
            val sx = HidReports.clamp8(rx)
            val sy = HidReports.clamp8(ry)
            send(HidReports.ID_MOUSE, HidReports.mouse(buttons, sx, sy, 0))
            rx -= sx
            ry -= sy
        } while (rx != 0 || ry != 0)
    }

    fun scroll(wheel: Int) {
        if (wheel != 0) send(HidReports.ID_MOUSE, HidReports.mouse(0, 0, 0, wheel))
    }

    fun click(button: Int = 1) {
        send(HidReports.ID_MOUSE, HidReports.mouse(button, 0, 0, 0))
        send(HidReports.ID_MOUSE, HidReports.mouse(0, 0, 0, 0))
    }

    @Volatile private var lastGamepad: ByteArray = HidReports.gamepad(0, 8, 0, 0, 0, 0)

    /** Makes the report that the box can ask for neutral: no button, no direction, sticks centered. */
    fun resetGamepad() {
        lastGamepad = HidReports.gamepad(0, 8, 0, 0, 0, 0)
    }

    /** Sends the full gamepad state. Returns false if no box is connected. */
    fun sendGamepad(report: ByteArray): Boolean {
        lastGamepad = report
        return send(HidReports.ID_GAMEPAD, report)
    }

    fun mouseButton(down: Boolean, button: Int = 1) {
        send(HidReports.ID_MOUSE, HidReports.mouse(if (down) button else 0, 0, 0, 0))
    }
}
