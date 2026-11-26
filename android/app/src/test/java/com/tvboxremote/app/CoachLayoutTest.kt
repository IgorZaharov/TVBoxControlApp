package com.tvboxremote.app

import com.tvboxremote.app.ui.tutorial.CoachLayout
import com.tvboxremote.app.ui.tutorial.CoachRect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachLayoutTest {
    private val w = 1080f
    private val h = 2340f
    private val edge = 48f
    private val gap = 200f

    private fun inside(r: CoachRect) = r.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h

    @Test
    fun cardGoesBelowATargetAtTheTop() {
        val target = CoachRect(50f, 60f, 550f, 200f)
        val p = CoachLayout.place(target, w, h, 900f, 400f, edge, gap)
        assertTrue(p.card.top >= target.bottom + gap - 0.5f)
        assertFalse(p.card.intersects(target))
        assertTrue(inside(p.card))
        assertNotNull(p.arrow)
        val arrow = p.arrow!!
        assertTrue(arrow.from.y < p.card.top)
        assertTrue(arrow.to.y > target.bottom && arrow.to.x in target.left..target.right)
    }

    @Test
    fun cardGoesAboveATargetAtTheBottom() {
        val target = CoachRect(80f, 1700f, 1000f, 2150f)
        val p = CoachLayout.place(target, w, h, 900f, 500f, edge, gap)
        assertTrue(p.card.bottom <= target.top)
        assertFalse(p.card.intersects(target))
        assertTrue(inside(p.card))
    }

    @Test
    fun cardNeverCoversTheTargetWhenSpaceIsTight() {
        // A tall target leaves less room on both sides than the card and the gap need.
        val target = CoachRect(40f, 700f, 1040f, 1500f)
        val p = CoachLayout.place(target, w, h, 900f, 600f, edge, gap)
        assertFalse(p.card.intersects(target))
        assertTrue(inside(p.card))
    }

    @Test
    fun centeredCardHasNoArrow() {
        val p = CoachLayout.place(null, w, h, 900f, 600f, edge, gap)
        assertNull(p.arrow)
        assertTrue(inside(p.card))
        assertTrue(kotlin.math.abs(p.card.centerY - h / 2f) < 1f)
    }

    @Test
    fun systemBarsStayClear() {
        val target = CoachRect(50f, 1900f, 1030f, 2100f)
        val p = CoachLayout.place(target, w, h, 900f, 400f, edge, gap, safeTop = 120f, safeBottom = 160f)
        assertTrue(p.card.top >= 120f + edge)
        assertTrue(p.card.bottom <= h - 160f)
        assertFalse(p.card.intersects(target))
    }
}
