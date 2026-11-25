package com.tvboxremote.app

import com.tvboxremote.app.ui.remoteBackground
import androidx.compose.ui.graphics.Color
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tvboxremote.app.controller.GamepadButton
import com.tvboxremote.app.controller.Stick
import com.tvboxremote.app.remote.RemoteKey
import com.tvboxremote.app.ui.ControllerActions
import com.tvboxremote.app.ui.ControllerLayoutEditor
import com.tvboxremote.app.ui.ControllerScreen
import com.tvboxremote.app.ui.Direction
import com.tvboxremote.app.ui.AboutScreen
import com.tvboxremote.app.ui.CodeDialog
import com.tvboxremote.app.ui.DevicesScreen
import com.tvboxremote.app.ui.Haptics
import com.tvboxremote.app.ui.HelpScreen
import com.tvboxremote.app.ui.KeyboardSheet
import com.tvboxremote.app.ui.MediaKeysSheet
import com.tvboxremote.app.ui.MenuItem
import com.tvboxremote.app.ui.MouseScreen
import com.tvboxremote.app.ui.Palette
import com.tvboxremote.app.ui.PreferencesScreen
import com.tvboxremote.app.ui.RemoteScreen
import com.tvboxremote.app.ui.RemoteTheme

enum class Screen(val fullScreen: Boolean = false, val landscape: Boolean = false) {
    REMOTE(fullScreen = true),
    DEVICES,
    MOUSE,
    PREFERENCES(fullScreen = true),
    HELP,
    ABOUT,
    CONTROLLER(fullScreen = true, landscape = true),
    CONTROLLER_LAYOUT(fullScreen = true, landscape = true),
}

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by vm.preferences.collectAsState()
            RemoteTheme(prefs.theme, prefs.accent) {
                // Dark icons on the light theme, light icons on the dark theme.
                val dark = Palette.isDark
                LaunchedEffect(dark) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    )
                }
                App(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.discovery.start()
        vm.reconnectLast()
    }

    override fun onStop() {
        super.onStop()
        vm.discovery.stop()
    }

    fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Controller mode: landscape, no system bars, and the screen stays on. [keepScreenOn] is the user preference. */
    fun setControllerMode(on: Boolean, keepScreenOn: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
        setKeepScreenOn(on || keepScreenOn)
    }
}

/** Sends the controller input to the view model. */
private class VmControllerActions(private val vm: MainViewModel) : ControllerActions {
    override fun button(button: GamepadButton, down: Boolean) = vm.gamepadButton(button, down)
    override fun dpad(arrows: Set<Direction>) = vm.gamepadDpad(arrows)
    override fun stick(stick: Stick, x: Float, y: Float) = vm.gamepadStick(stick, x, y)
    override fun stickClick(stick: Stick) = vm.gamepadStickClick(stick)
    override fun releaseAll() = vm.gamepadReleaseAll()
}

@Composable
private fun App(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val prefs by vm.preferences.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.REMOTE) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Haptics.enabled = prefs.haptics

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.openKeyboard.collect { if (vm.isConnected) keyboardOpen = true } }

    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    // The controller always keeps the screen on, also after the activity is recreated.
    LaunchedEffect(prefs.keepScreenOn, screen.landscape) { activity?.setKeepScreenOn(prefs.keepScreenOn || screen.landscape) }

    BackHandler(enabled = screen != Screen.REMOTE) {
        screen = if (screen == Screen.CONTROLLER_LAYOUT) Screen.PREFERENCES else Screen.REMOTE
    }

    val landscape = screen.landscape
    DisposableEffect(landscape) {
        if (landscape) activity?.setControllerMode(true, prefs.keepScreenOn)
        onDispose { if (landscape) activity?.setControllerMode(false, vm.preferences.value.keepScreenOn) }
    }
    val controllerActions = remember(vm) { VmControllerActions(vm) }

    /** Sends a key, or opens Devices when no box is connected. */
    fun key(k: RemoteKey) {
        if (!vm.press(k)) screen = Screen.DEVICES
    }

    // Every screen sits on the textured background (docs/UI_SPEC.md, "Background texture").
    Scaffold(
        modifier = Modifier.remoteBackground(),
        containerColor = Color.Transparent,
        // A transparent container gives no content color, so text without its own color would be black.
        contentColor = Palette.onSurface,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The remote and the controller draw their own background behind the system bars.
        Box(Modifier.fillMaxSize().then(if (screen.fullScreen) Modifier else Modifier.padding(padding))) {
            when (screen) {
                Screen.REMOTE -> RemoteScreen(
                    state = ui,
                    signals = vm.signals,
                    onKey = ::key,
                    onKeyboard = { if (vm.isConnected) keyboardOpen = true else screen = Screen.DEVICES },
                    onMouse = { screen = if (vm.isConnected) Screen.MOUSE else Screen.DEVICES },
                    onController = { screen = if (vm.isConnected) Screen.CONTROLLER else Screen.DEVICES },
                    onOpenDevices = { screen = Screen.DEVICES },
                    onMenu = { item ->
                        when (item) {
                            MenuItem.DEVICES -> screen = Screen.DEVICES
                            MenuItem.MEDIA -> if (vm.isConnected) mediaOpen = true else screen = Screen.DEVICES
                            MenuItem.PREFERENCES -> screen = Screen.PREFERENCES
                            MenuItem.HELP -> screen = Screen.HELP
                            MenuItem.ABOUT -> screen = Screen.ABOUT
                        }
                    },
                )
                Screen.DEVICES -> DevicesScreen(vm, onBack = { screen = Screen.REMOTE })
                Screen.MOUSE -> MouseScreen(vm, onDone = { screen = Screen.REMOTE }, onBluetooth = { screen = Screen.DEVICES })
                Screen.PREFERENCES -> PreferencesScreen(
                    prefs, vm::updatePreferences,
                    onControllerLayout = { screen = Screen.CONTROLLER_LAYOUT },
                    onBack = { screen = Screen.REMOTE },
                )
                Screen.CONTROLLER -> {
                    // The controller closes if the box goes away.
                    LaunchedEffect(ui.link) { if (ui.link !is Link.Connected) screen = Screen.REMOTE }
                    ControllerScreen(
                        layout = remember { vm.controllerLayout },
                        actions = controllerActions,
                        wifi = (ui.link as? Link.Connected)?.transport == Transport.WIFI,
                        rightStickReady = ui.link.let { it is Link.Connected && (it.transport == Transport.BLUETOOTH || ui.receiver is com.tvboxremote.app.receiver.ReceiverState.Ready) },
                        onExit = { screen = Screen.REMOTE },
                    )
                }
                Screen.CONTROLLER_LAYOUT -> ControllerLayoutEditor(
                    initial = remember { vm.controllerLayout },
                    onDone = { vm.saveControllerLayout(it); screen = Screen.PREFERENCES },
                    onCancel = { screen = Screen.PREFERENCES },
                )
                Screen.HELP -> HelpScreen(onBack = { screen = Screen.REMOTE })
                Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.REMOTE })
            }
        }
    }

    if (keyboardOpen) {
        LaunchedEffect(Unit) { vm.resetText() }
        KeyboardSheet(
            onTextChange = vm::setText,
            onKey = { vm.press(it) },
            onDismiss = { keyboardOpen = false },
        )
    }
    if (mediaOpen) {
        MediaKeysSheet(onKey = ::key, onDismiss = { mediaOpen = false })
    }
    ui.codeRequest?.let { request -> CodeDialog(request, onSubmit = vm::submitCode) }
}
