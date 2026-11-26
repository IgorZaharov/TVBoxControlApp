package com.tvboxremote.app.ui

import com.tvboxremote.app.ui.tutorial.MouseTour
import com.tvboxremote.app.ui.tutorial.ReceiverGuide
import com.tvboxremote.app.ui.tutorial.TourTargets
import com.tvboxremote.app.ui.tutorial.coachTarget
import com.tvboxremote.app.bluetooth.HidState
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight

import com.tvboxremote.app.LinkTyping
import androidx.compose.material3.AlertDialog
import com.tvboxremote.app.receiver.ReceiverDownload
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import android.widget.Toast
import com.tvboxremote.app.R
import androidx.compose.ui.res.stringResource
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.Link
import com.tvboxremote.app.MainViewModel
import com.tvboxremote.app.Transport
import com.tvboxremote.app.motion.GyroPointer
import com.tvboxremote.app.receiver.ReceiverState
import com.tvboxremote.app.remote.RemoteKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun MouseScreen(vm: MainViewModel, onDone: () -> Unit, onBluetooth: () -> Unit) {
    var sharing by remember { mutableStateOf(false) }
    // The receiver install guide (docs/UI_SPEC.md, "Receiver install guide").
    var guideOpen by remember { mutableStateOf(false) }
    val guideRequest by vm.mouseGuideRequest.collectAsState()
    LaunchedEffect(guideRequest) {
        if (guideRequest) {
            guideOpen = true
            vm.mouseGuideShown()
        }
    }
    if (guideOpen) ReceiverGuide(vm, onClose = { guideOpen = false })
    val linkTyping by vm.linkTyping.collectAsState()
    if (sharing) ReceiverShareScreen(onClose = { sharing = false })
    if (linkTyping != LinkTyping.IDLE) {
        LinkTypingDialog(
            linkTyping,
            onClose = vm::closeTypeLink,
            onShare = { vm.closeTypeLink(); sharing = true },
        )
    }
    val context = LocalContext.current
    val view = LocalView.current
    val ui by vm.ui.collectAsState()
    val prefs by vm.preferences.collectAsState()
    // Stored in the preferences, so that the switch keeps its position after the app restarts.
    val gyroOn = prefs.gyroEnabled
    // The gyro pauses briefly after a click, because pressing the screen tilts the phone.
    var lastClick by remember { mutableStateOf(0L) }

    val transport = (ui.link as? Link.Connected)?.transport
    val ready = transport == Transport.BLUETOOTH || ui.receiver is ReceiverState.Ready

    val gyro = remember {
        GyroPointer(context) { dx, dy ->
            if (SystemClock.uptimeMillis() - lastClick > 250) vm.mouseMove(dx, dy)
        }
    }
    gyro.speed = prefs.gyroSpeed

    LaunchedEffect(transport) { if (transport == Transport.WIFI) vm.connectReceiver() }
    LaunchedEffect(ready) { if (ready) vm.showCursor(true) }

    DisposableEffect(gyroOn, ready) {
        if (gyroOn && ready) gyro.start()
        onDispose { gyro.stop() }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = prefs.keepScreenOn
            vm.showCursor(false)
        }
    }

    fun click() {
        lastClick = SystemClock.uptimeMillis()
        vm.mouseClick()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mouse_title), fontSize = 22.sp, modifier = Modifier.weight(1f))
            if (gyro.available) {
                Text(stringResource(R.string.mouse_gyro), color = Palette.muted, modifier = Modifier.padding(end = 8.dp))
                Switch(
                    checked = gyroOn, onCheckedChange = { vm.updatePreferences(prefs.copy(gyroEnabled = it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent),
                )
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_done), fontSize = 16.sp) }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Palette.surface)
                .then(
                    if (ready) Modifier.touchpad(
                        speed = { prefs.touchpadSpeed },
                        onMove = vm::mouseMove,
                        onScroll = vm::mouseScroll,
                        onTap = ::click,
                        onLongPress = { lastClick = SystemClock.uptimeMillis(); vm.mouseLongPress() },
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (ready) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null, tint = Palette.muted)
                    Text(
                        stringResource(if (gyroOn && gyro.available) R.string.mouse_hint_gyro else R.string.mouse_hint_touch),
                        color = Palette.muted, textAlign = TextAlign.Center, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                    )
                }
            } else if (transport == Transport.WIFI && (ui.receiver is ReceiverState.Failed || ui.receiver is ReceiverState.Disconnected)) {
                // No receiver yet: the two ways to set up the mouse. The walk-through card shows the first time.
                LaunchedEffect(Unit) { vm.startMouseTourIfFirst() }
                MouseSetup(
                    failure = ui.receiver as? ReceiverState.Failed,
                    showBluetooth = ui.hid != HidState.Unavailable,
                    onBluetooth = { vm.chooseBluetoothMouse(); onBluetooth() },
                    onReceiver = {
                        if (vm.mouseTour.value == MouseTour.CHOOSE) vm.finishMouseTour()
                        guideOpen = true
                    },
                    onRetry = vm::connectReceiver,
                    onTypeLink = vm::startTypeLink,
                )
            } else {
                MouseUnavailable(ui.receiver, transport)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.key_back)) { vm.press(RemoteKey.BACK) }
            ClickBar(enabled = ready, modifier = Modifier.weight(1f), onClick = ::click)
            RemoteButton(Icons.Filled.Home, stringResource(R.string.key_home)) { vm.press(RemoteKey.HOME) }
        }
    }
}

@Composable
private fun ClickBar(enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(Sizes.roundButton)
            .clip(RoundedCornerShape(36.dp))
            .background(if (pressed) Palette.accent.copy(alpha = 0.75f) else if (enabled) Palette.accent else Palette.surface)
            .remotePress(stringResource(R.string.mouse_click), onPressedChange = { pressed = it }) { if (enabled) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.mouse_click), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (enabled) Palette.onAccent else Palette.muted)
    }
}

/** The mouse is not ready: what the phone waits for (a box, the receiver, its code). */
@Composable
private fun MouseUnavailable(state: ReceiverState, transport: Transport?) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val text = when {
            transport == null -> stringResource(R.string.mouse_connect_first)
            state is ReceiverState.Connecting -> stringResource(R.string.mouse_receiver_connecting)
            state is ReceiverState.PairRequired -> stringResource(R.string.mouse_enter_code)
            else -> stringResource(R.string.mouse_install_receiver)
        }
        if (state is ReceiverState.Connecting) {
            CircularProgressIndicator(color = Palette.accent, modifier = Modifier.padding(bottom = 16.dp).size(32.dp))
        }
        Text(text, textAlign = TextAlign.Center, fontSize = 16.sp, color = Palette.onSurface)
    }
}

/**
 * The mouse setup (docs/UI_SPEC.md, "Mouse setup"): "Connect via Bluetooth" (Android only) and "Install auxiliary
 * Receiver on device". The receiver opens the install guide. The old ways to get the receiver stay below.
 */
@Composable
private fun MouseSetup(
    failure: ReceiverState.Failed?,
    showBluetooth: Boolean,
    onBluetooth: () -> Unit,
    onReceiver: () -> Unit,
    onRetry: () -> Unit,
    onTypeLink: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Mouse, contentDescription = null, tint = Palette.muted, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.mouse_setup_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.onSurface)
            Text(stringResource(R.string.mouse_setup_text), fontSize = 15.sp, lineHeight = 22.sp, color = Palette.muted, textAlign = TextAlign.Center)
            if (failure != null) {
                Text(
                    listOfNotNull(stringResource(failure.message), failure.detail).joinToString("\n"),
                    color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
        }
        if (showBluetooth) {
            SetupChoice(
                Icons.Filled.Bluetooth, stringResource(R.string.mouse_choice_bt), stringResource(R.string.mouse_choice_bt_detail),
                Modifier.coachTarget("${TourTargets.MOUSE_CHOICES}/bluetooth"), onBluetooth,
            )
        }
        SetupChoice(
            Icons.Filled.Download, stringResource(R.string.mouse_choice_rx), stringResource(R.string.mouse_choice_rx_detail),
            Modifier.coachTarget("${TourTargets.MOUSE_CHOICES}/receiver"), onReceiver,
        )
        // The other ways to get the receiver: copy the fixed link, or let the phone type it on the box.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(ReceiverDownload.URL))
                Toast.makeText(context, R.string.receiver_link_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.receiver_link_copy)) }
            TextButton(onClick = onTypeLink) { Text(stringResource(R.string.receiver_link_type)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
        }
    }
}

/** One big setup button: an accent icon, the title and one line on what it means. */
@Composable
private fun SetupChoice(icon: ImageVector, title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.onSurface)
            Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = Palette.muted)
        }
    }
}

/**
 * Scroll values follow the protocol: positive means the finger moved up or left.
 *
 * Touch pad gestures: one-finger drag moves, two-finger drag scrolls,
 * a tap clicks, and a still press of 550 ms is a long press.
 */
@Composable
private fun Modifier.touchpad(
    speed: () -> Float,
    onMove: (Float, Float) -> Unit,
    onScroll: (Float, Float) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown()
            var moved = false
            var multi = false
            var longFired = false
            var total = Offset.Zero
            var longJob: Job? = scope.launch {
                delay(550)
                if (!moved && !multi) { longFired = true; onLongPress() }
            }
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) multi = true
                // Average the movement of all fingers on the pad.
                val delta = pressed.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / pressed.size.toFloat()
                total += delta
                if (!moved && (abs(total.x) > slop || abs(total.y) > slop)) {
                    moved = true
                    longJob?.cancel(); longJob = null
                }
                if (moved && !longFired) {
                    if (multi) onScroll(-delta.x, -delta.y) else onMove(delta.x * speed(), delta.y * speed())
                }
                event.changes.forEach { it.consume() }
            }
            longJob?.cancel()
            val duration = SystemClock.uptimeMillis() - down.uptimeMillis
            if (!moved && !multi && !longFired && duration < 550) onTap()
        }
    }
}

/** "Type link on the box": what the phone waits for, and the fallback when the box does not take the text. */
@Composable
private fun LinkTypingDialog(state: LinkTyping, onClose: () -> Unit, onShare: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.link_typing_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state == LinkTyping.WAITING) {
                    CircularProgressIndicator(color = Palette.accent, modifier = Modifier.padding(bottom = 12.dp).size(28.dp))
                }
                Text(
                    stringResource(
                        when (state) {
                            LinkTyping.TYPED -> R.string.link_typing_done
                            LinkTyping.NO_KEYBOARD -> R.string.link_typing_no_keyboard
                            else -> R.string.link_typing_wait
                        }
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(if (state == LinkTyping.WAITING) R.string.action_cancel else R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.receiver_share_open)) }
        },
    )
}
