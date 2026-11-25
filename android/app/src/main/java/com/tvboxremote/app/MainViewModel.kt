package com.tvboxremote.app

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvboxremote.app.atv.AtvPairing
import com.tvboxremote.app.atv.AtvRemoteSession
import com.tvboxremote.app.atv.CertificateMismatchException
import com.tvboxremote.app.atv.ClientIdentity
import com.tvboxremote.app.atv.PairingException
import com.tvboxremote.app.atv.Remote
import com.tvboxremote.app.atv.VolumeState
import com.tvboxremote.app.bluetooth.BluetoothHidRemote
import com.tvboxremote.app.bluetooth.BluetoothScanner
import com.tvboxremote.app.bluetooth.HidReports
import com.tvboxremote.app.controller.ControllerLayout
import com.tvboxremote.app.controller.Gamepad
import com.tvboxremote.app.controller.GamepadButton
import com.tvboxremote.app.controller.Stick
import com.tvboxremote.app.ui.Direction
import com.tvboxremote.app.bluetooth.HidState
import com.tvboxremote.app.data.AppStore
import com.tvboxremote.app.data.Preferences
import com.tvboxremote.app.discovery.BoxDiscovery
import com.tvboxremote.app.discovery.TvBox
import com.tvboxremote.app.motion.PixelAccumulator
import com.tvboxremote.app.receiver.ReceiverClient
import com.tvboxremote.app.receiver.ReceiverState
import com.tvboxremote.app.remote.HidAction
import com.tvboxremote.app.remote.RemoteKey
import com.tvboxremote.app.remote.TextChange
import com.tvboxremote.app.widget.LiveLinks
import com.tvboxremote.app.widget.RemoteWidget
import com.tvboxremote.app.widget.SavedDevice
import com.tvboxremote.app.widget.WidgetSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.abs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A marker for "no error" inside a flow of errors. */
private val NO_ERROR = Throwable("none")

/** Automatic reconnects after a lost Wi-Fi link, before the app shows "Connection lost". */
private const val MAX_RECONNECTS = 3
/** The wait before reconnect n is n times this. */
private const val RECONNECT_DELAY_MS = 1_000L
/** A link that lasted this long counts as working: a drop after it starts a new series of reconnects. */
private const val STABLE_LINK_MS = 30_000L

enum class Transport { WIFI, BLUETOOTH }

sealed interface Link {
    data object None : Link
    data class Connecting(val name: String) : Link
    data class Connected(val name: String, val transport: Transport) : Link
}

/** A dialog that waits for a code from the user. */
sealed interface CodeRequest {
    data class Atv(val boxName: String, val wrongCode: Boolean) : CodeRequest
    data class Receiver(val boxName: String, val wrongCode: Boolean) : CodeRequest
}

data class UiState(
    val link: Link = Link.None,
    val codeRequest: CodeRequest? = null,
    val volume: VolumeState? = null,
    val receiver: ReceiverState = ReceiverState.Disconnected,
    val hid: HidState = HidState.Idle,
    val connectedBox: TvBox? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = AppStore(app)
    val discovery = BoxDiscovery(app)
    val hid = BluetoothHidRemote.get(app)
    val scanner = BluetoothScanner(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** Short messages for a snackbar. */
    val messages = _messages.receiveAsFlow()

    private val _openKeyboard = Channel<Unit>(Channel.CONFLATED)
    /** Emits when the box opens its on-screen keyboard. */
    val openKeyboard = _openKeyboard.receiveAsFlow()

    val preferences: StateFlow<Preferences> get() = store.preferences

    private val _signals = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emits each time the app sends a command to the box. The signal line shows it. */
    val signals: SharedFlow<Unit> = _signals.asSharedFlow()

    private fun signal() {
        _signals.tryEmit(Unit)
    }

    private var session: AtvRemoteSession? = null
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null
    private var receiver: ReceiverClient? = null
    private var receiverJob: Job? = null
    private var pendingCode: CompletableDeferred<String?>? = null
    private var lastSentText = ""
    private val hidAccumulator = PixelAccumulator()
    private var hidScrollAccumulator = 0f

    /** True after the user asked for a Bluetooth connection, so the next HID connection may replace Wi-Fi. */
    @Volatile private var bluetoothRequested = false

    private var scrollX = 0f
    private var scrollY = 0f
    private var lastScrollSent = 0L

    private val phoneName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    init {
        LiveLinks.appActive = true
        discovery.setManualBoxes(loadManualBoxes())
        viewModelScope.launch {
            var previous: HidState = hid.state.value
            hid.state.collect { state ->
                _ui.update { it.copy(hid = state) }
                when (state) {
                    is HidState.Connected -> {
                        val wifiInUse = (_ui.value.link as? Link.Connected)?.transport == Transport.WIFI ||
                            _ui.value.connectedBox != null
                        if (wifiInUse && LiveLinks.widgetBluetooth == state.device.address) {
                            // The home screen widget uses this link for one press. Keep the app on Wi-Fi.
                            previous = state
                            return@collect
                        }
                        if (wifiInUse && !bluetoothRequested) {
                            // The user did not ask for Bluetooth now. Keep the Wi-Fi session.
                            hid.disconnect()
                            previous = state
                            return@collect
                        }
                        bluetoothRequested = false
                        closeWifi()
                        val name = deviceName(state.device)
                        _ui.update { it.copy(link = Link.Connected(name, Transport.BLUETOOTH)) }
                        store.lastDevice = "bt:${state.device.address}"
                        saveForWidget(SavedDevice.bluetooth(state.device.address, name))
                    }
                    is HidState.Connecting -> if (_ui.value.connectedBox == null) {
                        _ui.update { it.copy(link = Link.Connecting(deviceName(state.device))) }
                    }
                    else -> {
                        // Only a lost or failed Bluetooth link resets the status, not the registration steps.
                        val wasLinked = previous is HidState.Connected || previous is HidState.Connecting
                        if (wasLinked && _ui.value.connectedBox == null) {
                            _ui.update { it.copy(link = Link.None) }
                            if (previous is HidState.Connected) notify(str(R.string.msg_bt_lost))
                        }
                    }
                }
                previous = state
            }
        }
        viewModelScope.launch {
            scanner.bonded.collect { device ->
                if (device != null) {
                    scanner.consumeBonded()
                    hid.connect(device)
                }
            }
        }
        reconnectLast()
    }

    // ---------- Devices ----------

    private fun loadManualBoxes(): List<TvBox> = store.manualBoxes.map {
        TvBox(host = it.substringBefore('|'), port = BoxDiscovery.DEFAULT_ATV_PORT,
            name = it.substringAfter('|', it.substringBefore('|')), manual = true)
    }

    fun addManualBox(host: String, name: String) {
        val clean = host.trim()
        if (clean.isEmpty()) return
        store.manualBoxes = store.manualBoxes.filterNot { it.substringBefore('|') == clean }.toSet() +
            "$clean|${name.ifBlank { clean }}"
        discovery.setManualBoxes(loadManualBoxes())
    }

    fun forget(box: TvBox) {
        if (_ui.value.connectedBox?.host == box.host) disconnect()
        store.forget(box.host)
        RemoteWidget.updateAll(getApplication())
        discovery.setManualBoxes(loadManualBoxes())
        notify(str(R.string.msg_box_removed, box.name))
    }

    /** Reconnects to the last box, if the app is not connected. */
    fun reconnectLast() {
        if (_ui.value.link !is Link.None) return
        val last = store.lastDevice ?: return
        when {
            last.startsWith("wifi:") -> {
                val parts = last.removePrefix("wifi:").split(":", limit = 3)
                if (parts.size == 3) {
                    val host = parts[0]
                    if (store.fingerprint(host) == null) return
                    connectWifi(TvBox(host, parts[1].toIntOrNull() ?: BoxDiscovery.DEFAULT_ATV_PORT, parts[2]), quiet = true)
                }
            }
            last.startsWith("bt:") -> {
                val address = last.removePrefix("bt:")
                if (!hasBluetoothConnectPermission()) return
                hid.bondedDevices().firstOrNull { it.address == address }?.let {
                    bluetoothRequested = true
                    hid.connect(it)
                }
            }
        }
    }

    // ---------- Wi-Fi: Android TV Remote ----------

    /**
     * Connects to [box]. [attempt] counts the automatic reconnects after a lost link: 0 for a connect that the user
     * or the app start asked for.
     */
    fun connectWifi(box: TvBox, quiet: Boolean = false, attempt: Int = 0) {
        disconnect()
        sessionJob = viewModelScope.launch {
            _ui.update { it.copy(link = Link.Connecting(box.name), connectedBox = box) }
            try {
                val identity = ClientIdentity.get(getApplication())
                var fingerprint = store.fingerprint(box.host)
                if (fingerprint == null) {
                    fingerprint = pair(identity, box) ?: run {
                        _ui.update { it.copy(link = Link.None, connectedBox = null) }
                        return@launch
                    }
                }
                // If the box no longer knows this phone, it rejects the client certificate:
                // during the handshake (TLS 1.2) or right after it (TLS 1.3).
                var s: AtvRemoteSession? = try {
                    openSession(identity, box, fingerprint)
                } catch (e: SSLHandshakeException) {
                    null
                }
                if (s != null && startupFailure(s) is SSLException) {
                    s.close()
                    s = null
                }
                if (s == null) {
                    if (quiet) throw IOException("The box refused the phone")
                    // The old pin stays until a new pairing succeeds.
                    fingerprint = pair(identity, box) ?: run {
                        _ui.update { it.copy(link = Link.None, connectedBox = null) }
                        return@launch
                    }
                    s = openSession(identity, box, fingerprint)
                }
                session = s
                LiveLinks.wifi = s
                store.lastDevice = "wifi:${box.host}:${box.port}:${box.name}"
                saveForWidget(SavedDevice.wifi(box.host, box.port, box.name))
                _ui.update { it.copy(link = Link.Connected(box.name, Transport.WIFI)) }
                if (store.receiverToken(box.host) != null) connectReceiver()
                launch { s.volume.collect { v -> _ui.update { it.copy(volume = v) } } }
                launch { s.imeShown.collect { _openKeyboard.trySend(Unit) } }
                val connectedAt = SystemClock.elapsedRealtime()
                s.closed.collectLatest { reason ->
                    if (reason != null && session === s) {
                        session = null
                        if (LiveLinks.wifi === s) LiveLinks.wifi = null
                        closeReceiver()
                        // A link that worked for a while starts a new series of reconnects.
                        val next = if (SystemClock.elapsedRealtime() - connectedAt > STABLE_LINK_MS) 1 else attempt + 1
                        if (next <= MAX_RECONNECTS) {
                            // Reconnect without a message. Most drops are short Wi-Fi gaps.
                            _ui.update { it.copy(link = Link.Connecting(box.name), volume = null) }
                            reconnectJob = viewModelScope.launch {
                                kotlinx.coroutines.delay(RECONNECT_DELAY_MS * next)
                                if (session == null && _ui.value.connectedBox?.host == box.host) {
                                    connectWifi(box, quiet = true, attempt = next)
                                }
                            }
                        } else {
                            _ui.update { it.copy(link = Link.None, volume = null) }
                            notify(str(R.string.msg_connection_lost_reason, box.name, reason.message ?: reason.javaClass.simpleName))
                        }
                    }
                }
            } catch (e: CertificateMismatchException) {
                _ui.update { it.copy(link = Link.None, connectedBox = null) }
                notify(str(R.string.msg_new_certificate, box.name))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PairingException) {
                _ui.update { it.copy(link = Link.None, connectedBox = null, codeRequest = null) }
                notify(pairingMessage(e))
            } catch (e: Exception) {
                _ui.update { it.copy(link = Link.None, connectedBox = null, codeRequest = null) }
                val detail = e.message ?: e.javaClass.simpleName
                when {
                    attempt > 0 -> notify(str(R.string.msg_connection_lost_reason, box.name, detail))
                    !quiet -> notify(str(R.string.msg_cannot_connect, box.name, detail))
                }
            }
        }
    }

    private suspend fun openSession(identity: ClientIdentity, box: TvBox, fingerprint: String): AtvRemoteSession {
        val s = AtvRemoteSession(
            identity = identity, host = box.host, port = box.port, expectedFingerprint = fingerprint,
            model = Build.MODEL, vendor = Build.MANUFACTURER, appVersion = BuildConfig.VERSION_NAME,
        )
        try {
            s.start()
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return s
    }

    /** Waits until the box activates the session. Returns the error if the session ends first. */
    private suspend fun startupFailure(s: AtvRemoteSession): Throwable? =
        withTimeoutOrNull(5000) {
            combine(s.ready, s.closed) { ready, closed -> closed ?: if (ready) NO_ERROR else null }
                .filterNotNull()
                .first()
                .takeIf { it !== NO_ERROR }
        }

    private suspend fun pair(identity: ClientIdentity, box: TvBox): String? {
        val fingerprint = AtvPairing(identity, box.host, phoneName).run { wrongCode ->
            askCode(CodeRequest.Atv(box.name, wrongCode))
        } ?: return null
        store.setFingerprint(box.host, fingerprint)
        return fingerprint
    }

    private suspend fun askCode(request: CodeRequest): String? {
        val deferred = CompletableDeferred<String?>()
        pendingCode = deferred
        _ui.update { it.copy(codeRequest = request) }
        return try {
            deferred.await()
        } finally {
            if (pendingCode === deferred) pendingCode = null
            _ui.update { it.copy(codeRequest = null) }
        }
    }

    /** The user typed a code in the pairing dialog, or cancelled it (null). */
    fun submitCode(code: String?) {
        when (_ui.value.codeRequest) {
            is CodeRequest.Atv -> pendingCode?.complete(code)
            is CodeRequest.Receiver -> {
                _ui.update { it.copy(codeRequest = null) }
                if (code == null) closeReceiver() else receiver?.pair(code)
            }
            null -> Unit
        }
    }

    // ---------- Wi-Fi: box receiver (mouse) ----------

    /** Connects to the receiver on the connected box. Call when the mouse screen opens. */
    fun connectReceiver() {
        val box = _ui.value.connectedBox ?: return
        if ((_ui.value.link as? Link.Connected)?.transport != Transport.WIFI) return
        val current = receiver?.state?.value
        if (current is ReceiverState.Ready || current is ReceiverState.Connecting || current is ReceiverState.PairRequired) return
        closeReceiver()
        val port = discovery.receiverPortFor(box.host) ?: box.receiverPort ?: BoxDiscovery.DEFAULT_RECEIVER_PORT
        val client = ReceiverClient(box.host, port, phoneName, store.receiverToken(box.host)) { token ->
            store.setReceiverToken(box.host, token)
        }
        receiver = client
        receiverJob = viewModelScope.launch {
            client.state.collect { state ->
                _ui.update { it.copy(receiver = state) }
                if (state is ReceiverState.PairRequired) {
                    _ui.update { it.copy(codeRequest = CodeRequest.Receiver(box.name, state.wrongCode)) }
                }
            }
        }
        client.start()
    }

    private fun closeReceiver() {
        receiverJob?.cancel()
        receiverJob = null
        receiver?.close()
        receiver = null
        _ui.update { it.copy(receiver = ReceiverState.Disconnected) }
    }

    // ---------- Bluetooth ----------

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull() ?: device.address

    @SuppressLint("MissingPermission") // The Devices screen lists Bluetooth devices only after the user grants the permissions.
    fun connectBluetooth(device: BluetoothDevice) {
        bluetoothRequested = true
        closeWifi()
        _ui.update { it.copy(link = Link.Connecting(deviceName(device)), connectedBox = null) }
        if (device.bondState == BluetoothDevice.BOND_BONDED) hid.connect(device) else scanner.bond(device)
    }

    /** The user made the phone visible, so a box may connect over Bluetooth now. */
    fun expectBluetooth() {
        bluetoothRequested = true
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || getApplication<Application>()
            .checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------- Commands ----------

    val isConnected: Boolean get() = _ui.value.link is Link.Connected

    private val transport: Transport? get() = (_ui.value.link as? Link.Connected)?.transport

    /** Sends a key. Returns false if no box is connected. */
    fun press(key: RemoteKey): Boolean {
        when (transport) {
            Transport.WIFI -> {
                val s = session ?: return false
                // NVIDIA SHIELD ignores the Settings key code, so open the settings with an app link there.
                if (key == RemoteKey.SETTINGS && s.isNvidia) s.launchAppLink(Remote.SETTINGS_LINK) else s.sendKey(key.atvCode)
            }
            Transport.BLUETOOTH -> {
                val sent = when (val action = key.hid) {
                    is HidAction.Keyboard -> hid.pressKey(action.usage)
                    is HidAction.Consumer -> hid.pressConsumer(action.usage)
                    null -> { notify(str(R.string.msg_key_not_on_bt, str(key.label))); return true }
                }
                // No pulse on the signal line for a report that did not go out.
                if (!sent) return true
            }
            null -> return false
        }
        signal()
        return true
    }

    /** Sends a long press (Wi-Fi only). Over Bluetooth it is a short press. */
    fun longPress(key: RemoteKey) {
        if (transport == Transport.WIFI) {
            val s = session ?: return
            s.sendKey(key.atvCode, Remote.DIRECTION_START_LONG)
            s.sendKey(key.atvCode, Remote.DIRECTION_END_LONG)
            signal()
        } else {
            press(key)
        }
    }

    /** Called when the keyboard sheet opens, so that the text diff starts empty. */
    fun resetText() { lastSentText = "" }

    /**
     * Sends the change of the phone text field to the box. Both transports add text at the cursor, so the box gets
     * one Delete for each removed character and then only the new characters.
     */
    fun setText(text: String) {
        when (transport) {
            Transport.WIFI -> {
                val s = session ?: return
                val change = TextChange.of(lastSentText, text)
                repeat(change.deletes) { s.sendKey(RemoteKey.DELETE.atvCode) }
                s.sendText(change.insert)
            }
            Transport.BLUETOOTH -> {
                val change = TextChange.of(lastSentText, text)
                repeat(change.deletes) { hid.pressKey(com.tvboxremote.app.bluetooth.KeyUsage.BACKSPACE) }
                val skipped = hid.type(change.insert)
                if (skipped > 0) notify(str(R.string.msg_bt_us_only))
            }
            null -> return
        }
        signal()
        lastSentText = text
    }

    /** True if the mouse can work now: Bluetooth, or a ready receiver. */
    val mouseReady: Boolean
        get() = transport == Transport.BLUETOOTH || _ui.value.receiver is ReceiverState.Ready

    fun mouseMove(dx: Float, dy: Float) {
        when (transport) {
            Transport.BLUETOOTH -> {
                val (ix, iy) = hidAccumulator.add(dx, dy)
                if (ix != 0 || iy != 0) hid.moveMouse(ix, iy)
            }
            Transport.WIFI -> receiver?.move(dx, dy)
            null -> Unit
        }
    }

    fun mouseClick() {
        when (transport) {
            Transport.BLUETOOTH -> hid.click()
            Transport.WIFI -> receiver?.click()
            null -> Unit
        }
    }

    fun mouseLongPress() {
        when (transport) {
            Transport.BLUETOOTH -> viewModelScope.launch {
                hid.mouseButton(true)
                kotlinx.coroutines.delay(600)
                hid.mouseButton(false)
            }
            Transport.WIFI -> receiver?.longPress()
            null -> Unit
        }
    }

    /** Scrolls by a finger movement in pixels. Positive [dy]: the finger moved up. */
    fun mouseScroll(dx: Float, dy: Float) {
        when (transport) {
            Transport.BLUETOOTH -> {
                // One wheel step is about 40 pixels of finger movement. Wheel up shows content above.
                hidScrollAccumulator += dy / 40f
                val steps = hidScrollAccumulator.toInt()
                if (steps != 0) {
                    hidScrollAccumulator -= steps
                    hid.scroll(-steps)
                }
            }
            Transport.WIFI -> {
                // Each scroll becomes a swipe on the TV, and a new swipe cancels the one in progress.
                // So send few, long swipes: at least 60 px of finger movement, at most every 150 ms.
                scrollX += dx
                scrollY += dy
                val now = android.os.SystemClock.uptimeMillis()
                if ((abs(scrollX) >= 60f || abs(scrollY) >= 60f) && now - lastScrollSent >= 150) {
                    receiver?.scroll(scrollX * 2.5f, scrollY * 2.5f)
                    scrollX = 0f
                    scrollY = 0f
                    lastScrollSent = now
                }
            }
            null -> Unit
        }
    }

    fun showCursor(show: Boolean) {
        receiver?.showCursor(show)
    }

    // ---------- Game controller ----------

    /** Bluetooth: the full gamepad state, sent as one report on each change. */
    private var padButtons = 0
    private var padHat = Gamepad.HAT_NONE
    private val padAxes = IntArray(4) // X, Y (left stick), Z, Rz (right stick)

    /** Wi-Fi: the arrows that the D-pad and the left stick hold down now. */
    private var dpadArrows: Set<Direction> = emptySet()
    private var stickArrows: Set<Direction> = emptySet()
    private var heldArrows: Set<Direction> = emptySet()

    /** Wi-Fi: the right stick moves the pointer while it is out of the center. */
    @Volatile private var rightStick = floatArrayOf(0f, 0f)
    private var pointerJob: Job? = null

    val controllerLayout: ControllerLayout get() = ControllerLayout.decode(store.controllerLayout)

    fun saveControllerLayout(layout: ControllerLayout) {
        store.controllerLayout = layout.encode()
    }

    /** True if the right stick works now: always over Bluetooth, and over Wi-Fi with a ready receiver. */
    val rightStickReady: Boolean
        get() = transport == Transport.BLUETOOTH || _ui.value.receiver is ReceiverState.Ready

    fun gamepadButton(button: GamepadButton, down: Boolean) {
        when (transport) {
            Transport.WIFI -> {
                val s = session ?: return
                s.sendKey(button.atvCode, if (down) Remote.DIRECTION_START_LONG else Remote.DIRECTION_END_LONG)
                padButtons = if (down) padButtons or button.hidMask else padButtons and button.hidMask.inv()
            }
            Transport.BLUETOOTH -> {
                padButtons = if (down) padButtons or button.hidMask else padButtons and button.hidMask.inv()
                sendPad()
            }
            null -> return
        }
        if (down) signal()
    }

    /** A stick click (L3 or R3): a tap on a stick without movement. */
    fun gamepadStickClick(stick: Stick) {
        val button = if (stick == Stick.LEFT) GamepadButton.L3 else GamepadButton.R3
        when (transport) {
            Transport.WIFI -> session?.sendKey(button.atvCode) ?: return
            Transport.BLUETOOTH -> {
                padButtons = padButtons or button.hidMask
                sendPad()
                padButtons = padButtons and button.hidMask.inv()
                sendPad()
            }
            null -> return
        }
        signal()
    }

    fun gamepadDpad(arrows: Set<Direction>) {
        when (transport) {
            Transport.WIFI -> {
                dpadArrows = arrows
                holdArrows()
            }
            Transport.BLUETOOTH -> {
                val hat = Gamepad.hat(arrows)
                if (hat == padHat) return
                padHat = hat
                sendPad()
                if (hat != Gamepad.HAT_NONE) signal()
            }
            null -> Unit
        }
    }

    /** A stick position: [x] and [y] from -1 to 1, y down. */
    fun gamepadStick(stick: Stick, x: Float, y: Float) {
        when (transport) {
            Transport.WIFI -> when (stick) {
                Stick.LEFT -> {
                    stickArrows = Gamepad.arrows(x, y)
                    holdArrows()
                }
                Stick.RIGHT -> movePointerWithStick(x, y)
            }
            Transport.BLUETOOTH -> {
                val base = if (stick == Stick.LEFT) 0 else 2
                val ax = Gamepad.axis(x)
                val ay = Gamepad.axis(y)
                if (padAxes[base] == ax && padAxes[base + 1] == ay) return
                padAxes[base] = ax
                padAxes[base + 1] = ay
                sendPad()
            }
            null -> movePointerWithStick(0f, 0f)
        }
    }

    /** Releases every button, arrow and stick. Call when the controller screen closes. */
    fun gamepadReleaseAll() {
        dpadArrows = emptySet()
        stickArrows = emptySet()
        if (transport == Transport.WIFI) {
            holdArrows()
            GamepadButton.entries.forEach {
                if (padButtons and it.hidMask != 0) session?.sendKey(it.atvCode, Remote.DIRECTION_END_LONG)
            }
        }
        heldArrows = emptySet()
        movePointerWithStick(0f, 0f)
        val wasActive = padButtons != 0 || padHat != Gamepad.HAT_NONE || padAxes.any { it != 0 }
        padButtons = 0
        padHat = Gamepad.HAT_NONE
        padAxes.fill(0)
        if (wasActive && transport == Transport.BLUETOOTH) sendPad() else hid.resetGamepad()
    }

    private fun sendPad() {
        hid.sendGamepad(HidReports.gamepad(padButtons, padHat, padAxes[0], padAxes[1], padAxes[2], padAxes[3]))
    }

    /** Wi-Fi: presses the arrows that are newly held and releases the ones that are no longer held. */
    private fun holdArrows() {
        val wanted = dpadArrows + stickArrows
        val s = session
        val added = wanted - heldArrows
        (heldArrows - wanted).forEach { s?.sendKey(arrowCode(it), Remote.DIRECTION_END_LONG) }
        added.forEach { s?.sendKey(arrowCode(it), Remote.DIRECTION_START_LONG) }
        heldArrows = wanted
        if (added.isNotEmpty() && s != null) signal()
    }

    private fun arrowCode(direction: Direction): Int = when (direction) {
        Direction.UP -> RemoteKey.UP.atvCode
        Direction.DOWN -> RemoteKey.DOWN.atvCode
        Direction.LEFT -> RemoteKey.LEFT.atvCode
        Direction.RIGHT -> RemoteKey.RIGHT.atvCode
    }

    /** Wi-Fi: while the right stick is out of the center, moves the pointer through the receiver at about 60 Hz. */
    private fun movePointerWithStick(x: Float, y: Float) {
        rightStick = floatArrayOf(x, y)
        val active = abs(x) > 0.08f || abs(y) > 0.08f
        if (!active) {
            pointerJob?.cancel()
            pointerJob = null
            return
        }
        if (pointerJob?.isActive == true) return
        pointerJob = viewModelScope.launch {
            var last = android.os.SystemClock.uptimeMillis()
            while (true) {
                kotlinx.coroutines.delay(16)
                val now = android.os.SystemClock.uptimeMillis()
                val dt = (now - last) / 1000f
                last = now
                // Full travel moves the pointer about 1100 px per second, times the touchpad speed.
                val step = 1100f * preferences.value.touchpadSpeed * dt
                val v = rightStick
                if (transport != Transport.WIFI) break
                if (_ui.value.receiver is ReceiverState.Ready) receiver?.move(v[0] * step, v[1] * step)
            }
        }
    }

    // ---------- Lifecycle ----------

    /** Puts [device] first in the widget list, and redraws the widgets if the list changed. */
    private fun saveForWidget(device: SavedDevice) {
        val before = store.savedDevices
        store.rememberDevice(device)
        if (store.savedDevices != before) RemoteWidget.updateAll(getApplication())
    }

    private fun closeWifi() {
        // An automatic reconnect must not start after the user disconnected or chose another device.
        // A reconnect that calls connectWifi cancels its own job here, after its last suspension point.
        reconnectJob?.cancel()
        reconnectJob = null
        sessionJob?.cancel()
        sessionJob = null
        pendingCode?.complete(null)
        if (LiveLinks.wifi === session) LiveLinks.wifi = null
        session?.close()
        session = null
        closeReceiver()
        _ui.update { it.copy(volume = null, connectedBox = null) }
    }

    fun disconnect() {
        closeWifi()
        if (hid.connectedDevice != null) hid.disconnect()
        _ui.update { it.copy(link = Link.None, codeRequest = null) }
    }

    fun updatePreferences(p: Preferences) = store.updatePreferences(p)

    private fun notify(message: String) {
        _messages.trySend(message)
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().withAppLanguage().getString(id, *args)

    private fun pairingMessage(e: PairingException): String = when (e.reason) {
        PairingException.Reason.REFUSED_CODE -> str(R.string.pair_refused_code)
        PairingException.Reason.REFUSED_CONFIG -> str(R.string.pair_refused_config)
        PairingException.Reason.STATUS -> str(R.string.pair_failed_status, e.status)
        PairingException.Reason.UNEXPECTED -> str(R.string.pair_unexpected)
    }

    override fun onCleared() {
        LiveLinks.appActive = false
        closeWifi()
        discovery.stop()
        scanner.stop()
        // A widget press in the last seconds may still use the Bluetooth link. Then the widget closes it later.
        if (hasBluetoothConnectPermission() && !WidgetSender.adoptBluetooth()) hid.unregister()
    }
}
