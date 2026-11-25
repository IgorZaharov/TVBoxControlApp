package com.tvboxremote.app.data

import android.content.Context
import com.tvboxremote.app.ui.AccentChoice
import com.tvboxremote.app.ui.ThemeMode
import com.tvboxremote.app.widget.SavedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Preferences(
    val haptics: Boolean = true,
    val gyroSpeed: Float = 1f,
    val touchpadSpeed: Float = 1f,
    val keepScreenOn: Boolean = false,
    /** The Gyro switch on the mouse screen. */
    val gyroEnabled: Boolean = true,
    val theme: ThemeMode = ThemeMode.DARK,
    val accent: AccentChoice = AccentChoice.BLUE,
)

/** What the app keeps between starts: pairings, receiver tokens, the last box, and preferences. */
class AppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tvboxremote", Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<Preferences> = _preferences.asStateFlow()

    private fun loadPreferences() = Preferences(
        haptics = prefs.getBoolean("p_haptics", true),
        gyroSpeed = prefs.getFloat("p_gyro", 1f),
        touchpadSpeed = prefs.getFloat("p_touch", 1f),
        keepScreenOn = prefs.getBoolean("p_screen", false),
        gyroEnabled = prefs.getBoolean("p_gyro_on", true),
        theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("p_theme", null) } ?: ThemeMode.DARK,
        accent = AccentChoice.entries.firstOrNull { it.name == prefs.getString("p_accent", null) } ?: AccentChoice.BLUE,
    )

    fun updatePreferences(p: Preferences) {
        prefs.edit()
            .putBoolean("p_haptics", p.haptics)
            .putFloat("p_gyro", p.gyroSpeed)
            .putFloat("p_touch", p.touchpadSpeed)
            .putBoolean("p_screen", p.keepScreenOn)
            .putBoolean("p_gyro_on", p.gyroEnabled)
            .putString("p_theme", p.theme.name)
            .putString("p_accent", p.accent.name)
            .apply()
        _preferences.value = p
    }

    /** SHA-256 fingerprint of the box certificate, stored after pairing. Keyed by the box IP address. */
    fun fingerprint(host: String): String? = prefs.getString("fp_$host", null)
    fun setFingerprint(host: String, fp: String?) = prefs.edit().apply {
        if (fp == null) remove("fp_$host") else putString("fp_$host", fp)
    }.apply()

    fun receiverToken(host: String): String? = prefs.getString("rt_$host", null)
    fun setReceiverToken(host: String, token: String?) = prefs.edit().apply {
        if (token == null) remove("rt_$host") else putString("rt_$host", token)
    }.apply()

    /** The last box. Format: "wifi:<host>:<port>:<name>" or "bt:<address>". */
    var lastDevice: String?
        get() = prefs.getString("last_device", null)
        set(value) = prefs.edit().putString("last_device", value).apply()

    /** Boxes added by IP address. Format per entry: "<host>|<name>". */
    var manualBoxes: Set<String>
        get() = prefs.getStringSet("manual_boxes", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("manual_boxes", value).apply()

    /** The controller layout, in the format of ControllerLayout.encode. Null for the default layout. */
    var controllerLayout: String?
        get() = prefs.getString("controller_layout", null)
        set(value) = prefs.edit().putString("controller_layout", value).apply()

    /** The devices that the app connected to at least once, the most recent first. The widget lists them. */
    var savedDevices: List<SavedDevice>
        get() = SavedDevice.decode(prefs.getString("saved_devices", null))
        set(value) = prefs.edit().putString("saved_devices", SavedDevice.encode(value)).apply()

    fun rememberDevice(device: SavedDevice) {
        savedDevices = SavedDevice.remember(savedDevices, device)
    }

    fun forget(host: String) {
        savedDevices = savedDevices.filterNot { it.host == host }
        setFingerprint(host, null)
        setReceiverToken(host, null)
        manualBoxes = manualBoxes.filterNot { it.substringBefore('|') == host }.toSet()
        if (lastDevice?.startsWith("wifi:$host:") == true) lastDevice = null
    }
}
