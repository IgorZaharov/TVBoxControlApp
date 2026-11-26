package com.tvboxremote.app.ui.tutorial

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A rectangle in pixels, in root coordinates. Plain Kotlin, so the layout is tested on the JVM. */
data class CoachRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun inflate(d: Float) = CoachRect(left - d, top - d, right + d, bottom + d)

    fun intersects(o: CoachRect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun union(o: CoachRect) = CoachRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

data class CoachPoint(val x: Float, val y: Float)

/**
 * Where the card goes and how the arrow runs. [arrow] is null for a card without a target (a centered card).
 * [c1] and [c2] are the control points of the hand-drawn cubic curve from [from] to [to].
 */
data class CoachPlacement(val card: CoachRect, val arrow: CoachArrow?)

data class CoachArrow(val from: CoachPoint, val c1: CoachPoint, val c2: CoachPoint, val to: CoachPoint)

/**
 * The placement rule of the coach cards (docs/UI_SPEC.md, "First-run tutorial"): the card goes on the side of the
 * target with more free space, at least [gap] away, so it never covers the target. The arrow runs from the card edge
 * to the target edge.
 */
object CoachLayout {
    /**
     * [target] is the spotlight hole, or null for a centered card. [safeTop] and [safeBottom] are the system bars.
     * [edge] is the side margin, [gap] the room for the arrow between the card and the target.
     */
    fun place(
        target: CoachRect?,
        screenWidth: Float,
        screenHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        edge: Float,
        gap: Float,
        safeTop: Float = 0f,
        safeBottom: Float = 0f,
    ): CoachPlacement {
        val minTop = safeTop + edge
        val maxTop = max(minTop, screenHeight - safeBottom - edge - cardHeight)
        val left = ((screenWidth - cardWidth) / 2f).coerceAtLeast(edge)
        if (target == null) {
            val top = ((screenHeight - cardHeight) / 2f).coerceIn(minTop, maxTop)
            return CoachPlacement(CoachRect(left, top, left + cardWidth, top + cardHeight), null)
        }

        val spaceBelow = screenHeight - safeBottom - target.bottom
        val spaceAbove = target.top - safeTop
        val below = spaceBelow >= cardHeight + gap + edge || spaceBelow >= spaceAbove
        // The arrow needs some room. With less space, the card moves closer, but never onto the target.
        val minGap = edge
        var top = if (below) target.bottom + gap else target.top - gap - cardHeight
        top = top.coerceIn(minTop, maxTop)
        if (below && top < target.bottom + minGap) top = target.bottom + minGap
        if (!below && top + cardHeight > target.top - minGap) top = target.top - minGap - cardHeight

        val cardLeft = (target.centerX - cardWidth / 2f).coerceIn(edge, max(edge, screenWidth - edge - cardWidth))
        val card = CoachRect(cardLeft, top, cardLeft + cardWidth, top + cardHeight)

        // The arrow starts on the near card edge, between the target and the card center, and ends on the near target edge.
        val inset = min(28f, card.width / 4f)
        val fromX = ((target.centerX + card.centerX) / 2f).coerceIn(card.left + inset, card.right - inset)
        val fromY = if (below) card.top - 6f else card.bottom + 6f
        val toX = (target.centerX + (fromX - target.centerX) * 0.3f).coerceIn(target.left + 12f, target.right - 12f)
        val toY = if (below) target.bottom + 6f else target.top - 6f
        val from = CoachPoint(fromX, fromY)
        val to = CoachPoint(toX, toY)
        return CoachPlacement(card, curve(from, to))
    }

    /** A gentle hand-drawn bend: control points pushed to one side of the straight line. */
    fun curve(from: CoachPoint, to: CoachPoint): CoachArrow {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        // The normal of the line, to one side. A bend reads as drawn by hand.
        val side = if (dx >= 0f) -1f else 1f
        val bend = min(0.3f * length, 60f) * side
        val nx = -dy / length * bend
        val ny = dx / length * bend
        val c1 = CoachPoint(from.x + dx * 0.25f + nx, from.y + dy * 0.25f + ny)
        val c2 = CoachPoint(from.x + dx * 0.8f + nx * 0.5f, from.y + dy * 0.8f + ny * 0.5f)
        return CoachArrow(from, c1, c2, to)
    }
}
