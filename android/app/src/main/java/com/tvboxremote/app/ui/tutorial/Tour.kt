package com.tvboxremote.app.ui.tutorial

/** The first-run tour (docs/UI_SPEC.md, "First-run tutorial"): pair a box, then "You are all set". */
enum class Tour { PAIR, ALL_SET }

/** The mouse walk-through, the first time the mouse screen opens: choose a way, then the Bluetooth hint. */
enum class MouseTour { CHOOSE, BLUETOOTH }

/** The screen that the tour looks at. */
enum class TourScreen { REMOTE, DEVICES, MOUSE, OTHER }

/** The coach step that the overlay shows. Each step has its own target. */
enum class TourStep(val target: String?) {
    OPEN_DEVICES(TourTargets.DEVICES),
    PICK_BOX(TourTargets.WIFI_ROWS),
    ADD_BY_IP(TourTargets.ADD_BY_IP),
    ALL_SET(null),
    MOUSE_CHOOSE(TourTargets.MOUSE_CHOICES),
    MOUSE_BLUETOOTH(TourTargets.BLUETOOTH),
}

/** The names of the coach targets that the screens mark with `Modifier.coachTarget`. */
object TourTargets {
    const val DEVICES = "devices"
    /** Each Wi-Fi row is "wifi_rows/<host>". The spotlight covers all of them. */
    const val WIFI_ROWS = "wifi_rows"
    const val ADD_BY_IP = "add_by_ip"
    /** "mouse_choices/bluetooth" and "mouse_choices/receiver". */
    const val MOUSE_CHOICES = "mouse_choices"
    const val BLUETOOTH = "bluetooth"
}

/** The rules of the tours. Plain Kotlin, so they are tested on the JVM. */
object TourFlow {
    /** With no box found for this long, the Devices step points at "Add by IP address". */
    const val EMPTY_LIST_WAIT_MS = 3_000L

    /** The tour starts on the first launch only. A phone that paired with a box before (an update) skips it. */
    fun initial(tourDone: Boolean, hasPairedBox: Boolean): Tour? = if (tourDone || hasPairedBox) null else Tour.PAIR

    /** The link to a box works: the pairing part of the tour ends with "You are all set". */
    fun onConnected(tour: Tour?): Tour? = if (tour == Tour.PAIR) Tour.ALL_SET else tour

    /**
     * The step to show now, or null. No card shows while a dialog is open (the pairing dialog guides by itself).
     * [wifiBoxes] is the number of boxes in the Wi-Fi list, [emptyForLong] is true once the list stayed empty for
     * [EMPTY_LIST_WAIT_MS].
     */
    fun step(
        tour: Tour?,
        mouseTour: MouseTour?,
        screen: TourScreen,
        wifiBoxes: Int,
        emptyForLong: Boolean,
        dialogOpen: Boolean,
    ): TourStep? {
        if (dialogOpen) return null
        when (tour) {
            Tour.PAIR -> when (screen) {
                TourScreen.REMOTE -> return TourStep.OPEN_DEVICES
                TourScreen.DEVICES -> return when {
                    wifiBoxes > 0 -> TourStep.PICK_BOX
                    emptyForLong -> TourStep.ADD_BY_IP
                    else -> null
                }
                else -> Unit
            }
            Tour.ALL_SET -> if (screen == TourScreen.REMOTE) return TourStep.ALL_SET
            null -> Unit
        }
        return when {
            mouseTour == MouseTour.CHOOSE && screen == TourScreen.MOUSE -> TourStep.MOUSE_CHOOSE
            mouseTour == MouseTour.BLUETOOTH && screen == TourScreen.DEVICES -> TourStep.MOUSE_BLUETOOTH
            else -> null
        }
    }
}
