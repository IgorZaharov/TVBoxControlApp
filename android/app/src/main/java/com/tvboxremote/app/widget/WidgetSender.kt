package com.tvboxremote.app.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.tvboxremote.app.BuildConfig
import com.tvboxremote.app.atv.AtvRemoteSession
import com.tvboxremote.app.atv.ClientIdentity
import com.tvboxremote.app.bluetooth.BluetoothHidRemote
import com.tvboxremote.app.bluetooth.HidState
import com.tvboxremote.app.data.AppStore
import com.tvboxremote.app.discovery.BoxDiscovery
import com.tvboxremote.app.remote.HidAction
import com.tvboxremote.app.remote.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The connections of the app screen, so that the widget can use them instead of opening a second one. */
object LiveLinks {
    /** The Wi-Fi session of the app, while it is open. */
    @Volatile var wifi: AtvRemoteSession? = null

    /** True while the app screen (its view model) exists. Then the app owns the Bluetooth link. */
    @Volatile var appActive: Boolean = false

    /** The Bluetooth address that the widget connects to now. The app does not take over or end that link. */
    @Volatile var widgetBluetooth: String? = null
}

/**
 * Sends one widget key press to a saved device. If the app is connected to that device, it uses the app's
 * connection. Otherwise it connects, waits until the box is ready, sends the key, and keeps the connection
 * for a short time, so that quick repeat presses are fast.
 */
object WidgetSender {
    /** A connection of the widget closes after this time without a press, before WidgetPressService stops. */
    private const val IDLE_CLOSE_MS = 7_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: AtvRemoteSession? = null
    @Volatile private var ownsBluetooth = false
    @Volatile private var closeJob: Job? = null

    /** Returns false if the device cannot be reached. */
    suspend fun send(context: Context, device: SavedDevice, key: RemoteKey): Boolean = mutex.withLock {
        closeJob?.cancel()
        val sent = if (device.isBluetooth) sendBluetooth(context, device, key) else sendWifi(context, device, key)
        scheduleClose(context)
        sent
    }

    private suspend fun sendWifi(context: Context, device: SavedDevice, key: RemoteKey): Boolean {
        val host = device.host ?: return false
        LiveLinks.wifi?.takeIf { it.host == host && it.ready.value && it.closed.value == null }?.let {
            it.sendKey(key.atvCode)
            return true
        }
        var s = session?.takeIf { it.host == host && it.ready.value && it.closed.value == null }
        if (s == null) {
            session?.close()
            session = null
            s = open(context, host, device.port ?: BoxDiscovery.DEFAULT_ATV_PORT) ?: return false
            session = s
        }
        s.sendKey(key.atvCode)
        // Give the write queue a moment, because the system can stop the process soon after the press.
        delay(150)
        return true
    }

    /** Connects with the stored pin. Returns null if the box is not paired, not reachable, or not ready in 5 s. */
    private suspend fun open(context: Context, host: String, port: Int): AtvRemoteSession? {
        val fingerprint = AppStore(context).fingerprint(host) ?: return null
        val identity = runCatching { ClientIdentity.get(context) }.getOrNull() ?: return null
        val s = AtvRemoteSession(
            identity = identity, host = host, port = port, expectedFingerprint = fingerprint,
            model = Build.MODEL, vendor = Build.MANUFACTURER, appVersion = BuildConfig.VERSION_NAME,
        )
        val ready = withTimeoutOrNull(6_000) {
            runCatching { s.start() }.getOrElse { return@withTimeoutOrNull false }
            combine(s.ready, s.closed) { r, closed -> if (closed != null) false else if (r) true else null }
                .filterNotNull()
                .first()
        } == true
        if (!ready) {
            s.close()
            return null
        }
        return s
    }

    private suspend fun sendBluetooth(context: Context, device: SavedDevice, key: RemoteKey): Boolean {
        val action = key.hid ?: return false
        val address = device.address ?: return false
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        val hid = BluetoothHidRemote.get(context)
        if (!hid.isBluetoothOn) return false
        LiveLinks.widgetBluetooth = address
        if (hid.connectedDevice?.address != address) {
            val target = hid.bondedDevices().firstOrNull { it.address == address } ?: return false
            hid.connect(target)
            val connected = withTimeoutOrNull(8_000) {
                hid.state.first { it is HidState.Connected && it.device.address == address }
            } != null
            if (!connected) return false
            if (!LiveLinks.appActive) ownsBluetooth = true
            // The box needs a moment after the connection before it reads key reports.
            delay(300)
        }
        return when (action) {
            is HidAction.Keyboard -> hid.pressKey(action.usage)
            is HidAction.Consumer -> hid.pressConsumer(action.usage)
        }
    }

    private fun scheduleClose(context: Context) {
        closeJob = scope.launch {
            delay(IDLE_CLOSE_MS)
            mutex.withLock {
                session?.close()
                session = null
                LiveLinks.widgetBluetooth = null
                if (ownsBluetooth && !LiveLinks.appActive) {
                    val hid = BluetoothHidRemote.get(context)
                    if (Build.VERSION.SDK_INT < 31 ||
                        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    ) runCatching { hid.unregister() }
                }
                ownsBluetooth = false
            }
        }
    }

    /**
     * The app screen closes. If a widget press used Bluetooth in the last seconds, the widget closes the
     * link after its idle time instead of the app now. Returns true in that case.
     */
    fun adoptBluetooth(): Boolean {
        if (closeJob?.isActive != true || LiveLinks.widgetBluetooth == null) return false
        ownsBluetooth = true
        return true
    }
}
