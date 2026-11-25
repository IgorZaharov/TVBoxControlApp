package com.tvboxremote.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds nearby Bluetooth devices and bonds with one. The caller must hold
 * BLUETOOTH_SCAN and BLUETOOTH_CONNECT (Android 12+) or location (Android 9 to 11).
 */
@SuppressLint("MissingPermission")
class BluetoothScanner(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _found = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val found: StateFlow<List<BluetoothDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _bonded = MutableStateFlow<BluetoothDevice?>(null)
    /** The last device that finished bonding. */
    val bonded: StateFlow<BluetoothDevice?> = _bonded.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.device() ?: return
                    if (_found.value.none { it.address == device.address }) _found.value = _found.value + device
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.device() ?: return
                    if (device.bondState == BluetoothDevice.BOND_BONDED) _bonded.value = device
                }
            }
        }
    }

    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun ensureRegistered() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // The system Bluetooth service sends these broadcasts, so the receiver must be exported.
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun start() {
        val a = adapter ?: return
        ensureRegistered()
        _found.value = emptyList()
        runCatching {
            if (a.isDiscovering) a.cancelDiscovery()
            _scanning.value = a.startDiscovery()
        }
    }

    fun stop() {
        runCatching { adapter?.cancelDiscovery() }
        _scanning.value = false
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Starts bonding. The phone and the box both ask the user to confirm. */
    fun bond(device: BluetoothDevice): Boolean {
        runCatching { adapter?.cancelDiscovery() }
        _scanning.value = false
        ensureRegistered()
        return runCatching { device.createBond() }.getOrDefault(false)
    }

    fun consumeBonded() { _bonded.value = null }
}
