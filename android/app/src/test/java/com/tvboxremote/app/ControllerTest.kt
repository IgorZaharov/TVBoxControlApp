package com.tvboxremote.app

import com.tvboxremote.app.bluetooth.HidReports
import com.tvboxremote.app.controller.ControlPlacement
import com.tvboxremote.app.controller.ControllerControl
import com.tvboxremote.app.controller.ControllerLayout
import com.tvboxremote.app.controller.Gamepad
import com.tvboxremote.app.controller.GamepadButton
import com.tvboxremote.app.ui.Direction
import com.tvboxremote.app.widget.SavedDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTest {
    @Test
    fun stickBelowHalfTravelHoldsNoArrow() {
        assertTrue(Gamepad.arrows(0.3f, -0.3f).isEmpty())
        assertTrue(Gamepad.arrows(0f, 0f).isEmpty())
    }

    @Test
    fun stickStraightDirectionsGiveOneArrow() {
        assertEquals(setOf(Direction.RIGHT), Gamepad.arrows(1f, 0f))
        assertEquals(setOf(Direction.LEFT), Gamepad.arrows(-0.8f, 0.1f))
        assertEquals(setOf(Direction.UP), Gamepad.arrows(0f, -0.9f)) // y is down on the screen
        assertEquals(setOf(Direction.DOWN), Gamepad.arrows(0.1f, 0.7f))
    }

    @Test
    fun stickDiagonalsGiveTwoArrows() {
        assertEquals(setOf(Direction.UP, Direction.RIGHT), Gamepad.arrows(0.6f, -0.6f))
        assertEquals(setOf(Direction.DOWN, Direction.LEFT), Gamepad.arrows(-0.6f, 0.6f))
        assertEquals(setOf(Direction.UP, Direction.LEFT), Gamepad.arrows(-0.5f, -0.5f))
        assertEquals(setOf(Direction.DOWN, Direction.RIGHT), Gamepad.arrows(0.5f, 0.5f))
    }

    @Test
    fun dpadDeadZoneIsSmallerThanStickThreshold() {
        assertEquals(setOf(Direction.UP), Gamepad.arrows(0f, -0.3f, Gamepad.DPAD_DEAD_ZONE))
        assertTrue(Gamepad.arrows(0f, -0.1f, Gamepad.DPAD_DEAD_ZONE).isEmpty())
    }

    @Test
    fun hatValuesGoClockwiseFromUp() {
        assertEquals(0, Gamepad.hat(setOf(Direction.UP)))
        assertEquals(1, Gamepad.hat(setOf(Direction.UP, Direction.RIGHT)))
        assertEquals(2, Gamepad.hat(setOf(Direction.RIGHT)))
        assertEquals(4, Gamepad.hat(setOf(Direction.DOWN)))
        assertEquals(5, Gamepad.hat(setOf(Direction.DOWN, Direction.LEFT)))
        assertEquals(6, Gamepad.hat(setOf(Direction.LEFT)))
        assertEquals(7, Gamepad.hat(setOf(Direction.UP, Direction.LEFT)))
        assertEquals(Gamepad.HAT_NONE, Gamepad.hat(emptySet()))
        assertEquals(Gamepad.HAT_NONE, Gamepad.hat(setOf(Direction.UP, Direction.DOWN)))
    }

    @Test
    fun axisIsClampedToSigned8Bits() {
        assertEquals(127, Gamepad.axis(1f))
        assertEquals(-127, Gamepad.axis(-2f))
        assertEquals(0, Gamepad.axis(0f))
        assertEquals(64, Gamepad.axis(0.5f))
    }

    @Test
    fun buttonMasksFollowTheSpecNumbers() {
        assertEquals(1, GamepadButton.A.hidMask)
        assertEquals(1 shl 3, GamepadButton.X.hidMask)
        assertEquals(1 shl 11, GamepadButton.START.hidMask)
        assertEquals(1 shl 14, GamepadButton.R3.hidMask)
        // All buttons fit in the 16-bit field, and no two share a bit.
        val masks = GamepadButton.entries.map { it.hidMask }
        assertEquals(masks.size, masks.toSet().size)
        assertTrue(masks.all { it in 1..0xFFFF })
    }

    @Test
    fun gamepadReportHasButtonsHatAndAxes() {
        val report = HidReports.gamepad(GamepadButton.A.hidMask or GamepadButton.START.hidMask, 2, 127, -127, 0, 5)
        assertArrayEquals(byteArrayOf(0x01, 0x08, 0x02, 127, -127, 0, 5), report)
        assertEquals(HidReports.reportSize(HidReports.ID_GAMEPAD), report.size)
    }

    @Test
    fun layoutClampKeepsControlsOnScreen() {
        // A 132 dp stick on an 800 x 360 dp screen, pushed past the left edge and the bottom.
        val p = ControllerLayout.clamp(ControllerControl.LEFT_STICK, ControlPlacement(-0.2f, 1.3f, 1f), 800f, 360f)
        assertEquals(66f / 800f, p.x, 1e-4f)
        assertEquals(1f - 66f / 360f, p.y, 1e-4f)
    }

    @Test
    fun layoutClampLimitsTheSizeFactor() {
        val big = ControllerLayout.clamp(ControllerControl.FACE, ControlPlacement(0.5f, 0.5f, 3f), 800f, 360f)
        assertEquals(ControllerLayout.MAX_SCALE, big.scale, 0f)
        val small = ControllerLayout.clamp(ControllerControl.FACE, ControlPlacement(0.5f, 0.5f, 0.1f), 800f, 360f)
        assertEquals(ControllerLayout.MIN_SCALE, small.scale, 0f)
    }

    @Test
    fun layoutClampCentersAControlLargerThanTheScreen() {
        val p = ControllerLayout.clamp(ControllerControl.FACE, ControlPlacement(0.1f, 0.9f, 1.6f), 200f, 200f)
        assertEquals(0.5f, p.x, 0f)
        assertEquals(0.5f, p.y, 0f)
    }

    @Test
    fun defaultLayoutFitsAPhoneInLandscape() {
        ControllerControl.entries.forEach { c ->
            val p = ControllerLayout.DEFAULT[c]
            assertEquals(c.name, p, ControllerLayout.clamp(c, p, 780f, 360f))
        }
    }

    @Test
    fun layoutSurvivesEncodeAndDecode() {
        val layout = ControllerLayout.DEFAULT
            .with(ControllerControl.DPAD, ControlPlacement(0.25f, 0.7f, 1.2f))
            .with(ControllerControl.R2, ControlPlacement(0.95f, 0.05f, 0.6f))
        val decoded = ControllerLayout.decode(layout.encode())
        ControllerControl.entries.forEach { c ->
            assertEquals(layout[c].x, decoded[c].x, 1e-4f)
            assertEquals(layout[c].y, decoded[c].y, 1e-4f)
            assertEquals(layout[c].scale, decoded[c].scale, 1e-3f)
        }
    }

    @Test
    fun brokenLayoutEntriesKeepTheirDefaults() {
        val decoded = ControllerLayout.decode("dpad:0.2,oops,1;unknown:1,1,1;r1:0.5,0.5,1.5")
        assertEquals(ControllerControl.DPAD.default, decoded[ControllerControl.DPAD])
        assertEquals(ControlPlacement(0.5f, 0.5f, 1.5f), decoded[ControllerControl.R1])
        assertEquals(ControllerLayout.DEFAULT, ControllerLayout.decode(null))
    }

    @Test
    fun savedDevicesKeepTheMostRecentFirst() {
        val tv = SavedDevice.wifi("192.168.1.20", 6466, "Living room")
        val box = SavedDevice.bluetooth("AA:BB:CC:DD:EE:FF", "Bedroom box")
        var list = SavedDevice.remember(emptyList(), tv)
        list = SavedDevice.remember(list, box)
        list = SavedDevice.remember(list, tv)
        assertEquals(listOf(tv, box), list)
        assertEquals(list, SavedDevice.decode(SavedDevice.encode(list)))
    }

    @Test
    fun savedDeviceParsesHostPortAndAddress() {
        val v6 = SavedDevice.wifi("fe80::1", 6466, "TV")
        assertEquals("fe80::1", v6.host)
        assertEquals(6466, v6.port)
        assertEquals("AA:BB:CC:DD:EE:FF", SavedDevice.bluetooth("AA:BB:CC:DD:EE:FF", "Box").address)
        assertEquals(null, SavedDevice.bluetooth("AA:BB", "Box").host)
    }

    @Test
    fun savedDeviceNamesCannotBreakTheFormat() {
        val odd = SavedDevice.wifi("10.0.0.2", 6466, "Name\twith\ntabs")
        val decoded = SavedDevice.decode(SavedDevice.encode(listOf(odd)))
        assertEquals(1, decoded.size)
        assertEquals("Name with tabs", decoded[0].name)
    }
}
