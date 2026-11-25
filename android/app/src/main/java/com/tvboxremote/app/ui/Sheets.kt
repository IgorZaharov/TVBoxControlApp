package com.tvboxremote.app.ui

import com.tvboxremote.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.CodeRequest
import com.tvboxremote.app.remote.RemoteKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSheet(
    onTextChange: (String) -> Unit,
    onKey: (RemoteKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.keyboard_title), fontSize = 18.sp)
            Text(
                stringResource(R.string.keyboard_hint),
                color = Palette.muted, fontSize = 13.sp,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onTextChange(it) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.keyboard_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { onKey(RemoteKey.ENTER) }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteButton(Icons.AutoMirrored.Filled.Backspace, stringResource(R.string.key_delete), size = 56.dp, repeats = true) {
                    if (text.isNotEmpty()) {
                        text = text.dropLast(1)
                        onTextChange(text)
                    } else {
                        onKey(RemoteKey.DELETE)
                    }
                }
                OutlinedButton(onClick = { text = ""; onTextChange("") }) { Text(stringResource(R.string.keyboard_clear)) }
                OutlinedButton(onClick = { onKey(RemoteKey.ENTER) }) { Text(stringResource(R.string.key_enter)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaKeysSheet(onKey: (RemoteKey) -> Unit, onDismiss: () -> Unit) {
    val keys = listOf(
        RemoteKey.PREVIOUS to Icons.Filled.SkipPrevious,
        RemoteKey.REWIND to Icons.Filled.FastRewind,
        RemoteKey.PLAY_PAUSE to Icons.Filled.PlayArrow,
        RemoteKey.FAST_FORWARD to Icons.Filled.FastForward,
        RemoteKey.NEXT to Icons.Filled.SkipNext,
        RemoteKey.CHANNEL_UP to Icons.Filled.KeyboardDoubleArrowUp,
        RemoteKey.CHANNEL_DOWN to Icons.Filled.KeyboardDoubleArrowDown,
        RemoteKey.INPUT to Icons.AutoMirrored.Filled.Input,
        RemoteKey.GUIDE to Icons.AutoMirrored.Filled.ViewList,
        RemoteKey.SEARCH to Icons.Filled.Search,
        RemoteKey.ALL_APPS to Icons.Filled.Apps,
        RemoteKey.SETTINGS to Icons.Filled.Settings,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.surface) {
        Text(stringResource(R.string.media_title), fontSize = 18.sp, modifier = Modifier.padding(horizontal = 20.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(keys) { (key, icon) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LabeledButton(icon, stringResource(key.label)) { onKey(key) }
                }
            }
        }
    }
}

/** Asks for the pairing code that the TV shows. */
@Composable
fun CodeDialog(request: CodeRequest, onSubmit: (String?) -> Unit) {
    val isAtv = request is CodeRequest.Atv
    val length = if (isAtv) 6 else 4
    val wrong = when (request) {
        is CodeRequest.Atv -> request.wrongCode
        is CodeRequest.Receiver -> request.wrongCode
    }
    var code by remember(request) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { focus.requestFocus() }

    fun accept(c: Char) = if (isAtv) c.isDigit() || c.uppercaseChar() in 'A'..'F' else c.isDigit()

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (isAtv) R.string.pair_box_title else R.string.pair_receiver_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (isAtv) R.string.pair_box_text else R.string.pair_receiver_text),
                    fontSize = 15.sp,
                )
                if (wrong) Text(stringResource(R.string.pair_wrong_code), color = Palette.power, fontSize = 14.sp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(::accept).uppercase().take(length) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 28.sp, textAlign = TextAlign.Center, letterSpacing = 6.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isAtv) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == length) onSubmit(code) }),
                )
            }
        },
        confirmButton = { TextButton(enabled = code.length == length, onClick = { onSubmit(code) }) { Text(stringResource(R.string.action_pair)) } },
        dismissButton = { TextButton(onClick = { onSubmit(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}
