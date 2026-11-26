package com.tvboxremote.app

import com.tvboxremote.app.ui.tutorial.MouseTour
import com.tvboxremote.app.ui.tutorial.Tour
import com.tvboxremote.app.ui.tutorial.TourFlow
import com.tvboxremote.app.ui.tutorial.TourScreen
import com.tvboxremote.app.ui.tutorial.TourStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TourFlowTest {
    private fun step(
        tour: Tour?,
        screen: TourScreen,
        boxes: Int = 0,
        emptyForLong: Boolean = false,
        dialog: Boolean = false,
        mouse: MouseTour? = null,
    ) = TourFlow.step(tour, mouse, screen, boxes, emptyForLong, dialog)

    @Test
    fun startsOnlyOnTheFirstLaunch() {
        assertEquals(Tour.PAIR, TourFlow.initial(tourDone = false, hasPairedBox = false))
        assertNull(TourFlow.initial(tourDone = true, hasPairedBox = false))
        // An update on a phone that paired before: no tour.
        assertNull(TourFlow.initial(tourDone = false, hasPairedBox = true))
    }

    @Test
    fun pairStepsFollowTheScreens() {
        assertEquals(TourStep.OPEN_DEVICES, step(Tour.PAIR, TourScreen.REMOTE))
        assertEquals(TourStep.PICK_BOX, step(Tour.PAIR, TourScreen.DEVICES, boxes = 2))
        // An empty list waits first, then points at "Add by IP address".
        assertNull(step(Tour.PAIR, TourScreen.DEVICES, boxes = 0, emptyForLong = false))
        assertEquals(TourStep.ADD_BY_IP, step(Tour.PAIR, TourScreen.DEVICES, boxes = 0, emptyForLong = true))
        // A box that shows up later wins over "Add by IP address".
        assertEquals(TourStep.PICK_BOX, step(Tour.PAIR, TourScreen.DEVICES, boxes = 1, emptyForLong = true))
    }

    @Test
    fun noCardWhileTheDialogIsOpen() {
        assertNull(step(Tour.PAIR, TourScreen.DEVICES, boxes = 1, dialog = true))
        assertNull(step(Tour.ALL_SET, TourScreen.REMOTE, dialog = true))
    }

    @Test
    fun connectingEndsWithAllSet() {
        assertEquals(Tour.ALL_SET, TourFlow.onConnected(Tour.PAIR))
        assertNull(TourFlow.onConnected(null))
        assertEquals(TourStep.ALL_SET, step(Tour.ALL_SET, TourScreen.REMOTE))
        assertNull(step(Tour.ALL_SET, TourScreen.DEVICES))
    }

    @Test
    fun mouseWalkThrough() {
        assertEquals(TourStep.MOUSE_CHOOSE, step(null, TourScreen.MOUSE, mouse = MouseTour.CHOOSE))
        assertEquals(TourStep.MOUSE_BLUETOOTH, step(null, TourScreen.DEVICES, mouse = MouseTour.BLUETOOTH))
        assertNull(step(null, TourScreen.REMOTE, mouse = MouseTour.CHOOSE))
        // The first-run tour comes first.
        assertEquals(TourStep.OPEN_DEVICES, step(Tour.PAIR, TourScreen.REMOTE, mouse = MouseTour.CHOOSE))
    }
}
