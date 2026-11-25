package com.tvboxremote.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tvboxremote.app.ui.AccentChoice
import com.tvboxremote.app.ui.AccentColors
import com.tvboxremote.app.ui.ThemeColors
import com.tvboxremote.app.ui.ThemeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contrast rules of docs/UI_SPEC.md, "Themes and accents" > "Contrast". */
class ThemeTest {
    private fun rgb(c: Color) = c.toArgb() and 0xFFFFFF
    private fun mid(a: Color, b: Color): Int {
        val x = rgb(a)
        val y = rgb(b)
        fun ch(shift: Int) = (((x shr shift) and 0xFF) + ((y shr shift) and 0xFF)) / 2
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val themes = listOf(true to ThemeColors.Dark, false to ThemeColors.Light)

    @Test
    fun contrastMatchesKnownValues() {
        assertEquals(21.0, ThemeMath.contrast(0xFFFFFF, 0x000000), 0.01)
        assertEquals(1.0, ThemeMath.contrast(0x777777, 0x777777), 0.001)
    }

    @Test
    fun textColorsPassOnEveryBackground() {
        themes.forEach { (_, t) ->
            listOf(t.onSurface, t.bodyText, t.muted).forEach { text ->
                listOf(t.background, t.surface, t.bodyBottom).forEach { bg ->
                    val ratio = ThemeMath.contrast(rgb(text), rgb(bg))
                    assertTrue("text ${rgb(text).toString(16)} on ${rgb(bg).toString(16)}: $ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun statusDotsAndPowerIconPassThreeToOne() {
        themes.forEach { (_, t) ->
            listOf(t.ok, t.warn, t.power).forEach { c ->
                val ratio = ThemeMath.contrast(rgb(c), rgb(t.background))
                assertTrue("${rgb(c).toString(16)}: $ratio", ratio >= 3.0)
            }
        }
    }

    @Test
    fun everyAccentPassesAsTextAndOnTheOkButton() {
        themes.forEach { (dark, t) ->
            AccentChoice.entries.forEach { choice ->
                val a = AccentColors.of(choice, dark)
                val asText = ThemeMath.contrast(rgb(a.accent), rgb(t.background))
                assertTrue("$choice dark=$dark accent as text: $asText", asText >= 4.5)
                // The bold 22 pt OK label is large text: 3:1 on the middle of the gradient.
                val label = ThemeMath.contrast(rgb(a.onAccent), mid(a.okTop, a.okBottom))
                assertTrue("$choice dark=$dark OK label: $label", label >= 3.0)
                if (!dark) assertTrue("$choice light OK label: $label", label >= 4.5)
            }
        }
    }

    @Test
    fun rainbowPassesAtEveryHue() {
        themes.forEach { (dark, t) ->
            for (hue in 0 until 360 step 3) {
                val a = AccentColors.rainbow(hue.toFloat(), dark)
                val asText = ThemeMath.contrast(rgb(a.accent), rgb(t.background))
                assertTrue("hue $hue dark=$dark accent: $asText", asText >= 4.5)
                val label = ThemeMath.contrast(rgb(a.onAccent), mid(a.okTop, a.okBottom))
                assertTrue("hue $hue dark=$dark OK label: $label", label >= 3.0)
            }
        }
    }

    @Test
    fun hslGivesThePrimaryColors() {
        assertEquals(0xFF0000, ThemeMath.hsl(0f, 1f, 0.5f))
        assertEquals(0x00FF00, ThemeMath.hsl(120f, 1f, 0.5f))
        assertEquals(0x0000FF, ThemeMath.hsl(240f, 1f, 0.5f))
        assertEquals(0xFF0000, ThemeMath.hsl(360f, 1f, 0.5f))
    }
}
