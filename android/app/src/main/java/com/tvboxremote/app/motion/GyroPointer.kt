package com.tvboxremote.app.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Converts phone rotation to cursor movement. The phone points at the TV with
 * the screen up: turning left or right (axis z) moves the cursor sideways, and
 * tilting up or down (axis x) moves it vertically.
 */
object GyroMapping {
    /** Pixels for one radian of rotation at speed 1.0. */
    const val PIXELS_PER_RADIAN = 900f
    /** Rotation below this rate (rad/s) is noise. */
    const val DEAD_ZONE = 0.03f

    fun delta(gx: Float, gz: Float, dtSeconds: Float, speed: Float): Pair<Float, Float> {
        val rx = if (abs(gz) < DEAD_ZONE) 0f else gz
        val ry = if (abs(gx) < DEAD_ZONE) 0f else gx
        val k = PIXELS_PER_RADIAN * speed * dtSeconds
        return Pair(-rx * k, -ry * k)
    }
}

/** Keeps the fractions of a pixel, so slow movement still moves the cursor. */
class PixelAccumulator {
    private var fx = 0f
    private var fy = 0f

    /** Adds a movement and returns the whole pixels to send now. */
    fun add(dx: Float, dy: Float): Pair<Int, Int> {
        fx += dx
        fy += dy
        val ix = fx.toInt()
        val iy = fy.toInt()
        fx -= ix
        fy -= iy
        return Pair(ix, iy)
    }

    fun reset() { fx = 0f; fy = 0f }
}

/** Reads the gyroscope and reports cursor movement in pixels. */
class GyroPointer(context: Context, private val onMove: (dx: Float, dy: Float) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var lastTimestamp = 0L

    @Volatile var speed: Float = 1f

    val available: Boolean get() = gyro != null

    fun start() {
        val g = gyro ?: return
        lastTimestamp = 0L
        // 10 ms period (100 Hz). This stays below the 200 Hz limit that needs an extra permission.
        sensors.registerListener(this, g, 10_000)
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp
        if (lastTimestamp == 0L) { lastTimestamp = t; return }
        val dt = ((t - lastTimestamp) / 1e9f).coerceIn(0f, 0.05f)
        lastTimestamp = t
        val (dx, dy) = GyroMapping.delta(gx = event.values[0], gz = event.values[2], dtSeconds = dt, speed = speed)
        if (dx != 0f || dy != 0f) onMove(dx, dy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
