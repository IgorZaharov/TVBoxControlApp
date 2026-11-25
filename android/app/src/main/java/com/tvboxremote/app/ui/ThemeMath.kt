package com.tvboxremote.app.ui

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Color math for the themes, on 0xRRGGBB ints, so that it runs in plain unit tests. */
object ThemeMath {
    const val DARK_TEXT = 0x14161B
    const val WHITE = 0xFFFFFF

    /** Relative luminance (WCAG 2.2). */
    fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    /** Contrast ratio (WCAG 2.2), from 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** HSL to 0xRRGGBB. [h] in degrees, [s] and [l] from 0 to 1. */
    fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r, g, b) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** White or the dark text color, whichever has the higher contrast with [background]. */
    fun textOn(background: Int): Int =
        if (contrast(WHITE, background) >= contrast(DARK_TEXT, background)) WHITE else DARK_TEXT

    /** The colors of one accent: the accent itself, the OK button gradient and the text on it. */
    data class AccentRgb(val accent: Int, val okTop: Int, val okBottom: Int, val onAccent: Int)

    /**
     * The RGB (rainbow) accent at [hue] (docs/UI_SPEC.md, "Accent"): saturation 85%, lightness from 62% (dark)
     * or 36% (light), changed in 2% steps until the contrast with [background] is at least 4.5:1.
     */
    fun rainbow(hue: Float, dark: Boolean, background: Int): AccentRgb {
        var l = if (dark) 0.62f else 0.36f
        val step = if (dark) 0.02f else -0.02f
        var color = hsl(hue, 0.85f, l)
        while (contrast(color, background) < 4.5 && l in 0.05f..0.95f) {
            l += step
            color = hsl(hue, 0.85f, l)
        }
        return AccentRgb(
            accent = color,
            okTop = hsl(hue, 0.85f, (l + 0.12f).coerceAtMost(0.95f)),
            okBottom = hsl(hue, 0.85f, (l - 0.12f).coerceAtLeast(0.05f)),
            onAccent = textOn(color),
        )
    }
}
