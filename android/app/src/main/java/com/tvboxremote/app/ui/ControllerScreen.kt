package com.tvboxremote.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.R
import com.tvboxremote.app.controller.ControlPlacement
import com.tvboxremote.app.controller.ControllerControl
import com.tvboxremote.app.controller.ControllerLayout
import com.tvboxremote.app.controller.Gamepad
import com.tvboxremote.app.controller.GamepadButton
import com.tvboxremote.app.controller.Stick
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the controller sends. */
interface ControllerActions {
    fun button(button: GamepadButton, down: Boolean)
    fun dpad(arrows: Set<Direction>)
    fun stick(stick: Stick, x: Float, y: Float)
    fun stickClick(stick: Stick)
    fun releaseAll()
}

private object NoActions : ControllerActions {
    override fun button(button: GamepadButton, down: Boolean) = Unit
    override fun dpad(arrows: Set<Direction>) = Unit
    override fun stick(stick: Stick, x: Float, y: Float) = Unit
    override fun stickClick(stick: Stick) = Unit
    override fun releaseAll() = Unit
}

/**
 * The controller mode (docs/UI_SPEC.md, "Controller mode"). The caller turns the phone to landscape,
 * hides the system bars and keeps the screen on while this screen shows.
 */
@Composable
fun ControllerScreen(
    layout: ControllerLayout,
    actions: ControllerActions,
    wifi: Boolean,
    rightStickReady: Boolean,
    onExit: () -> Unit,
) {
    DisposableEffect(actions) { onDispose { actions.releaseAll() } }
    ProvideTilt {
        BoxWithConstraints(Modifier.fillMaxSize().remoteBackground()) {
            val w = maxWidth
            val h = maxHeight
            ControllerControl.entries.forEach { control ->
                val p = ControllerLayout.clamp(control, layout[control], w.value, h.value)
                Placed(control, p, w, h) {
                    ControlView(control, actions, enabled = control != ControllerControl.RIGHT_STICK || rightStickReady)
                }
            }
            RemoteButton(
                Icons.Filled.Close, stringResource(R.string.controller_exit), size = 44.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp), onPress = onExit,
            )
            if (wifi) {
                Text(
                    stringResource(if (rightStickReady) R.string.controller_wifi_note else R.string.controller_wifi_note_no_receiver),
                    color = Palette.muted, fontSize = 12.sp,
                    // Under the Select and Start row, where the default layout has free space.
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
                )
            }
        }
    }
}

/**
 * The layout editor: the controller without output. Drag a control to move it, tap it to select it and
 * change its size. [onDone] gets the new layout; [onCancel] keeps the old one.
 */
@Composable
fun ControllerLayoutEditor(initial: ControllerLayout, onDone: (ControllerLayout) -> Unit, onCancel: () -> Unit) {
    var layout by remember { mutableStateOf(initial) }
    var selected by remember { mutableStateOf<ControllerControl?>(null) }
    val density = LocalDensity.current
    ProvideTilt {
        BoxWithConstraints(Modifier.fillMaxSize().remoteBackground()) {
            val w = maxWidth
            val h = maxHeight
            val widthPx = with(density) { w.toPx() }
            val heightPx = with(density) { h.toPx() }
            ControllerControl.entries.forEach { control ->
                val p = ControllerLayout.clamp(control, layout[control], w.value, h.value)
                Placed(control, p, w, h) {
                    ControlView(control, NoActions, enabled = true)
                    // Covers the control, so that it does not react, and takes the drags and taps.
                    Box(
                        Modifier
                            .matchParentSize()
                            .drawBehind {
                                if (selected == control) {
                                    drawRoundRect(
                                        Palette.accent, style = Stroke(width = 2.dp.toPx()),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                                    )
                                }
                            }
                            .pointerInput(control, widthPx, heightPx) {
                                detectDragGestures(onDragStart = { selected = control }) { change, drag ->
                                    change.consume()
                                    val cur = ControllerLayout.clamp(control, layout[control], w.value, h.value)
                                    val moved = cur.copy(x = cur.x + drag.x / widthPx, y = cur.y + drag.y / heightPx)
                                    layout = layout.with(control, ControllerLayout.clamp(control, moved, w.value, h.value))
                                }
                            }
                            .pointerInput(control) { detectTapGestures { selected = control } }
                            .semantics { invisibleToUser() },
                    )
                }
            }

            // One panel in the free middle area, between the sticks and below Select and Start,
            // so that it covers no control of the default layout.
            val sel = selected
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = h * 0.236f)
                    // Wide enough for the three buttons in every language: the labels never wrap.
                    .widthIn(min = 320.dp, max = 560.dp)
                    .width(IntrinsicSize.Max)
                    .background(Palette.surface.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (sel == null) {
                    Text(stringResource(R.string.layout_hint), color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    val scale = layout[sel].scale.coerceIn(ControllerLayout.MIN_SCALE, ControllerLayout.MAX_SCALE)
                    Text(
                        stringResource(R.string.layout_size, (scale * 100).roundToInt()),
                        fontSize = 13.sp, color = Palette.onSurface,
                    )
                    Slider(
                        value = scale,
                        onValueChange = { v ->
                            val cur = layout[sel]
                            layout = layout.with(sel, ControllerLayout.clamp(sel, cur.copy(scale = v), w.value, h.value))
                        },
                        valueRange = ControllerLayout.MIN_SCALE..ControllerLayout.MAX_SCALE,
                    )
                }
                Row(
                    Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { layout = ControllerLayout.DEFAULT; selected = null }) {
                        Text(stringResource(R.string.action_reset), maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = { onDone(layout) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                    ) { Text(stringResource(R.string.action_done), maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** Puts [content] at the place of [control] on a screen of [w] x [h]. */
@Composable
private fun Placed(control: ControllerControl, p: ControlPlacement, w: Dp, h: Dp, content: @Composable BoxScope.() -> Unit) {
    val cw = (control.widthDp * p.scale).dp
    val ch = (control.heightDp * p.scale).dp
    Box(
        Modifier
            .offset(x = w * p.x - cw / 2, y = h * p.y - ch / 2)
            .size(cw, ch),
        content = content,
    )
}

@Composable
private fun ControlView(control: ControllerControl, actions: ControllerActions, enabled: Boolean) {
    when (control) {
        ControllerControl.L1 -> ShoulderButton("L1", GamepadButton.L1, actions)
        ControllerControl.L2 -> ShoulderButton("L2", GamepadButton.L2, actions)
        ControllerControl.R1 -> ShoulderButton("R1", GamepadButton.R1, actions)
        ControllerControl.R2 -> ShoulderButton("R2", GamepadButton.R2, actions)
        ControllerControl.SELECT -> ShoulderButton(stringResource(R.string.controller_select), GamepadButton.SELECT, actions, small = true)
        ControllerControl.START -> ShoulderButton(stringResource(R.string.controller_start), GamepadButton.START, actions, small = true)
        ControllerControl.LEFT_STICK -> GameStick(stringResource(R.string.controller_left_stick), enabled = true, Stick.LEFT, actions)
        ControllerControl.RIGHT_STICK -> GameStick(
            stringResource(if (enabled) R.string.controller_right_stick else R.string.controller_right_unavailable),
            enabled, Stick.RIGHT, actions,
        )
        ControllerControl.DPAD -> GameDpad(actions)
        ControllerControl.FACE -> FaceButtons(actions)
    }
}

/**
 * A press area that reports finger down and finger up, for buttons that the TV may see held.
 * A screen reader double tap sends down and up at once.
 */
@Composable
private fun Modifier.holdPress(label: String, anim: PressAnimation, onChange: (Boolean) -> Unit): Modifier {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onChange)
    return this
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { current(true); current(false); true }
        }
        .pointerInput(anim) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                if (Haptics.enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                scope.launch { anim.down() }
                current(true)
                try {
                    waitForUpOrCancellation()
                } finally {
                    current(false)
                    scope.launch { anim.up() }
                }
            }
        }
}

@Composable
private fun ShoulderButton(label: String, button: GamepadButton, actions: ControllerActions, small: Boolean = false) {
    val anim = rememberPressAnimation()
    val shape = RoundedCornerShape(if (small) 18.dp else 14.dp)
    val description = stringResource(R.string.controller_button, label)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = anim.scale; scaleY = anim.scale }
            .raisedSurface(shape, pressed = { anim.press.value }, glow = { anim.glow.value }, tilt = LocalTilt.current, flash = { anim.flash })
            .holdPress(description, anim) { actions.button(button, it) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = Palette.onSurface, fontWeight = FontWeight.SemiBold,
            fontSize = if (small) 12.sp else 16.sp, maxLines = 1,
        )
    }
}

/** A, B, X, Y in a diamond: Y top, X left, B right, A bottom. */
@Composable
private fun FaceButtons(actions: ControllerActions) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val button = maxWidth * (64f / 176f)
        FaceButton("Y", GamepadButton.Y, Palette.faceY, button, Modifier.align(Alignment.TopCenter), actions)
        FaceButton("X", GamepadButton.X, Palette.faceX, button, Modifier.align(Alignment.CenterStart), actions)
        FaceButton("B", GamepadButton.B, Palette.faceB, button, Modifier.align(Alignment.CenterEnd), actions)
        FaceButton("A", GamepadButton.A, Palette.faceA, button, Modifier.align(Alignment.BottomCenter), actions)
    }
}

@Composable
private fun FaceButton(letter: String, button: GamepadButton, color: Color, size: Dp, modifier: Modifier, actions: ControllerActions) {
    val anim = rememberPressAnimation()
    val description = stringResource(R.string.controller_button, letter)
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = anim.scale; scaleY = anim.scale }
            .raisedSurface(CircleShape, SurfaceStyle.face(color), pressed = { anim.press.value }, glow = { anim.glow.value }, tilt = LocalTilt.current, flash = { anim.flash })
            .holdPress(description, anim) { actions.button(button, it) },
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}

/**
 * A virtual stick: the knob follows the finger inside the base and springs back on release.
 * A short tap without movement is a stick click (L3 or R3).
 */
@Composable
private fun GameStick(label: String, enabled: Boolean, stick: Stick, actions: ControllerActions) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val reduce = rememberReducedMotion()
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) } // -1..1 on each axis
    val touch = rememberPressAnimation()
    val currentActions by rememberUpdatedState(actions)
    val tilt = LocalTilt.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .alpha(if (enabled) 1f else 0.35f)
            .semantics {
                contentDescription = label
                if (enabled) onClick { currentActions.stickClick(stick); true }
            }
            .drawBehind {
                // The base: a recessed well with a faint ring at half travel (where a stick starts to act as a D-pad).
                val r = size.minDimension / 2f
                drawCircle(Brush.radialGradient(listOf(Palette.wellCenter, Palette.wellEdge), center = center, radius = r), r)
                val line = if (Palette.isDark) Color.White else Color.Black
                drawCircle(line.copy(alpha = 0.08f), r - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
                drawCircle(line.copy(alpha = 0.06f), r * 0.5f, style = Stroke(1.dp.toPx()))
            }
            .pointerInput(enabled, stick) {
                if (!enabled) return@pointerInput
                val travel = size.width / 2f * (1f - 60f / 132f)
                awaitEachGesture {
                    // The point where the finger lands is the center, so a tap does not move the stick.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (Haptics.enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    scope.launch { touch.flash() }
                    var maxMove = 0f
                    var clicked = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                val elapsed = change.uptimeMillis - down.uptimeMillis
                                clicked = maxMove < travel * 0.15f && elapsed < 300
                                break
                            }
                            var v = (change.position - down.position) / travel
                            val length = v.getDistance()
                            maxMove = maxOf(maxMove, length * travel)
                            if (length > 1f) v /= length
                            scope.launch { knob.snapTo(v) }
                            currentActions.stick(stick, v.x, v.y)
                            change.consume()
                        }
                    } finally {
                        // Also when the gesture is cancelled, for example when the stick becomes unavailable.
                        currentActions.stick(stick, 0f, 0f)
                        scope.launch { knob.animateTo(Offset.Zero, Motion.press(reduce)) }
                    }
                    if (clicked) currentActions.stickClick(stick)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val knobSize = maxWidth * (60f / 132f)
        val travelDp = maxWidth / 2 * (1f - 60f / 132f)
        val density = LocalDensity.current
        val travelPx = with(density) { travelDp.toPx() }
        Box(
            Modifier
                .offset { IntOffset((knob.value.x * travelPx).roundToInt(), (knob.value.y * travelPx).roundToInt()) }
                .size(knobSize)
                .raisedSurface(CircleShape, tilt = tilt, flash = { touch.flash })
        )
    }
}

/** The controller D-pad: one surface; the finger direction gives up to two arrows (8 directions). */
@Composable
private fun GameDpad(actions: ControllerActions) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentActions by rememberUpdatedState(actions)
    val anims = Direction.entries.associateWith { rememberPressAnimation() }
    var active by remember { mutableStateOf(emptySet<Direction>()) }
    val label = stringResource(R.string.controller_dpad)
    Box(
        Modifier
            .fillMaxSize()
            .raisedSurface(CircleShape, tilt = LocalTilt.current)
            .drawWithContent {
                drawContent()
                anims.forEach { (direction, anim) -> drawArrowLight(direction, anim.glow.value, anim.press.value, anim.flash) }
            }
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f
                fun set(arrows: Set<Direction>) {
                    if (arrows == active) return
                    if ((arrows - active).isNotEmpty() && Haptics.enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    (arrows - active).forEach { d -> scope.launch { anims.getValue(d).down() } }
                    (active - arrows).forEach { d -> scope.launch { anims.getValue(d).up() } }
                    active = arrows
                    currentActions.dpad(arrows)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun at(p: Offset) = Gamepad.arrows((p.x - c.x) / radius, (p.y - c.y) / radius, Gamepad.DPAD_DEAD_ZONE)
                    try {
                        set(at(down.position))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            set(at(change.position))
                            change.consume()
                        }
                    } finally {
                        set(emptySet())
                    }
                }
            },
    ) {
        DpadIcon(Icons.Filled.KeyboardArrowUp, Modifier.align(Alignment.TopCenter), anims.getValue(Direction.UP))
        DpadIcon(Icons.Filled.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter), anims.getValue(Direction.DOWN))
        DpadIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart), anims.getValue(Direction.LEFT))
        DpadIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd), anims.getValue(Direction.RIGHT))
    }
}

@Composable
private fun DpadIcon(icon: ImageVector, modifier: Modifier, anim: PressAnimation) {
    val tint = lerp(Palette.onSurface, Palette.accentLit, anim.press.value)
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.padding(6.dp).size(32.dp))
}
