package com.tvboxremote.app.ui

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** The theme choice in Preferences. Default: dark. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** The accent choice in Preferences. Default: blue. */
enum class AccentChoice { BLUE, GREEN, ORANGE, RED, RAINBOW }

/** The theme tokens of docs/UI_SPEC.md ("Themes and accents"), without the accent. */
data class ThemeColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfacePressed: Color,
    val onSurface: Color,
    val bodyText: Color,
    val muted: Color,
    val power: Color,
    val ok: Color,
    val warn: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val bodyTop: Color,
    val bodyBottom: Color,
    val bodyPressedTop: Color,
    val bodyPressedBottom: Color,
    val shadow: Color,
    val rim: Color,
    val sheen: Color,
    val wellCenter: Color,
    val wellEdge: Color,
) {
    companion object {
        val Dark = ThemeColors(
            isDark = true,
            background = Color(0xFF0E0F12), surface = Color(0xFF1C1E24), surfacePressed = Color(0xFF2A2D35),
            onSurface = Color(0xFFE8EAF0), bodyText = Color(0xFFC9CCD4), muted = Color(0xFF8A8F9C),
            power = Color(0xFFE5484D), ok = Color(0xFF30A46C), warn = Color(0xFFF5A524),
            backgroundTop = Color(0xFF16181D), backgroundBottom = Color(0xFF0A0B0E),
            bodyTop = Color(0xFF272A33), bodyBottom = Color(0xFF17191E),
            bodyPressedTop = Color(0xFF17191E), bodyPressedBottom = Color(0xFF22252D),
            shadow = Color.Black.copy(alpha = 0.5f), rim = Color.White.copy(alpha = 0.12f), sheen = Color.White.copy(alpha = 0.08f),
            wellCenter = Color(0xFF07080A), wellEdge = Color(0xFF1B1D23),
        )
        val Light = ThemeColors(
            isDark = false,
            background = Color(0xFFF2F3F5), surface = Color(0xFFFFFFFF), surfacePressed = Color(0xFFE4E6EA),
            onSurface = Color(0xFF14161B), bodyText = Color(0xFF2A2E36), muted = Color(0xFF565C68),
            power = Color(0xFFD9363C), ok = Color(0xFF218A57), warn = Color(0xFFB7730A),
            backgroundTop = Color(0xFFF8F9FA), backgroundBottom = Color(0xFFE6E8EC),
            bodyTop = Color(0xFFFFFFFF), bodyBottom = Color(0xFFE9EBEF),
            bodyPressedTop = Color(0xFFE1E3E8), bodyPressedBottom = Color(0xFFF1F2F4),
            shadow = Color.Black.copy(alpha = 0.16f), rim = Color.White.copy(alpha = 0.9f), sheen = Color.White.copy(alpha = 0.45f),
            wellCenter = Color(0xFFD5D8DE), wellEdge = Color(0xFFEEF0F3),
        )
    }
}

/** The accent colors for one theme: the accent, the OK button gradient, and the text on the OK button. */
data class AccentColors(val accent: Color, val okTop: Color, val okBottom: Color, val onAccent: Color) {
    companion object {
        private fun of(accent: Long, top: Long, bottom: Long, on: Color) = AccentColors(Color(accent), Color(top), Color(bottom), on)
        private val DarkText = Color(0xFF14161B)

        fun of(choice: AccentChoice, dark: Boolean): AccentColors = when (choice) {
            AccentChoice.BLUE -> if (dark) of(0xFF4F8CFF, 0xFF6FA2FF, 0xFF3A74E8, Color.White) else of(0xFF2563EB, 0xFF4A7FF0, 0xFF1D4FC4, Color.White)
            AccentChoice.GREEN -> if (dark) of(0xFF3DBE7E, 0xFF5BD495, 0xFF24965F, DarkText) else of(0xFF127A46, 0xFF1E9A5A, 0xFF0F6B3D, Color.White)
            AccentChoice.ORANGE -> if (dark) of(0xFFFF9A3D, 0xFFFFB15E, 0xFFF07F1A, DarkText) else of(0xFFA84C0A, 0xFFD9691A, 0xFFA64708, Color.White)
            AccentChoice.RED -> if (dark) of(0xFFFF5A5F, 0xFFFF7A7E, 0xFFE0383E, Color.White) else of(0xFFC9272D, 0xFFE0383E, 0xFFA81E23, Color.White)
            AccentChoice.RAINBOW -> rainbow(220f, dark)
        }

        /** The RGB accent at [hue] for the theme. */
        fun rainbow(hue: Float, dark: Boolean): AccentColors {
            val bg = if (dark) ThemeColors.Dark.background else ThemeColors.Light.background
            val rgb = ThemeMath.rainbow(hue, dark, (bg.value shr 32).toInt() and 0xFFFFFF)
            fun c(v: Int) = Color(0xFF000000 or v.toLong())
            return AccentColors(c(rgb.accent), c(rgb.okTop), c(rgb.okBottom), c(rgb.onAccent))
        }
    }
}

/**
 * The current colors. The theme tokens and the accent are two separate states: the rainbow accent changes
 * about 20 times per second, and then only what reads the accent redraws.
 * [RemoteTheme] sets both.
 */
object Palette {
    internal var theme by mutableStateOf(ThemeColors.Dark)
    internal var accentColors by mutableStateOf(AccentColors.of(AccentChoice.BLUE, true))

    val isDark: Boolean get() = theme.isDark
    val background: Color get() = theme.background
    val surface: Color get() = theme.surface
    val surfacePressed: Color get() = theme.surfacePressed
    val onSurface: Color get() = theme.onSurface
    val bodyText: Color get() = theme.bodyText
    val muted: Color get() = theme.muted
    val power: Color get() = theme.power
    val ok: Color get() = theme.ok
    val warn: Color get() = theme.warn

    // Remote screen background (UI_SPEC "Background texture")
    val backgroundTop: Color get() = theme.backgroundTop
    val backgroundBottom: Color get() = theme.backgroundBottom

    // Raised buttons (UI_SPEC "Raised buttons")
    val bodyTop: Color get() = theme.bodyTop
    val bodyBottom: Color get() = theme.bodyBottom
    val bodyPressedTop: Color get() = theme.bodyPressedTop
    val bodyPressedBottom: Color get() = theme.bodyPressedBottom
    val shadow: Color get() = theme.shadow
    val rim: Color get() = theme.rim
    val sheen: Color get() = theme.sheen
    val wellCenter: Color get() = theme.wellCenter
    val wellEdge: Color get() = theme.wellEdge

    // Accent
    val accent: Color get() = accentColors.accent
    val okTop: Color get() = accentColors.okTop
    val okBottom: Color get() = accentColors.okBottom
    val onAccent: Color get() = accentColors.onAccent

    /** The accent for a lit arrow: lighter on the dark theme, darker on the light theme. */
    val accentLit: Color get() = if (theme.isDark) lerp(accent, Color.White, 0.4f) else lerp(accent, Color.Black, 0.15f)

    // Face buttons of the controller mode: fixed colors, the same in both themes.
    val faceA = Color(0xFF30A46C)
    val faceB = Color(0xFFE5484D)
    val faceX = Color(0xFF4F8CFF)
    val faceY = Color(0xFFF5A524)
}

object Sizes {
    val roundButton = 72.dp
    val icon = 30.dp
    val dpad = 264.dp
    val okButton = 104.dp
}

/**
 * Motion tokens from docs/UI_SPEC.md ("Motion"), taken from the Animate UI tokens.
 * Compose damping ratio = damping / (2 * sqrt(stiffness)), with mass 1.
 */
object Motion {
    /** Ease-out curve for things that leave or travel. */
    val easeOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** A sine ease in and out, for the slow D-pad glow. */
    val sine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    fun <T> press(reduce: Boolean = false): FiniteAnimationSpec<T> =
        if (reduce) snap() else spring(dampingRatio = 0.64f, stiffness = 550f)

    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.72f, stiffness = 400f)

    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(260, easing = easeOut)

    fun <T> pulse(): FiniteAnimationSpec<T> = tween(450, easing = easeOut)

    fun <T> highlight(): AnimationSpec<T> = spring(dampingRatio = 0.89f, stiffness = 500f)
}

/**
 * True if the system asks for less motion ("Remove animations": animator duration scale 0).
 * Compose then ends all animations at once, so motion that must still show (a flash) uses delays, not tweens.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** One full RGB cycle takes this long. */
private const val RAINBOW_CYCLE_MS = 8_000L

/** The RGB accent stays at this hue under reduced motion. */
private const val RAINBOW_REST_HUE = 220f

@Composable
fun RemoteTheme(mode: ThemeMode = ThemeMode.DARK, accent: AccentChoice = AccentChoice.BLUE, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val reduce = rememberReducedMotion()
    var hue by remember { mutableFloatStateOf(RAINBOW_REST_HUE) }
    if (accent == AccentChoice.RAINBOW && !reduce) {
        // About 20 updates per second, only while the app is visible.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val start = android.os.SystemClock.uptimeMillis() - (hue / 360f * RAINBOW_CYCLE_MS).toLong()
                while (true) {
                    val t = (android.os.SystemClock.uptimeMillis() - start) % RAINBOW_CYCLE_MS
                    hue = t * 360f / RAINBOW_CYCLE_MS
                    delay(50)
                }
            }
        }
    }
    val theme = if (dark) ThemeColors.Dark else ThemeColors.Light
    val accentColors = when (accent) {
        AccentChoice.RAINBOW -> AccentColors.rainbow(if (reduce) RAINBOW_REST_HUE else hue, dark)
        else -> AccentColors.of(accent, dark)
    }
    // RemoteTheme is the root, so nothing has read these states yet in this composition.
    if (Palette.theme != theme) Palette.theme = theme
    if (Palette.accentColors != accentColors) Palette.accentColors = accentColors

    val scheme = if (dark) {
        darkColorScheme(
            primary = accentColors.accent, onPrimary = accentColors.onAccent,
            background = theme.background, onBackground = theme.onSurface,
            surface = theme.background, onSurface = theme.onSurface,
            surfaceVariant = theme.surface, onSurfaceVariant = theme.muted,
            surfaceContainer = theme.surface, surfaceContainerHigh = theme.surface,
            surfaceContainerHighest = theme.surfacePressed, surfaceContainerLow = theme.surface,
            outline = theme.muted, error = theme.power,
        )
    } else {
        lightColorScheme(
            primary = accentColors.accent, onPrimary = accentColors.onAccent,
            background = theme.background, onBackground = theme.onSurface,
            surface = theme.background, onSurface = theme.onSurface,
            surfaceVariant = theme.surface, onSurfaceVariant = theme.muted,
            surfaceContainer = theme.surface, surfaceContainerHigh = theme.surface,
            surfaceContainerHighest = theme.surfacePressed, surfaceContainerLow = theme.surface,
            outline = theme.muted, error = theme.power,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
