package com.tvboxremote.app.ui

import com.tvboxremote.app.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.Link
import com.tvboxremote.app.MainViewModel
import com.tvboxremote.app.Transport
import com.tvboxremote.app.bluetooth.HidState
import com.tvboxremote.app.discovery.TvBox

private val BLUETOOTH_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

@SuppressLint("MissingPermission")
@Composable
fun DevicesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val boxes by vm.discovery.boxes.collectAsState()
    val searching by vm.discovery.searching.collectAsState()
    val found by vm.scanner.found.collectAsState()
    val scanning by vm.scanner.scanning.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var forgetBox by remember { mutableStateOf<TvBox?>(null) }

    fun granted() = BLUETOOTH_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var btGranted by remember { mutableStateOf(granted()) }
    // Changes when Bluetooth settings may have changed, so the bonded list reloads.
    var btRefresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        btGranted = granted()
        if (btGranted) vm.hid.register()
    }
    val visibleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { btRefresh++ }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { btRefresh++ }

    LaunchedEffect(btGranted) { if (btGranted) vm.hid.register() }

    // The bonded list reloads after system dialogs (btRefresh) and after HID state changes.
    var bonded by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    LaunchedEffect(btGranted, btRefresh, ui.hid) {
        bonded = if (btGranted) vm.hid.bondedDevices().sortedByDescending { it.looksLikeTv() } else emptyList()
    }
    DisposableEffect(Unit) { onDispose { vm.scanner.stop() } }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.devices_title), onBack) }

        // ---------- Wi-Fi ----------
        item {
            SectionTitle(stringResource(R.string.section_wifi), trailing = { if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) })
        }
        if (boxes.isEmpty()) {
            item {
                HintText(stringResource(R.string.devices_wifi_searching))
            }
        }
        items(boxes, key = { "wifi-" + it.host }) { box ->
            val active = ui.connectedBox?.host == box.host
            val status = when {
                active && ui.link is Link.Connected -> stringResource(R.string.status_connected)
                active && ui.link is Link.Connecting -> stringResource(R.string.status_connecting)
                else -> box.host
            }
            DeviceRow(
                icon = Icons.Filled.Tv,
                title = box.name,
                subtitle = if (box.receiverPort != null) stringResource(R.string.devices_mouse_ready, status) else status,
                highlighted = active,
                onClick = { vm.connectWifi(box); onBack() },
                onForget = { forgetBox = box },
            )
        }
        item {
            OutlinedButton(onClick = { showAddDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.devices_add_by_ip))
            }
        }

        // ---------- Bluetooth ----------
        item { SectionTitle(stringResource(R.string.section_bluetooth)) }
        when {
            ui.hid == HidState.Unavailable -> item {
                HintText(stringResource(R.string.bt_unavailable))
            }
            !btGranted -> item {
                Column {
                    HintText(stringResource(R.string.bt_allow_hint))
                    FilledTonalButton(onClick = { permissionLauncher.launch(BLUETOOTH_PERMISSIONS) }) {
                        Text(stringResource(R.string.bt_allow))
                    }
                }
            }
            !vm.hid.isBluetoothOn -> item {
                Column {
                    HintText(stringResource(R.string.bt_off))
                    FilledTonalButton(onClick = {
                        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text(stringResource(R.string.bt_turn_on)) }
                }
            }
            else -> {
                if (bonded.isEmpty()) item { HintText(stringResource(R.string.bt_none_paired)) }
                items(bonded, key = { "bt-" + it.address }) { device ->
                    val connected = (ui.hid as? HidState.Connected)?.device?.address == device.address
                    val connecting = (ui.hid as? HidState.Connecting)?.device?.address == device.address
                    DeviceRow(
                        icon = Icons.Filled.Bluetooth,
                        title = device.name ?: device.address,
                        subtitle = when {
                            connected -> stringResource(R.string.status_connected)
                            connecting -> stringResource(R.string.status_connecting)
                            else -> stringResource(R.string.bt_paired)
                        },
                        highlighted = connected,
                        onClick = { vm.connectBluetooth(device); onBack() },
                        onForget = null,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.scanner.start() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(if (scanning) R.string.bt_searching else R.string.bt_find_nearby))
                        }
                        OutlinedButton(
                            onClick = {
                                vm.hid.register()
                                vm.expectBluetooth()
                                visibleLauncher.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.bt_make_visible)) }
                    }
                }
                val bondedAddresses = bonded.map { it.address }.toSet()
                val nearby = found.filter { it.address !in bondedAddresses }
                items(nearby, key = { "near-" + it.address }) { device ->
                    DeviceRow(
                        icon = Icons.Filled.Bluetooth,
                        title = device.name ?: device.address,
                        subtitle = stringResource(R.string.bt_tap_to_pair),
                        highlighted = false,
                        onClick = { vm.connectBluetooth(device) },
                        onForget = null,
                    )
                }
                item {
                    HintText(stringResource(R.string.bt_pair_help))
                }
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }

    if (showAddDialog) {
        AddByIpDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { host, name ->
                showAddDialog = false
                vm.addManualBox(host, name)
            },
        )
    }
    forgetBox?.let { box ->
        AlertDialog(
            onDismissRequest = { forgetBox = null },
            title = { Text(stringResource(R.string.forget_title, box.name)) },
            text = { Text(stringResource(R.string.forget_text)) },
            confirmButton = { TextButton(onClick = { vm.forget(box); forgetBox = null }) { Text(stringResource(R.string.action_forget)) } },
            dismissButton = { TextButton(onClick = { forgetBox = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.looksLikeTv(): Boolean = runCatching {
    bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
}.getOrDefault(false)

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SectionTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Palette.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        trailing()
    }
}

@Composable
fun HintText(text: String) {
    Text(text, color = Palette.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface)
            .combinedClickable(onClick = onClick, onLongClick = onForget)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlighted) Palette.accent else Palette.onSurface)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 13.sp, color = if (highlighted) Palette.ok else Palette.muted)
        }
        if (onForget != null) {
            IconButton(onClick = onForget) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.forget_description, title), tint = Palette.muted)
            }
        }
    }
}

@Composable
private fun AddByIpDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val valid = host.trim().matches(Regex("""^[0-9A-Za-z.:\-]+$"""))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_add_by_ip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_ip_help), color = Palette.muted, fontSize = 14.sp)
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text(stringResource(R.string.add_ip_address)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.add_ip_name)) })
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(host.trim(), name.trim()) }) { Text(stringResource(R.string.action_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
