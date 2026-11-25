package com.tvboxremote.app

import com.tvboxremote.app.bluetooth.HidReports
import com.tvboxremote.app.bluetooth.KeyUsage
import com.tvboxremote.app.motion.GyroMapping
import com.tvboxremote.app.motion.PixelAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTest {
    @Test
    fun gyroTurnLeftMovesCursorLeft() {
        // Positive z rotation turns the phone to the left (counterclockwise seen from above).
        val (dx, dy) = GyroMapping.delta(gx = 0f, gz = 1f, dtSeconds = 0.01f, speed = 1f)
        assertTrue(dx < 0)
        assertEquals(0f, dy, 0f)
        assertEquals(-9f, dx, 0.001f)
    }

    @Test
    fun gyroTiltUpMovesCursorUp() {
        val (dx, dy) = GyroMapping.delta(gx = 0.5f, gz = 0f, dtSeconds = 0.01f, speed = 2f)
        assertEquals(0f, dx, 0f)
        assertEquals(-9f, dy, 0.001f)
    }

    @Test
    fun gyroDeadZoneIgnoresNoise() {
        val (dx, dy) = GyroMapping.delta(gx = 0.01f, gz = -0.02f, dtSeconds = 0.01f, speed = 3f)
        assertEquals(0f, dx, 0f)
        assertEquals(0f, dy, 0f)
    }

    @Test
    fun accumulatorKeepsFractions() {
        val acc = PixelAccumulator()
        var total = 0
        repeat(12) { total += acc.add(0.25f, 0f).first }
        assertEquals(3, total)
        val (x, y) = acc.add(-1.5f, 2.7f)
        assertEquals(-1, x)
        assertEquals(2, y)
    }

    @Test
    fun keyMapCoversUsKeyboard() {
        assertEquals(KeyUsage.Stroke(0x04, false), KeyUsage.forChar('a'))
        assertEquals(KeyUsage.Stroke(0x1D, true), KeyUsage.forChar('Z'))
        assertEquals(KeyUsage.Stroke(0x27, false), KeyUsage.forChar('0'))
        assertEquals(KeyUsage.Stroke(0x1E, true), KeyUsage.forChar('!'))
        assertEquals(KeyUsage.Stroke(0x27, true), KeyUsage.forChar(')'))
        assertEquals(KeyUsage.Stroke(0x38, true), KeyUsage.forChar('?'))
        assertEquals(KeyUsage.Stroke(0x34, false), KeyUsage.forChar('\''))
        assertNull(KeyUsage.forChar('é'))
        // Every printable ASCII character has a key.
        for (c in ' '..'~') assertTrue("no key for '$c'", KeyUsage.forChar(c) != null)
    }

    @Test
    fun mouseReportClampsToSignedByte() {
        val r = HidReports.mouse(1, 500, -500, 0)
        assertEquals(1, r[0].toInt())
        assertEquals(127, r[1].toInt())
        assertEquals(-127, r[2].toInt())
    }

    @Test
    fun consumerReportIsLittleEndian() {
        val r = HidReports.consumer(0x0223)
        assertEquals(0x23, r[0].toInt() and 0xFF)
        assertEquals(0x02, r[1].toInt() and 0xFF)
    }
}
