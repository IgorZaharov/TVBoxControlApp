package com.tvboxremote.app.ui.tutorial

import android.graphics.BlurMaskFilter
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.R
import androidx.compose.ui.res.stringResource
import com.tvboxremote.app.ui.Motion
import com.tvboxremote.app.ui.Palette
import com.tvboxremote.app.ui.rememberReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Motion of the coach overlay (docs/UI_SPEC.md, "First-run tutorial"). Onboarding is a rare moment, so it takes
// its time: the screen dims, the arrow draws itself, then the card rises in.

/** The screen dims on this soft ease-out (animate-ui `ease.soft`, 400 ms). */
private val EaseSoft = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/** A mark drawing itself (animate-ui `ease.draw`). */
internal val EaseDraw = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** The dim color: black at 66 %. */
internal val ScrimColor = Color(0xFF040508)
internal const val SCRIM_ALPHA = 0.66f

/** The places of the controls that a coach card can point at, in root coordinates. */
class CoachTargets {
    private val rects = mutableStateMapOf<String, CoachRect>()

    fun set(id: String, rect: CoachRect) {
        if (rects[id] != rect) rects[id] = rect
    }

    fun remove(id: String) {
        rects.remove(id)
    }

    /** The target [id], joined with all targets named "[id]/…" (for example the rows of a list). */
    fun rect(id: String): CoachRect? {
        var result: CoachRect? = null
        for ((key, r) in rects) {
            if (key == id || key.startsWith("$id/")) result = result?.union(r) ?: r
        }
        return result
    }
}

/** Provided at the root of the app, so screens can mark targets and the root overlay can find them. */
val LocalCoachTargets = staticCompositionLocalOf { CoachTargets() }

/** Marks this control as the coach target [id]. Several controls named "[id]/…" form one target. */
fun Modifier.coachTarget(id: String): Modifier = composed {
    val targets = LocalCoachTargets.current
    DisposableEffect(targets, id) { onDispose { targets.remove(id) } }
    onGloballyPositioned { c ->
        val b = c.boundsInRoot()
        targets.set(id, CoachRect(b.left, b.top, b.right, b.bottom))
    }
}

private val RectConverter = TwoWayConverter<CoachRect, AnimationVector4D>(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { CoachRect(it.v1, it.v2, it.v3, it.v4) },
)

/**
 * One coach step over the whole screen: the screen dims, a spotlight keeps [targetId] bright and tappable, a
 * hand-drawn arrow points at it, and [content] sits in a glowing card beside it. Taps outside the spotlight and the
 * card do nothing. With [targetId] null, the card is centered and there is no arrow.
 *
 * [stepKey] changes for each new step: the arrow and the card then come in again. The spotlight glides to a new target.
 */
@Composable
fun CoachOverlay(targetId: String?, stepKey: Any, content: @Composable ColumnScope.() -> Unit) {
    val targets = LocalCoachTargets.current
    val target = targetId?.let { targets.rect(it) }
    // A target that is not laid out yet (a list that is still loading): wait for it.
    if (targetId != null && target == null) return

    val density = LocalDensity.current
    val reduce = rememberReducedMotion()
    val accent = Palette.accent
    val pane = stringResource(R.string.tour_pane)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val px = { dpValue: Float -> dpValue * density.density }
        val hole = target?.inflate(px(8f))
        val insets = WindowInsets.safeDrawing
        val safeTop = insets.getTop(density).toFloat()
        val safeBottom = insets.getBottom(density).toFloat()
        val edge = px(16f)
        val cardWidth = min(screenW - 2 * edge, px(340f))
        var cardHeight by remember(stepKey) { mutableFloatStateOf(0f) }
        val placement = CoachLayout.place(
            hole, screenW, screenH, cardWidth, if (cardHeight > 0f) cardHeight else px(180f),
            edge = edge, gap = px(64f), safeTop = safeTop, safeBottom = safeBottom,
        )
        val below = hole == null || placement.card.top >= hole.bottom

        // The screen dims once for the whole overlay. Each step draws its arrow and brings its card.
        val scrim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { scrim.animateTo(1f, tween(if (reduce) 200 else 400, easing = EaseSoft)) }
        val draw = remember(stepKey) { Animatable(if (reduce) 1f else 0f) }
        val enter = remember(stepKey) { Animatable(0f) }
        LaunchedEffect(stepKey, reduce) {
            if (reduce) {
                enter.animateTo(1f, tween(200))
            } else {
                delay(200)
                launch { draw.animateTo(1f, tween(780, easing = EaseDraw)) }
                delay(350)
                enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 400f))
            }
        }
        // The spotlight glides to the next target (animate-ui `spring.morph`: critically damped).
        val spot = remember { Animatable(hole ?: CoachRect(0f, 0f, 0f, 0f), RectConverter) }
        LaunchedEffect(hole) {
            if (hole == null) return@LaunchedEffect
            if (spot.value.width <= 0f || reduce) spot.snapTo(hole) else spot.animateTo(hole, spring(dampingRatio = 1f, stiffness = 660f))
        }

        val loop = rememberInfiniteTransition(label = "coach")
        val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = Motion.easeOut), RepeatMode.Restart), label = "pulse")
        val nudge by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = Motion.sine), RepeatMode.Reverse), label = "nudge")

        val current = if (hole != null) spot.value else null
        val radius = current?.let { min(it.height / 2f, px(22f)) } ?: 0f

        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(0f, 0f, size.width, size.height))
                if (current != null) addRoundRect(current.toRoundRect(radius))
            }
            drawPath(path, ScrimColor.copy(alpha = SCRIM_ALPHA * scrim.value))
            if (current != null) {
                drawGlowRing(current, radius, accent.copy(alpha = 0.55f * scrim.value), px(4f), px(12f))
                drawRoundRect(
                    accent.copy(alpha = 0.85f * scrim.value), Offset(current.left, current.top), Size(current.width, current.height),
                    CornerRadius(radius), style = Stroke(px(1.5f)),
                )
                if (!reduce) {
                    val grow = current.inflate(px(6f) + px(10f) * pulse)
                    drawRoundRect(
                        accent.copy(alpha = 0.8f * (1f - pulse) * scrim.value), Offset(grow.left, grow.top), Size(grow.width, grow.height),
                        CornerRadius(radius + px(6f) + px(10f) * pulse), style = Stroke(px(2f)),
                    )
                }
            }
        }

        // Taps outside the spotlight do nothing during a step. The spotlight itself has no blocker, so the control
        // under it takes the tap.
        if (current == null) {
            Box(Modifier.fillMaxSize().blockTouches())
        } else {
            Blocker(0f, 0f, screenW, current.top)
            Blocker(0f, current.bottom, screenW, screenH)
            Blocker(0f, current.top, current.left, current.bottom)
            Blocker(current.right, current.top, screenW, current.bottom)
        }

        placement.arrow?.let { arrow ->
            Canvas(Modifier.fillMaxSize()) {
                // The arrow nudges toward its target, 4 dp, every 2.4 s.
                val dx = arrow.to.x - arrow.c2.x
                val dy = arrow.to.y - arrow.c2.y
                val len = hypot(dx, dy).coerceAtLeast(1f)
                val shift = if (reduce) Offset.Zero else Offset(dx / len, dy / len) * (px(4f) * nudge)
                translate(shift.x, shift.y) { drawCoachArrow(arrow, draw.value, accent, px(3f), px(12f)) }
            }
        }

        val p = enter.value
        val rise = px(12f) * (1f - p) * (if (below) 1f else -1f)
        Column(
            Modifier
                .offset { IntOffset(placement.card.left.roundToInt(), (placement.card.top + rise).roundToInt()) }
                .width(with(density) { cardWidth.toDp() })
                .onSizeChanged { cardHeight = it.height.toFloat() }
                .graphicsLayer { alpha = if (cardHeight > 0f) p.coerceIn(0f, 1f) else 0f }
                .semantics { paneTitle = pane; liveRegion = LiveRegionMode.Polite }
                .coachCard()
                .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun Blocker(left: Float, top: Float, right: Float, bottom: Float) {
    if (right <= left || bottom <= top) return
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
            .blockTouches(),
    )
}

/** Takes every touch, so the dimmed controls under it get none. */
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** The card of a coach step: the surface at 86 %, a 1.5 dp accent outline and a soft accent glow around it. */
fun Modifier.coachCard(corner: Float = 20f): Modifier = composed {
    val accent = Palette.accent
    val density = LocalDensity.current
    val shape = RoundedCornerShape(corner.dp)
    val r = corner * density.density
    this
        .drawBehind {
            drawGlowRing(CoachRect(0f, 0f, size.width, size.height), r, accent.copy(alpha = 0.45f), 4.dp.toPx(), 16.dp.toPx())
        }
        .clip(shape)
        .background(Palette.surface.copy(alpha = 0.86f))
        .border(1.5.dp, accent.copy(alpha = 0.7f), shape)
}

private val glowPaints = HashMap<Float, android.graphics.Paint>()

/** A blurred ring along [rect]: the glow of the spotlight and of the card. */
internal fun DrawScope.drawGlowRing(rect: CoachRect, radius: Float, color: Color, width: Float, blur: Float) {
    val paint = glowPaints.getOrPut(blur) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
    }
    paint.strokeWidth = width
    paint.color = color.toArgb()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.bottom), radius, radius, paint) }
}

private fun CoachRect.toRoundRect(radius: Float) = RoundRect(left, top, right, bottom, CornerRadius(radius))

/**
 * The hand-drawn arrow at [progress] (0 to 1): the line draws first, a fainter second line follows a little behind,
 * then the head. A wide faint stroke under it is the glow.
 */
internal fun DrawScope.drawCoachArrow(arrow: CoachArrow, progress: Float, color: Color, stroke: Float, head: Float) {
    val path = Path().apply {
        moveTo(arrow.from.x, arrow.from.y)
        cubicTo(arrow.c1.x, arrow.c1.y, arrow.c2.x, arrow.c2.y, arrow.to.x, arrow.to.y)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    val lineProgress = (progress / 0.77f).coerceIn(0f, 1f)
    val sketchProgress = ((progress - 0.1f) / 0.77f).coerceIn(0f, 1f)
    val cap = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

    val line = Path()
    measure.getSegment(0f, length * lineProgress, line, true)
    drawPath(line, color.copy(alpha = 0.3f), style = Stroke(stroke * 3f, cap = StrokeCap.Round))
    drawPath(line, color, style = cap)

    val sketch = Path()
    measure.getSegment(0f, length * sketchProgress, sketch, true)
    translate(stroke * 0.9f, -stroke * 0.9f) {
        drawPath(sketch, color.copy(alpha = 0.4f), style = Stroke(stroke * 0.5f, cap = StrokeCap.Round))
    }

    val headProgress = ((progress - 0.77f) / 0.23f).coerceIn(0f, 1f)
    if (headProgress <= 0f) return
    // The head follows the tangent at the end of the curve.
    val tx = arrow.to.x - arrow.c2.x
    val ty = arrow.to.y - arrow.c2.y
    val tl = hypot(tx, ty).coerceAtLeast(1f)
    val bx = -tx / tl
    val by = -ty / tl
    val angle = Math.toRadians(28.0)
    fun wing(sign: Double) = Offset(
        arrow.to.x + head * (bx * cos(angle) - by * sin(angle) * sign).toFloat(),
        arrow.to.y + head * (bx * sin(angle) * sign + by * cos(angle)).toFloat(),
    )
    val w1 = wing(1.0)
    val w2 = wing(-1.0)
    val headPath = Path().apply {
        moveTo(w1.x, w1.y)
        lineTo(arrow.to.x, arrow.to.y)
        lineTo(w2.x, w2.y)
    }
    val headMeasure = PathMeasure().apply { setPath(headPath, false) }
    val partial = Path()
    headMeasure.getSegment(0f, headMeasure.length * headProgress, partial, true)
    drawPath(partial, color, style = cap)
}

/** The text of a coach card: a small step label, the title, the body, and one quiet action (Skip, Not now). */
@Composable
fun ColumnScope.CoachText(label: String, title: String, body: String, action: String?, onAction: () -> Unit) {
    Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = Palette.muted,
        modifier = Modifier.padding(end = 8.dp))
    Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.onSurface, modifier = Modifier.padding(end = 8.dp))
    Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = Palette.onSurface.copy(alpha = 0.86f), modifier = Modifier.padding(end = 8.dp))
    if (action != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onAction) { Text(action, color = Palette.accent, fontSize = 15.sp) }
        }
    } else {
        Box(Modifier.size(8.dp))
    }
}

/** The check of the last card: the circle pops in, then the check draws itself. */
@Composable
fun CoachCheck() {
    val reduce = rememberReducedMotion()
    val accent = Palette.accent
    val pop = remember { Animatable(if (reduce) 1f else 0.5f) }
    val alpha = remember { Animatable(0f) }
    val check = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(200)) }
        if (!reduce) {
            launch { pop.animateTo(1f, spring(dampingRatio = 0.54f, stiffness = 500f)) }
            delay(350)
            check.animateTo(1f, tween(300, easing = EaseDraw))
        }
    }
    Box(
        Modifier
            .size(64.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value; this.alpha = alpha.value }
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.15f))
            .border(1.5.dp, accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val s = size.width / 24f
            val path = Path().apply {
                moveTo(5f * s, 12.5f * s)
                lineTo(10f * s, 17.5f * s)
                lineTo(19f * s, 7f * s)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * check.value, part, true)
            drawPath(part, accent, style = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
