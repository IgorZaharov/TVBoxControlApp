package com.tvboxremote.app

import com.tvboxremote.app.ui.Flash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The press flash of docs/UI_SPEC.md, "Press glow". */
class FlashTest {
    @Test
    fun isOffBeforeAndAfterItsRun() {
        assertEquals(Flash.None, Flash.at(0f, expands = true))
        assertEquals(Flash.None, Flash.at(1f, expands = true))
    }

    @Test
    fun reachesFullLightAt15PercentThenFades() {
        assertEquals(1f, Flash.at(0.15f, expands = true).light, 0.0001f)
        assertEquals(0.5f, Flash.at(0.075f, expands = true).light, 0.0001f)
        val late = Flash.at(0.9f, expands = true).light
        assertTrue(late in 0f..0.1f)
    }

    @Test
    fun growsOnlyWithMotion() {
        assertTrue(Flash.at(0.5f, expands = true).spread > 0.7f)
        assertEquals(0f, Flash.at(0.5f, expands = false).spread, 0f)
    }
}
