package com.tvboxremote.app.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import com.tvboxremote.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------- background

/**
 * A 128 x 128 grain tile, made once with a fixed seed. Dark theme: each pixel is white or black at 0 to 4%
 * opacity. Light theme: black only, at 0 to 2%.
 */
private fun grain(light: Boolean): ImageBitmap {
    val size = 128
    val random = Random(7)
    val pixels = IntArray(size * size) {
        val alpha = random.nextInt(0, if (light) 6 else 11) // of 255: about 0 to 2%, or 0 to 4%
        val white = !light && random.nextBoolean()
        (alpha shl 24) or (if (white) 0xFFFFFF else 0x000000)
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

private val darkGrain: ImageBitmap by lazy { grain(light = false) }
private val lightGrain: ImageBitmap by lazy { grain(light = true) }

/**
 * The textured background of the remote screen (docs/UI_SPEC.md, "Background texture"): a gradient, a soft accent
 * light at the top, a slight pebble leather and a fine grain.
 * The colors are read while drawing, so a theme or accent change redraws it without a recomposition.
 */
@Composable
fun Modifier.remoteBackground(): Modifier {
    val leather = rememberLeatherBrush()
    return drawBehind { drawAppBackground(leather, size) }
}

/** The leather tile (res/drawable-nodpi, made by tools/make_leather.py) is 384 px for 128 dp: scale it to the screen. */
@Composable
private fun rememberLeatherBrush(): ShaderBrush {
    val leather = ImageBitmap.imageResource(R.drawable.leather_grain)
    val density = LocalDensity.current.density
    return remember(leather, density) {
        val shader = android.graphics.BitmapShader(
            leather.asAndroidBitmap(), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT,
        )
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(density / 3f, density / 3f) })
        ShaderBrush(shader)
    }
}

/**
 * Draws the app background of a screen of [screen] size, as it lies under a box at [origin] (root coordinates).
 * The screen draws it at the origin. A glass card draws the part under itself and blurs it.
 */
private fun DrawScope.drawAppBackground(leather: ShaderBrush, screen: Size, origin: Offset = Offset.Zero) {
    translate(-origin.x, -origin.y) {
        drawRect(
            Brush.verticalGradient(listOf(Palette.backgroundTop, Palette.backgroundBottom), endY = screen.height),
            size = screen,
        )
        drawRect(
            Brush.radialGradient(
                listOf(Palette.accent.copy(alpha = 0.07f), Color.Transparent),
                center = Offset(screen.width / 2f, 0f),
                radius = screen.width * 0.7f,
            ),
            size = screen,
        )
        drawRect(leather, size = screen, alpha = if (Palette.isDark) 1f else 0.55f)
        val tile = if (Palette.isDark) darkGrain else lightGrain
        drawRect(ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)), size = screen)
    }
}

// ---------------------------------------------------------------- tilt

/** The phone tilt for the button sheen: x and y from -1 to 1. (0, 0) is the phone held up at about 45 degrees. */
val LocalTilt = staticCompositionLocalOf<State<Offset>> { mutableStateOf(RestingSheen) }

/** Where the sheen sits without tilt data, or under reduced motion: top left. */
val RestingSheen = Offset(-0.5f, -0.6f)

/**
 * Reads the gravity sensor while the screen is resumed and gives the smoothed tilt to [content] through [LocalTilt].
 * The values are read only in draw code, so a tilt change redraws the buttons and does not recompose them.
 */
@Composable
fun ProvideTilt(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduce = rememberReducedMotion()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tilt = remember { Animatable(RestingSheen, Offset.VectorConverter) }
    if (!reduce) {
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                gravity(context).conflate().collect { target ->
                    launch { tilt.animateTo(target, Motion.highlight()) }
                }
            }
        }
    }
    CompositionLocalProvider(LocalTilt provides tilt.asState(), content = content)
}

private fun gravity(context: Context): Flow<Offset> = callbackFlow {
    val manager = context.getSystemService(SensorManager::class.java)
    val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    if (manager == null || sensor == null) {
        close()
        return@callbackFlow
    }
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val g = SensorManager.GRAVITY_EARTH
            // The sensor gives device axes. Turn them into screen axes, so that the light is right in landscape too.
            val (sx, sy) = when (displayRotation(context)) {
                Surface.ROTATION_90 -> -event.values[1] to event.values[0]
                Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
                Surface.ROTATION_270 -> event.values[1] to -event.values[0]
                else -> event.values[0] to event.values[1]
            }
            // Tilt right (gravity x negative) moves the light to the right. Tilt back moves it down.
            val x = (-sx / g * 1.6f).coerceIn(-1f, 1f)
            val y = ((0.7f - sy / g) * 1.6f).coerceIn(-1f, 1f)
            trySend(Offset(x, y))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    awaitClose { manager.unregisterListener(listener) }
}

private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }

// ---------------------------------------------------------------- raised surface

/**
 * Colors of one raised surface. [Color.Unspecified] means "the theme color": those are read while drawing,
 * so a theme change or the rainbow accent redraws the surface without a recomposition.
 */
data class SurfaceStyle(
    val glow: Color = Color.Unspecified,
    /** True for the OK button: an accent body with a halo. */
    val accentBody: Boolean = false,
    /** True for the power button: it glows in the power color. */
    val powerGlow: Boolean = false,
) {
    companion object {
        val Default = SurfaceStyle()
        val Power = SurfaceStyle(powerGlow = true)
        val Ok = SurfaceStyle(accentBody = true)

        fun face(color: Color) = SurfaceStyle(glow = color)
    }
}

/** A soft blurred fill of [path], drawn with the platform blur (hardware accelerated on Android 9 and later). */
fun DrawScope.drawBlurredPath(path: Path, color: Color, blur: Dp, offset: Offset = Offset.Zero) {
    if (color.alpha <= 0f) return
    val radius = blur.toPx()
    // Drawing runs on the main thread, so one paint per blur radius is enough.
    val paint = blurPaints.getOrPut(radius) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
    }
    paint.color = color.toArgb()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.translate(offset.x, offset.y)
        native.drawPath(path.asAndroidPath(), paint)
        native.restore()
    }
}

private val blurPaints = HashMap<Float, android.graphics.Paint>()

fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}

/**
 * Draws a raised surface in [shape]: drop shadow, gradient body, rim light, tilt sheen, press glow and the
 * momentary flash of a press. [pressed] and [glow] go from 0 to 1. They are lambdas, so the values are read
 * only in the draw phase.
 */
fun Modifier.raisedSurface(
    shape: Shape,
    style: SurfaceStyle = SurfaceStyle.Default,
    pressed: () -> Float = { 0f },
    glow: () -> Float = { 0f },
    tilt: State<Offset>? = null,
    flash: () -> Flash = { Flash.None },
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = outline.toPath()
    val p = pressed()
    val g = glow()
    val f = flash()
    val glowColor = when {
        style.glow != Color.Unspecified -> style.glow
        style.powerGlow -> Palette.power
        else -> Palette.accent
    }
    val ok = style.accentBody
    val top = if (ok) Palette.okTop else Palette.bodyTop
    val bottom = if (ok) Palette.okBottom else Palette.bodyBottom
    // Pressed, the gradient flips: the OK body goes from a darker bottom color to a lighter top color.
    val pressedTop = if (ok) lerp(Palette.okBottom, Color.Black, 0.1f) else Palette.bodyPressedTop
    val pressedBottom = if (ok) lerp(Palette.okTop, Color.Black, 0.05f) else Palette.bodyPressedBottom
    val sheenAlpha = Palette.sheen.alpha * (if (ok) 2f else 1f)

    // Drop shadow: blur 14 and offset 6 at rest, blur 6 and offset 2 while pressed.
    // The blur goes in whole dp steps, so that only a few blur paints exist.
    val blur = (14f - 8f * p).roundToInt().dp
    drawBlurredPath(path, Palette.shadow, blur, Offset(0f, (6f - 4f * p).dp.toPx()))

    // Halo (always on for OK), press glow and press flash, outside the body. They come after the shadow,
    // so the shadow does not darken them.
    if (ok) drawBlurredPath(path, glowColor.copy(alpha = 0.25f + 0.30f * p), 24.dp)
    if (g > 0f) {
        drawBlurredPath(path, glowColor.copy(alpha = 0.55f * g), 18.dp)
    }
    drawFlash(shape, f, glowColor)

    // Body: the gradient flips while pressed, so the button looks pushed in.
    drawPath(
        path,
        Brush.verticalGradient(listOf(lerp(top, pressedTop, p), lerp(bottom, pressedBottom, p))),
    )

    clipPath(path) {
        // Sheen: a soft light that follows the tilt of the phone.
        // The center moves at most 30% of the button radius; the light reaches 60% of the button size.
        val t = tilt?.value ?: RestingSheen
        val travel = size.minDimension / 2f * 0.3f
        val center = Offset(size.width / 2f + t.x * travel, size.height / 2f + t.y * travel)
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = (sheenAlpha * (1f - 0.6f * p)).coerceAtMost(1f)), Color.Transparent),
                center = center, radius = size.minDimension * 0.6f,
            )
        )
    }

    // Rim: a thin light on the upper edge.
    drawPath(
        path,
        Brush.verticalGradient(
            0f to Palette.rim.copy(alpha = Palette.rim.alpha * (1f - p)),
            0.5f to Color.White.copy(alpha = 0.02f),
            1f to Color.Transparent,
        ),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * The momentary glow of one press (docs/UI_SPEC.md, "Press glow"). [light] is its opacity, from 0 to 1.
 * [spread] says how far it reaches past the edge of the button, from 0 (3 dp) to 1 (12 dp).
 */
@Immutable
data class Flash(val light: Float, val spread: Float) {
    companion object {
        val None = Flash(0f, 0f)

        /** The flash at [t] (0 to 1) of its run: up to full light in the first 15%, then a slow fade out. */
        fun at(t: Float, expands: Boolean): Flash {
            if (t <= 0f || t >= 1f) return None
            val light = if (t < 0.15f) t / 0.15f else (1f - (t - 0.15f) / 0.85f).let { it * it }
            val spread = if (expands) 1f - (1f - t) * (1f - t) else 0f
            return Flash(light, spread)
        }
    }
}

/** Draws [flash] around a body of [shape]: a soft halo and a thin bright edge that grow outward and fade. */
fun DrawScope.drawFlash(shape: Shape, flash: Flash, color: Color) {
    if (flash.light <= 0f) return
    val grow = (3f + 9f * flash.spread).dp.toPx()
    val outer = shape.createOutline(Size(size.width + grow * 2f, size.height + grow * 2f), layoutDirection, this)
        .toPath()
        .apply { translate(Offset(-grow, -grow)) }
    drawBlurredPath(outer, color.copy(alpha = 0.65f * flash.light), 10.dp)
    drawPath(outer, color.copy(alpha = 0.5f * flash.light), style = Stroke(width = 1.5.dp.toPx()))
}

/**
 * Press state for one control: the press value follows the press spring, the glow pops in and fades out.
 * Each finger down also starts one flash, which plays to its end even after a short tap.
 */
class PressAnimation(private val reduce: Boolean) {
    val press = Animatable(0f)
    val glow = Animatable(0f)
    private val flashTime = Animatable(0f)
    var isPressed by mutableStateOf(false)
        private set

    /** The flash now. Read it in the draw phase. */
    val flash: Flash get() = Flash.at(flashTime.value, expands = !reduce)

    suspend fun down() {
        isPressed = true
        kotlinx.coroutines.coroutineScope {
            launch { press.animateTo(1f, Motion.press(reduce)) }
            launch { glow.animateTo(1f, Motion.pop()) }
            launch { flash() }
        }
    }

    /** Plays one flash: 520 ms, or 300 ms without growth under reduced motion. */
    suspend fun flash() {
        flashTime.snapTo(0f)
        flashTime.animateTo(1f, tween(if (reduce) 300 else 520, easing = LinearEasing))
    }

    suspend fun up() {
        isPressed = false
        kotlinx.coroutines.coroutineScope {
            launch { press.animateTo(0f, Motion.press(reduce)) }
            launch { glow.animateTo(0f, Motion.fadeOut()) }
        }
    }

    /** Scale for this press: 1 to 0.97, or always 1 under reduced motion. */
    val scale: Float get() = if (reduce) 1f else 1f - 0.03f * press.value
}

@Composable
fun rememberPressAnimation(): PressAnimation {
    val reduce = rememberReducedMotion()
    return remember(reduce) { PressAnimation(reduce) }
}

// ---------------------------------------------------------------- glass card

/** How much a glass card blurs the background under it. */
private val GlassBlur = 16.dp

/**
 * A frosted glass card (docs/UI_SPEC.md, "Glass card"): a soft drop shadow, the background under the card blurred,
 * a mostly opaque fill and a thin border. The background does not move, so the card draws the part of it that
 * lies under the card and blurs that (Android 12 and later). On older versions the texture stays sharp under the fill.
 */
@Composable
fun GlassBox(shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val leather = rememberLeatherBrush()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var screen by remember { mutableStateOf(Size.Zero) }
    Box(modifier.glassShadow(shape)) {
        Box(
            Modifier
                .matchParentSize()
                .onGloballyPositioned { c ->
                    origin = c.positionInRoot()
                    screen = c.findRootCoordinates().size.toSize()
                }
                .graphicsLayer {
                    val r = GlassBlur.toPx()
                    renderEffect = BlurEffect(r, r, TileMode.Clamp)
                    this.shape = shape
                    clip = true
                }
                .drawBehind { if (screen != Size.Zero) drawAppBackground(leather, screen, origin) },
        )
        Box(Modifier.matchParentSize().glassFill(shape))
        content()
    }
}

/** The glass card without the blur: shadow, fill and border. */
fun Modifier.glassSurface(shape: Shape): Modifier = glassShadow(shape).glassFill(shape)

/** The drop shadow of a glass card. It is drawn only outside the card, so it does not darken the fill. */
private fun Modifier.glassShadow(shape: Shape): Modifier = drawBehind {
    val path = shape.createOutline(size, layoutDirection, this).toPath()
    val dark = Palette.isDark
    clipPath(path, ClipOp.Difference) {
        if (dark) {
            drawBlurredPath(path, Color.Black.copy(alpha = 0.45f), 24.dp, Offset(0f, 10.dp.toPx()))
        } else {
            drawBlurredPath(path, Color.Black.copy(alpha = 0.10f), 20.dp, Offset(0f, 8.dp.toPx()))
            // A hairline of black 6% just outside the white border.
            drawPath(path, Color.Black.copy(alpha = 0.06f), style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** The fill and the border of a glass card: mostly opaque, so the text stays clear over the background. */
private fun Modifier.glassFill(shape: Shape): Modifier = drawBehind {
    val path = shape.createOutline(size, layoutDirection, this).toPath()
    val dark = Palette.isDark
    val fill = if (dark) {
        listOf(Color(0xFF262930).copy(alpha = 0.80f), Color(0xFF1B1D23).copy(alpha = 0.74f))
    } else {
        listOf(Color.White.copy(alpha = 0.90f), Color.White.copy(alpha = 0.82f))
    }
    drawPath(path, Brush.verticalGradient(fill))
    // The border: 1 dp inside the edge (a 2 dp stroke, clipped to the card).
    val border = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f)))
    } else {
        Brush.verticalGradient(listOf(Color.White, Color.White))
    }
    clipPath(path) { drawPath(path, border, style = Stroke(width = 2.dp.toPx())) }
}

// ---------------------------------------------------------------- D-pad glow

/** The D-pad glow opacity breathes from 18% to 42% and back in 5 s. Under reduced motion it stays at 30%. */
@Composable
fun rememberBreath(): State<Float> {
    val reduce = rememberReducedMotion()
    if (reduce) return remember { mutableFloatStateOf(0.3f) }
    val transition = rememberInfiniteTransition(label = "dpad-glow")
    return transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(2_500, easing = Motion.sine), RepeatMode.Reverse),
        label = "breath",
    )
}

// ---------------------------------------------------------------- signal line

/** At most this many pulses run on the signal line at the same time. */
private const val MAX_PULSES = 3

/**
 * A thin vertical line above the D-pad that shows each command sent to the box: a pulse of light runs up
 * from the bottom. [length] is the line height (64 dp, shorter on a short screen). Decorative: it has no semantics.
 */
@Composable
fun SignalLine(signals: Flow<Unit>, modifier: Modifier = Modifier, length: Dp = 64.dp, lineColor: Color = Color.Unspecified) {
    val reduce = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val pulses = remember { mutableStateListOf<Animatable<Float, *>>() }
    val trackGlow = remember { Animatable(0f) }
    var flash by remember { mutableStateOf(0f) }

    LaunchedEffect(signals, reduce) {
        signals.collect {
            if (reduce) {
                // No movement: the whole line fades in and out in 300 ms. Delay steps, because animations are off.
                for (step in 1..18) {
                    flash = if (step <= 9) step / 9f else (18 - step) / 9f
                    delay(16)
                }
                flash = 0f
                return@collect
            }
            if (pulses.size >= MAX_PULSES) pulses.removeAt(0)
            val pulse = Animatable(0f)
            pulses.add(pulse)
            scope.launch {
                pulse.animateTo(1f, Motion.pulse())
                pulses.remove(pulse)
            }
            scope.launch {
                trackGlow.animateTo(1f, Motion.pop())
                trackGlow.animateTo(0f, androidx.compose.animation.core.tween(600, easing = Motion.easeOut))
            }
        }
    }

    Canvas(modifier.size(width = 4.dp, height = length)) {
        // The live accent is read here, in the draw phase, so the RGB accent does not recompose the line.
        val color = if (lineColor != Color.Unspecified) lineColor else Palette.accent
        val w = size.width
        val h = size.height
        val track = RoundRect(Rect(Offset.Zero, size), CornerRadius(w / 2f))
        val trackPath = Path().apply { addRoundRect(track) }
        val lit = maxOf(flash, trackGlow.value)

        drawPath(trackPath, if (Palette.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f))
        if (lit > 0f) {
            drawBlurredPath(trackPath, color.copy(alpha = 0.45f * lit), 6.dp)
            drawPath(trackPath, color.copy(alpha = (0.25f + 0.65f * flash) * lit))
        }

        val segment = h * 0.4f
        pulses.forEach { pulse ->
            val t = pulse.value
            val top = (h + segment) * (1f - t) - segment // from below the bottom to above the top
            val fade = if (t > 0.8f) (1f - t) / 0.2f else 1f
            val brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.95f * fade),
                0.25f to color.copy(alpha = fade),
                1f to Color.Transparent,
                startY = top, endY = top + segment,
            )
            val glowPath = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(0f, top), Size(w, segment)), CornerRadius(w / 2f)))
            }
            drawBlurredPath(glowPath, color.copy(alpha = 0.7f * fade), 8.dp)
            clipPath(trackPath) { drawRect(brush, topLeft = Offset(0f, top), size = Size(w, segment)) }
        }
    }
}
