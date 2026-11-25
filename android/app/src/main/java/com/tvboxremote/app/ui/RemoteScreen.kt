package com.tvboxremote.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.Link
import com.tvboxremote.app.R
import com.tvboxremote.app.Transport
import com.tvboxremote.app.UiState
import com.tvboxremote.app.remote.RemoteKey
import kotlinx.coroutines.flow.Flow

enum class MenuItem(@StringRes val label: Int) {
    DEVICES(R.string.menu_devices),
    MEDIA(R.string.menu_media),
    PREFERENCES(R.string.menu_preferences),
    HELP(R.string.menu_help),
    ABOUT(R.string.menu_about),
}

@Composable
fun RemoteScreen(
    state: UiState,
    signals: Flow<Unit>,
    onKey: (RemoteKey) -> Unit,
    onKeyboard: () -> Unit,
    onMouse: () -> Unit,
    onController: () -> Unit,
    onOpenDevices: () -> Unit,
    onMenu: (MenuItem) -> Unit,
) {
    ProvideTilt {
        Box(
            Modifier
                .fillMaxSize()
                .remoteBackground()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .displayCutoutPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TopBar(state, onOpenDevices, onMenu)
                Spacer(Modifier.height(16.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RemoteButton(
                        Icons.Filled.PowerSettingsNew, stringResource(R.string.key_power),
                        tint = Palette.power, style = SurfaceStyle.Power,
                    ) { onKey(RemoteKey.POWER) }
                    Spacer(Modifier.weight(1f))
                    RemoteButton(Icons.Filled.Keyboard, stringResource(R.string.btn_keyboard), size = 60.dp, onPress = onKeyboard)
                    Spacer(Modifier.width(12.dp))
                    RemoteButton(Icons.Filled.Mouse, stringResource(R.string.btn_mouse), size = 60.dp, onPress = onMouse)
                    Spacer(Modifier.width(12.dp))
                    RemoteButton(Icons.Filled.SportsEsports, stringResource(R.string.btn_controller), size = 60.dp, onPress = onController)
                }

                // One-hand layout (docs/UI_SPEC.md, "Remote screen"): all free height goes here, so that the
                // D-pad, the key row and the volume pill sit together at the bottom. The signal line stands in
                // this free height, 16 dp above the D-pad ring: 64 dp high, shorter on a short screen.
                Spacer(Modifier.height(16.dp))
                // The signal line sits in the middle of the free height between the top row and the D-pad.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val length = (maxHeight - 32.dp).coerceIn(24.dp, 64.dp)
                    SignalLine(signals, length = length)
                }
                DPad(
                    onArrow = {
                        onKey(
                            when (it) {
                                Direction.UP -> RemoteKey.UP
                                Direction.DOWN -> RemoteKey.DOWN
                                Direction.LEFT -> RemoteKey.LEFT
                                Direction.RIGHT -> RemoteKey.RIGHT
                            }
                        )
                    },
                    onOk = { onKey(RemoteKey.OK) },
                )
                Spacer(Modifier.height(20.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RemoteButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.key_back)) { onKey(RemoteKey.BACK) }
                    RemoteButton(Icons.Filled.Home, stringResource(R.string.key_home)) { onKey(RemoteKey.HOME) }
                    RemoteButton(Icons.Filled.Settings, stringResource(R.string.key_settings)) { onKey(RemoteKey.SETTINGS) }
                }

                Spacer(Modifier.height(16.dp))
                VolumePill(
                    onDown = { onKey(RemoteKey.VOLUME_DOWN) },
                    onMute = { onKey(RemoteKey.MUTE) },
                    onUp = { onKey(RemoteKey.VOLUME_UP) },
                    muted = state.volume?.muted == true,
                )
                // The volume text always takes its line, so that the D-pad does not jump when the level arrives.
                val volume = state.volume?.takeIf { it.max > 0 }
                Text(
                    if (volume != null) stringResource(R.string.volume_level, volume.level, volume.max) else "",
                    color = Palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TopBar(state: UiState, onOpenDevices: () -> Unit, onMenu: (MenuItem) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val (dot, name, detail) = when (val link = state.link) {
            Link.None -> Triple(Palette.muted, stringResource(R.string.status_not_connected), stringResource(R.string.status_tap_to_connect))
            is Link.Connecting -> Triple(Palette.warn, link.name, stringResource(R.string.status_connecting))
            is Link.Connected -> Triple(
                Palette.ok, link.name,
                stringResource(if (link.transport == Transport.WIFI) R.string.transport_wifi else R.string.transport_bluetooth),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpenDevices)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(dot)
            Column(Modifier.padding(start = 12.dp)) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 12.sp, color = Palette.muted)
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_open))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuItem.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.label), fontSize = 16.sp) },
                        onClick = { menuOpen = false; onMenu(item) },
                    )
                }
            }
        }
    }
}
