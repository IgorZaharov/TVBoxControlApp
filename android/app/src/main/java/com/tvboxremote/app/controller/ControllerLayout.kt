package com.tvboxremote.app.controller

import java.util.Locale

/**
 * The movable controls of the controller mode, with their size at 100% (dp) and their default center
 * as a fraction of the screen width and height (docs/UI_SPEC.md, "Default layout").
 * The Exit button is not in this list: it stays at the top center, so it is always easy to find.
 */
enum class ControllerControl(
    val key: String,
    val widthDp: Float,
    val heightDp: Float,
    val defaultX: Float,
    val defaultY: Float,
) {
    L2("l2", 96f, 44f, 0.10f, 0.10f),
    L1("l1", 96f, 44f, 0.10f, 0.24f),
    R2("r2", 96f, 44f, 0.90f, 0.10f),
    R1("r1", 96f, 44f, 0.90f, 0.24f),
    SELECT("select", 76f, 36f, 0.38f, 0.10f),
    START("start", 76f, 36f, 0.62f, 0.10f),
    LEFT_STICK("lstick", 132f, 132f, 0.18f, 0.56f),
    DPAD("dpad", 140f, 140f, 0.34f, 0.80f),
    FACE("face", 176f, 176f, 0.83f, 0.56f),
    RIGHT_STICK("rstick", 132f, 132f, 0.64f, 0.80f);

    val default: ControlPlacement get() = ControlPlacement(defaultX, defaultY, 1f)
}

/** Where one control is: its center as a fraction of the screen (0 to 1) and its size factor. */
data class ControlPlacement(val x: Float, val y: Float, val scale: Float)

/** The positions and sizes of all controls. The layout is the same for all devices. */
data class ControllerLayout(private val placements: Map<ControllerControl, ControlPlacement> = emptyMap()) {

    operator fun get(control: ControllerControl): ControlPlacement = placements[control] ?: control.default

    fun with(control: ControllerControl, placement: ControlPlacement): ControllerLayout =
        ControllerLayout(placements + (control to placement))

    /** A short text form for storage: "key:x,y,scale" per control, separated by ";". */
    fun encode(): String = ControllerControl.entries.joinToString(";") { c ->
        val p = get(c)
        String.format(Locale.ROOT, "%s:%.4f,%.4f,%.3f", c.key, p.x, p.y, p.scale)
    }

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.6f

        val DEFAULT = ControllerLayout()

        /** Reads [encode] output. Unknown or broken entries are skipped, so those controls keep their defaults. */
        fun decode(text: String?): ControllerLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val byKey = ControllerControl.entries.associateBy { it.key }
            val placements = text.split(';').mapNotNull { entry ->
                val control = byKey[entry.substringBefore(':', "")] ?: return@mapNotNull null
                val numbers = entry.substringAfter(':', "").split(',').map { it.toFloatOrNull() }
                if (numbers.size != 3 || numbers.any { it == null || it.isNaN() }) return@mapNotNull null
                control to ControlPlacement(numbers[0]!!, numbers[1]!!, numbers[2]!!)
            }.toMap()
            return ControllerLayout(placements)
        }

        /**
         * Keeps [placement] of [control] inside a screen of [screenWidth] x [screenHeight] (any unit, the same as
         * the control sizes): the size factor stays in [MIN_SCALE]..[MAX_SCALE], and the control does not go past
         * a screen edge. A control larger than the screen is centered.
         */
        fun clamp(control: ControllerControl, placement: ControlPlacement, screenWidth: Float, screenHeight: Float): ControlPlacement {
            val scale = placement.scale.coerceIn(MIN_SCALE, MAX_SCALE)
            fun fit(center: Float, size: Float, screen: Float): Float {
                if (screen <= 0f) return center.coerceIn(0f, 1f)
                val half = size * scale / 2f / screen
                return if (half >= 0.5f) 0.5f else center.coerceIn(half, 1f - half)
            }
            return ControlPlacement(
                x = fit(placement.x, control.widthDp, screenWidth),
                y = fit(placement.y, control.heightDp, screenHeight),
                scale = scale,
            )
        }
    }
}
