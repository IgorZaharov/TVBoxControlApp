package com.tvboxremote.app.remote

import androidx.annotation.StringRes
import com.tvboxremote.app.R
import com.tvboxremote.app.bluetooth.ConsumerUsage
import com.tvboxremote.app.bluetooth.KeyUsage

/** What a key sends over Bluetooth HID. */
sealed interface HidAction {
    data class Keyboard(val usage: Int) : HidAction
    data class Consumer(val usage: Int) : HidAction
}

/**
 * The keys of the remote. [atvCode] is the Android KeyEvent code for the
 * Android TV Remote protocol. [hid] is null if Bluetooth has no matching key.
 */
enum class RemoteKey(@StringRes val label: Int, val atvCode: Int, val hid: HidAction?, val repeats: Boolean = false) {
    UP(R.string.key_up, 19, HidAction.Keyboard(KeyUsage.UP), repeats = true),
    DOWN(R.string.key_down, 20, HidAction.Keyboard(KeyUsage.DOWN), repeats = true),
    LEFT(R.string.key_left, 21, HidAction.Keyboard(KeyUsage.LEFT), repeats = true),
    RIGHT(R.string.key_right, 22, HidAction.Keyboard(KeyUsage.RIGHT), repeats = true),
    OK(R.string.key_ok, 23, HidAction.Keyboard(KeyUsage.ENTER)),
    BACK(R.string.key_back, 4, HidAction.Consumer(ConsumerUsage.BACK)),
    HOME(R.string.key_home, 3, HidAction.Consumer(ConsumerUsage.HOME)),
    SETTINGS(R.string.key_settings, 176, HidAction.Consumer(ConsumerUsage.CONTROL_PANEL)),
    POWER(R.string.key_power, 26, HidAction.Consumer(ConsumerUsage.POWER)),
    VOLUME_UP(R.string.key_volume_up, 24, HidAction.Consumer(ConsumerUsage.VOLUME_UP), repeats = true),
    VOLUME_DOWN(R.string.key_volume_down, 25, HidAction.Consumer(ConsumerUsage.VOLUME_DOWN), repeats = true),
    MUTE(R.string.key_mute, 164, HidAction.Consumer(ConsumerUsage.MUTE)),
    ENTER(R.string.key_enter, 66, HidAction.Keyboard(KeyUsage.ENTER)),
    DELETE(R.string.key_delete, 67, HidAction.Keyboard(KeyUsage.BACKSPACE), repeats = true),
    MENU(R.string.key_menu, 82, HidAction.Consumer(ConsumerUsage.MENU)),
    SEARCH(R.string.key_search, 84, HidAction.Consumer(ConsumerUsage.SEARCH)),
    PLAY_PAUSE(R.string.key_play_pause, 85, HidAction.Consumer(ConsumerUsage.PLAY_PAUSE)),
    NEXT(R.string.key_next, 87, HidAction.Consumer(ConsumerUsage.NEXT)),
    PREVIOUS(R.string.key_previous, 88, HidAction.Consumer(ConsumerUsage.PREVIOUS)),
    REWIND(R.string.key_rewind, 89, HidAction.Consumer(ConsumerUsage.REWIND)),
    FAST_FORWARD(R.string.key_fast_forward, 90, HidAction.Consumer(ConsumerUsage.FAST_FORWARD)),
    CHANNEL_UP(R.string.key_channel_up, 166, HidAction.Consumer(ConsumerUsage.CHANNEL_UP)),
    CHANNEL_DOWN(R.string.key_channel_down, 167, HidAction.Consumer(ConsumerUsage.CHANNEL_DOWN)),
    GUIDE(R.string.key_guide, 172, HidAction.Consumer(ConsumerUsage.GUIDE)),
    INPUT(R.string.key_input, 178, null),
    ALL_APPS(R.string.key_all_apps, 284, null),
}
