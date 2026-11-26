package com.tvboxremote.app

import com.tvboxremote.app.data.DeviceOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOrderTest {
    @Test
    fun recentDevicesComeFirstThenPairedThenOthers() {
        val found = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5")
        val recent = listOf("10.0.0.4", "10.0.0.2")
        val paired = setOf("10.0.0.5")
        val order = DeviceOrder.knownFirst(found, recent, key = { it }, paired = { it in paired })
        assertEquals(listOf("10.0.0.4", "10.0.0.2", "10.0.0.5", "10.0.0.1", "10.0.0.3"), order)
    }

    @Test
    fun knownMeansRecentOrPaired() {
        val recent = listOf("a")
        assertTrue(DeviceOrder.isKnown("a", recent))
        assertTrue(DeviceOrder.isKnown("b", recent, paired = true))
        assertFalse(DeviceOrder.isKnown("b", recent))
    }
}
