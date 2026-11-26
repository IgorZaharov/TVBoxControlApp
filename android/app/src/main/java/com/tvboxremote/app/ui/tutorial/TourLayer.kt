package com.tvboxremote.app.ui.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.R
import com.tvboxremote.app.ui.Palette
import kotlinx.coroutines.delay

/**
 * The tour cards over the app. [screen] is the screen that shows now. The layer finds the step with [TourFlow] and
 * shows its coach card. Skip and Not now end the tour.
 */
@Composable
fun TourLayer(
    tour: Tour?,
    mouseTour: MouseTour?,
    screen: TourScreen,
    wifiBoxes: Int,
    dialogOpen: Boolean,
    boxName: String,
    onFinishTour: () -> Unit,
    onFinishMouseTour: () -> Unit,
) {
    // The Devices step waits a moment for the list, so it does not point at "Add by IP address" while the search runs.
    var emptyForLong by remember(screen) { mutableStateOf(false) }
    LaunchedEffect(screen, wifiBoxes == 0) {
        emptyForLong = false
        if (wifiBoxes == 0) {
            delay(TourFlow.EMPTY_LIST_WAIT_MS)
            emptyForLong = true
        }
    }
    val step = TourFlow.step(tour, mouseTour, screen, wifiBoxes, emptyForLong, dialogOpen) ?: return
    // One overlay for the pairing steps, so the dim stays and the spotlight glides from step to step.
    CoachOverlay(step.target, stepKey = step) {
        when (step) {
            TourStep.OPEN_DEVICES -> CoachText(
                stringResource(R.string.tour_step, 1, 3), stringResource(R.string.tour_devices_title),
                stringResource(R.string.tour_devices_text), stringResource(R.string.tour_skip), onFinishTour,
            )
            TourStep.PICK_BOX -> CoachText(
                stringResource(R.string.tour_step, 2, 3), stringResource(R.string.tour_pick_title),
                stringResource(R.string.tour_pick_text), stringResource(R.string.tour_skip), onFinishTour,
            )
            TourStep.ADD_BY_IP -> CoachText(
                stringResource(R.string.tour_step, 2, 3), stringResource(R.string.tour_none_title),
                stringResource(R.string.tour_none_text), stringResource(R.string.tour_skip), onFinishTour,
            )
            TourStep.ALL_SET -> AllSet(boxName, onFinishTour)
            TourStep.MOUSE_CHOOSE -> CoachText(
                stringResource(R.string.mouse_tour_label), stringResource(R.string.mouse_tour_choose_title),
                stringResource(R.string.mouse_tour_choose_text), stringResource(R.string.tour_not_now), onFinishMouseTour,
            )
            TourStep.MOUSE_BLUETOOTH -> CoachText(
                stringResource(R.string.mouse_tour_bt_label), stringResource(R.string.mouse_tour_bt_title),
                stringResource(R.string.mouse_tour_bt_text), stringResource(R.string.tour_not_now), onFinishMouseTour,
            )
        }
    }
}

/** The last card: the check, "You are all set!", and Done. */
@Composable
private fun AllSet(boxName: String, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(end = 8.dp, top = 8.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoachCheck()
        Text(stringResource(R.string.tour_done_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface,
            modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.tour_done_text, boxName), fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
            color = Palette.onSurface.copy(alpha = 0.86f),
        )
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent),
        ) { Text(stringResource(R.string.action_done), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
    }
}
