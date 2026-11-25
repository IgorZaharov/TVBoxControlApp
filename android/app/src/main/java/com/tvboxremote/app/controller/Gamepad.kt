package com.tvboxremote.app.controller

import com.tvboxremote.app.ui.Direction
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The buttons of the controller mode (docs/UI_SPEC.md, "What each transport sends").
 * [atvCode] is the Android key code for Wi-Fi. [hidButton] is the button number in the Bluetooth gamepad report.
 */
enum class GamepadButton(val atvCode: Int, val hidButton: Int) {
    A(96, 1),
    B(97, 2),
    X(99, 4),
    Y(100, 5),
    L1(102, 7),
    R1(103, 8),
    L2(104, 9),
    R2(105, 10),
    SELECT(109, 11),
    START(108, 12),
    L3(106, 14),
    R3(107, 15);

    /** The bit of this button in the 16-bit button field of the gamepad report. */
    val hidMask: Int get() = 1 shl (hidButton - 1)
}

enum class Stick { LEFT, RIGHT }

object Gamepad {
    /** The hat switch value for "no direction". */
    const val HAT_NONE = 8

    /** A stick acts as a D-pad past this part of its travel (Wi-Fi). */
    const val STICK_ARROW_THRESHOLD = 0.5f

    /** The controller D-pad ignores touches nearer to its center than this part of its radius. */
    const val DPAD_DEAD_ZONE = 0.2f

    /**
     * The arrows for a stick or D-pad position. [x] and [y] go from -1 to 1, with y down (screen coordinates).
     * Below [threshold] there is no arrow. Otherwise the position falls in one of 8 directions,
     * so the result has one arrow or two (a diagonal).
     */
    fun arrows(x: Float, y: Float, threshold: Float = STICK_ARROW_THRESHOLD): Set<Direction> {
        if (hypot(x, y) < threshold) return emptySet()
        // Sector 0 is right, then clockwise on the screen: 2 is down, 4 is left, 6 is up.
        val angle = atan2(y, x)
        val sector = ((angle / (PI / 4)).roundToInt() + 8) % 8
        return when (sector) {
            0 -> setOf(Direction.RIGHT)
            1 -> setOf(Direction.DOWN, Direction.RIGHT)
            2 -> setOf(Direction.DOWN)
            3 -> setOf(Direction.DOWN, Direction.LEFT)
            4 -> setOf(Direction.LEFT)
            5 -> setOf(Direction.UP, Direction.LEFT)
            6 -> setOf(Direction.UP)
            else -> setOf(Direction.UP, Direction.RIGHT)
        }
    }

    /** The hat switch value for a set of arrows: 0 is up, then clockwise in steps of 45 degrees. */
    fun hat(arrows: Set<Direction>): Int {
        val up = Direction.UP in arrows && Direction.DOWN !in arrows
        val down = Direction.DOWN in arrows && Direction.UP !in arrows
        val left = Direction.LEFT in arrows && Direction.RIGHT !in arrows
        val right = Direction.RIGHT in arrows && Direction.LEFT !in arrows
        return when {
            up && right -> 1
            down && right -> 3
            down && left -> 5
            up && left -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> HAT_NONE
        }
    }

    /** A stick axis from -1..1 to the signed 8-bit report value. */
    fun axis(value: Float): Int = (value * 127f).roundToInt().coerceIn(-127, 127)
}
