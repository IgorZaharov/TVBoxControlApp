package com.tvboxremote.app.ui

import android.util.Log
import android.view.View
import android.media.SoundPool
import android.media.AudioAttributes
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The press feedback of all remote buttons: the haptic tick and the tap sound (Preferences, Remote). */
object Haptics {
    var enabled: Boolean = true
    var sound: Boolean = false

    private var pool: SoundPool? = null
    private var tapId = 0
    @Volatile private var tapLoaded = false

    /** Loads the tap sound (res/raw/tap.wav, made by tools/make_tap.py). Call it when the sound is turned on. */
    fun loadSound(context: Context) {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            // The media volume, like a game. The user turned the sound on in the app, so the system sound
            // volume and silent mode (often muted) do not mute it. SoundPool takes no audio focus: music keeps playing.
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attributes).build().also { p ->
            p.setOnLoadCompleteListener { _, _, status ->
                tapLoaded = status == 0
                Log.i(TAG, "Tap sound loaded, status $status")
                if (tapLoaded && previewPending) {
                    previewPending = false
                    playTap()
                }
            }
            tapId = p.load(context.applicationContext, R.raw.tap, 1)
        }
    }

    /** Plays the tap once, also before it has loaded: when the user turns the sound on in Preferences. */
    fun preview(context: Context) {
        if (tapLoaded) playTap() else {
            previewPending = true
            loadSound(context)
        }
    }

    @Volatile private var previewPending = false

    private fun playTap() {
        val stream = pool?.play(tapId, TAP_VOLUME, TAP_VOLUME, 1, 0, 1f) ?: 0
        if (stream == 0) Log.w(TAG, "Tap sound did not play (loaded: $tapLoaded)")
    }

    /** The feedback of one press. */
    fun press(view: View) {
        if (enabled) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound && tapLoaded) playTap()
    }

    private const val TAG = "TVBoxRemote"

    /** The tap file is already soft (tools/make_tap.py), so it plays at the full media volume. */
    private const val TAP_VOLUME = 1f
}

/**
 * A press area that reacts on finger down (not on release), like a real remote.
 * If [repeats] is true, it repeats while held: first after 400 ms, then every 110 ms.
 * [animation] plays the press and glow motion; [onPressedChange] reports the finger state.
 */
@Composable
fun Modifier.remotePress(
    label: String,
    repeats: Boolean = false,
    animation: PressAnimation? = null,
    onPressedChange: (Boolean) -> Unit = {},
    onPress: () -> Unit,
): Modifier {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentOnPress by rememberUpdatedState(onPress)
    val currentPressedChange by rememberUpdatedState(onPressedChange)
    fun fire() {
        Haptics.press(view)
        currentOnPress()
    }
    return this
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { fire(); true }
        }
        .pointerInput(repeats, animation) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                currentPressedChange(true)
                animation?.let { scope.launch { it.down() } }
                fire()
                var job: Job? = null
                if (repeats) {
                    job = scope.launch {
                        delay(400)
                        while (true) { fire(); delay(110) }
                    }
                }
                waitForUpOrCancellation()
                job?.cancel()
                animation?.let { scope.launch { it.up() } }
                currentPressedChange(false)
            }
        }
}

/** A raised button: shadow, gradient body, rim, tilt sheen, press scale and press glow. */
@Composable
fun RemoteButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.roundButton,
    shape: Shape = CircleShape,
    tint: Color = Palette.onSurface,
    style: SurfaceStyle = SurfaceStyle.Default,
    repeats: Boolean = false,
    onPress: () -> Unit,
) {
    val anim = rememberPressAnimation()
    val tilt = LocalTilt.current
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = anim.scale; scaleY = anim.scale }
            .raisedSurface(shape, style, pressed = { anim.press.value }, glow = { anim.glow.value }, tilt = tilt, flash = { anim.flash })
            .remotePress(label, repeats, anim, onPress = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.icon * (size / Sizes.roundButton)))
    }
}

/**
 * The power button. Like the power key of an appliance, it shows the state of the box ([on]):
 * on, a green symbol with a green halo and a lit LED dot; off (standby), a red symbol with a red LED dot;
 * unknown (null), a red symbol with no LED. The change cross-fades in 300 ms.
 */
@Composable
fun PowerButton(on: Boolean?, label: String, modifier: Modifier = Modifier, size: Dp = Sizes.roundButton, onPress: () -> Unit) {
    val anim = rememberPressAnimation()
    val tilt = LocalTilt.current
    val color by animateColorAsState(
        if (on == true) Palette.ok else Palette.power, tween(POWER_FADE_MS, easing = FastOutSlowInEasing), label = "power color",
    )
    val halo by animateFloatAsState(if (on == true) 1f else 0f, tween(POWER_FADE_MS), label = "power halo")
    val led by animateFloatAsState(
        when (on) { true -> 1f; false -> 0.75f; null -> 0f }, tween(POWER_FADE_MS), label = "power led",
    )
    val state = on?.let { stringResource(if (it) R.string.power_state_on else R.string.power_state_off) }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = anim.scale; scaleY = anim.scale }
            .raisedSurface(
                CircleShape, SurfaceStyle(glow = color, halo = halo),
                pressed = { anim.press.value }, glow = { anim.glow.value }, tilt = tilt, flash = { anim.flash },
            )
            .remotePress(label, animation = anim, onPress = onPress)
            .semantics { if (state != null) stateDescription = state },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PowerSettingsNew, contentDescription = null,
            tint = color.copy(alpha = if (on == false) 0.8f else 1f),
            modifier = Modifier.size(Sizes.icon * (size / Sizes.roundButton)),
        )
        // The LED: a 5 dp dot under the symbol with a soft light around it.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = size * 0.14f)
                .size(5.dp)
                .drawBehind {
                    if (led <= 0f) return@drawBehind
                    val r = this.size.minDimension / 2f
                    drawCircle(
                        Brush.radialGradient(listOf(color.copy(alpha = 0.6f * led), Color.Transparent), center = center, radius = r * 3.2f),
                        radius = r * 3.2f,
                    )
                    drawCircle(color.copy(alpha = led), radius = r)
                },
        )
    }
}

private const val POWER_FADE_MS = 300

/** A round button with a caption under it, for grids. */
@Composable
fun LabeledButton(icon: ImageVector, label: String, onPress: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteButton(icon = icon, label = label, size = 64.dp, onPress = onPress)
        Text(label, color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

enum class Direction(val angle: Float) { UP(270f), DOWN(90f), LEFT(180f), RIGHT(0f) }

/**
 * The D-pad: one raised ring with four arrow zones and the OK button in the center.
 * A press on an arrow lights an arc of the ring on that side. The ring itself does not move.
 */
@Composable
fun DPad(onArrow: (Direction) -> Unit, onOk: () -> Unit, modifier: Modifier = Modifier, size: Dp = Sizes.dpad) {
    val tilt = LocalTilt.current
    val arrows = Direction.entries.associateWith { rememberPressAnimation() }
    val zone = size * (88f / 264f)
    val breath = rememberBreath()
    Box(modifier.size(size)) {
        // The D-pad glow (docs/UI_SPEC.md, "D-pad glow"): a breathing accent halo outside the ring.
        // It is its own layer, and the breath changes only the layer alpha, so the ring does not redraw each frame.
        // ModulateAlpha applies the alpha to the one draw call and needs no offscreen buffer, which would clip the blur.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = breath.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .drawBehind {
                    // The parameter "size" hides the draw size here, so read it from the draw scope.
                    val radius = this.size.minDimension / 2f + 4.dp.toPx()
                    val halo = Path().apply { addOval(Rect(center, radius)) }
                    drawBlurredPath(halo, Palette.accent, 22.dp)
                }
        )
        DPadRing(onArrow, onOk, size, tilt, arrows, zone)
    }
}

@Composable
private fun DPadRing(
    onArrow: (Direction) -> Unit,
    onOk: () -> Unit,
    size: Dp,
    tilt: androidx.compose.runtime.State<Offset>,
    arrows: Map<Direction, PressAnimation>,
    zone: Dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .raisedSurface(CircleShape, tilt = tilt)
            .drawWithContent {
                // The rim: a thin accent line on the outer edge of the ring.
                val rim = 1.5.dp.toPx()
                drawCircle(Palette.accent.copy(alpha = 0.55f), radius = this.size.minDimension / 2f - rim / 2f, style = Stroke(width = rim))
                drawContent()
                arrows.forEach { (direction, anim) -> drawArrowLight(direction, anim.glow.value, anim.press.value, anim.flash) }
            },
        contentAlignment = Alignment.Center,
    ) {
        ArrowZone(Icons.Filled.KeyboardArrowUp, stringResource(R.string.key_up), Modifier.align(Alignment.TopCenter), zone, arrows.getValue(Direction.UP)) { onArrow(Direction.UP) }
        ArrowZone(Icons.Filled.KeyboardArrowDown, stringResource(R.string.key_down), Modifier.align(Alignment.BottomCenter), zone, arrows.getValue(Direction.DOWN)) { onArrow(Direction.DOWN) }
        ArrowZone(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.key_left), Modifier.align(Alignment.CenterStart), zone, arrows.getValue(Direction.LEFT)) { onArrow(Direction.LEFT) }
        ArrowZone(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.key_right), Modifier.align(Alignment.CenterEnd), zone, arrows.getValue(Direction.RIGHT)) { onArrow(Direction.RIGHT) }

        val ok = rememberPressAnimation()
        Box(
            modifier = Modifier
                .size(size * (104f / 264f))
                .graphicsLayer { scaleX = ok.scale; scaleY = ok.scale }
                .raisedSurface(CircleShape, SurfaceStyle.Ok, pressed = { ok.press.value }, glow = { ok.glow.value }, tilt = tilt, flash = { ok.flash })
                .remotePress(stringResource(R.string.key_ok), animation = ok, onPress = onOk),
            contentAlignment = Alignment.Center,
        ) {
            // Bold 22 counts as large text, so the label needs 3:1 on the OK gradient (docs/UI_SPEC.md, "Contrast").
            Text(stringResource(R.string.key_ok), color = Palette.onAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The light of one pressed arrow: a soft wedge toward the arrow and a bright arc near the rim.
 * The [flash] of the press glows outside the rim on that side.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowLight(
    direction: Direction,
    glow: Float,
    press: Float,
    flash: Flash = Flash.None,
) {
    val sweep = 64f
    if (flash.light > 0f) {
        val rim = size.minDimension / 2f
        val outer = rim + (3f + 9f * flash.spread).dp.toPx()
        val inner = rim - 2.dp.toPx()
        val start = direction.angle - sweep / 2f
        val band = Path().apply {
            arcTo(Rect(center, outer), start, sweep, forceMoveTo = true)
            arcTo(Rect(center, inner), start + sweep, -sweep, forceMoveTo = false)
            close()
        }
        drawBlurredPath(band, Palette.accent.copy(alpha = 0.7f * flash.light), 10.dp)
    }
    val light = maxOf(glow, press)
    if (light <= 0f) return
    val inset = 7.dp.toPx()
    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
    drawArc(
        brush = Brush.radialGradient(
            listOf(Color.Transparent, Palette.accent.copy(alpha = 0.16f * light)),
            center = center, radius = size.minDimension / 2f,
        ),
        startAngle = direction.angle - sweep / 2f, sweepAngle = sweep, useCenter = true,
    )
    val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
    drawArc(
        color = Palette.accent.copy(alpha = 0.35f * light),
        startAngle = direction.angle - sweep / 2f, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
    )
    drawArc(
        color = Palette.accentLit.copy(alpha = light),
        startAngle = direction.angle - sweep / 2f, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(inset, inset), size = arcSize, style = stroke,
    )
}

@Composable
private fun ArrowZone(icon: ImageVector, label: String, modifier: Modifier, size: Dp, anim: PressAnimation, onPress: () -> Unit) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .size(size)
            .clip(CircleShape)
            .remotePress(label, repeats = true, animation = anim, onPress = onPress),
        contentAlignment = Alignment.Center,
    ) {
        val tint = lerp(Palette.onSurface, Palette.accentLit, anim.press.value)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
    }
}

/** A wide raised pill: volume down, mute, volume up. The pill does not scale; each segment lights up. */
@Composable
fun VolumePill(onDown: () -> Unit, onMute: () -> Unit, onUp: () -> Unit, muted: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(36.dp)
    val down = rememberPressAnimation()
    val mute = rememberPressAnimation()
    val up = rememberPressAnimation()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizes.roundButton)
            // The flash of the newest press goes around the whole pill. The segments clip only their own light.
            .raisedSurface(shape, tilt = LocalTilt.current, flash = { listOf(down.flash, mute.flash, up.flash).maxBy { it.light } })
            .clip(shape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PillSegment(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.key_volume_down), repeats = true, Modifier.weight(1f), down, onPress = onDown)
        PillDivider()
        PillSegment(
            Icons.AutoMirrored.Filled.VolumeOff,
            stringResource(if (muted) R.string.key_unmute else R.string.key_mute),
            repeats = false, Modifier.weight(1f), mute,
            tint = if (muted) Palette.accent else Palette.onSurface, onPress = onMute,
        )
        PillDivider()
        PillSegment(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.key_volume_up), repeats = true, Modifier.weight(1f), up, onPress = onUp)
    }
}

@Composable
private fun PillDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight(0.42f)
            .drawBehind { drawRect(Color.White.copy(alpha = 0.07f)) }
    )
}

@Composable
private fun PillSegment(
    icon: ImageVector,
    label: String,
    repeats: Boolean,
    modifier: Modifier,
    anim: PressAnimation,
    tint: Color = Palette.onSurface,
    onPress: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(Sizes.roundButton)
            .drawBehind {
                val light = maxOf(anim.glow.value, anim.press.value, anim.flash.light)
                if (light > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Palette.accent.copy(alpha = 0.24f * light), Color.Transparent),
                            center = center, radius = size.height * 0.9f,
                        )
                    )
                }
            }
            .remotePress(label, repeats, anim, onPress = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.icon))
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .drawBehind {
                drawCircle(color.copy(alpha = 0.35f), radius = size.minDimension)
                drawCircle(color)
            }
    )
}

/** Remembers one [PressAnimation] per key. */
@Composable
fun <K> rememberPressAnimations(keys: List<K>): Map<K, PressAnimation> {
    val reduce = rememberReducedMotion()
    return remember(keys, reduce) { keys.associateWith { PressAnimation(reduce) } }
}
