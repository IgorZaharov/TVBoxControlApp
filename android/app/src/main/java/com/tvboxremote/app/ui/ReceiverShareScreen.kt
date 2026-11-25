package com.tvboxremote.app.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tvboxremote.app.R
import com.tvboxremote.app.receiver.ReceiverShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ShareState {
    data object Loading : ShareState
    data class Ready(val address: String) : ShareState
    data object NoWifi : ShareState
    data object Failed : ShareState
}

/**
 * Install the receiver from this phone: the phone serves receiver.apk on the local Wi-Fi and shows the short address
 * in big text, with the steps for the box (docs/UI_SPEC.md, "Receiver install"). The server runs while this screen is
 * open, and the screen stays on.
 */
@Composable
fun ReceiverShareScreen(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val context = LocalContext.current
        val view = LocalView.current
        val scope = rememberCoroutineScope()
        var state by remember { mutableStateOf<ShareState>(ShareState.Loading) }
        var downloads by remember { mutableIntStateOf(0) }
        var attempt by remember { mutableIntStateOf(0) }
        var server by remember { mutableStateOf<ReceiverShare.Server?>(null) }

        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        DisposableEffect(Unit) { onDispose { server?.stop() } }
        LaunchedEffect(attempt) {
            state = ShareState.Loading
            server?.stop()
            server = null
            val ip = ReceiverShare.localAddress()
            if (ip == null) {
                state = ShareState.NoWifi
                return@LaunchedEffect
            }
            val file = try {
                ReceiverShare.fetch(context)
            } catch (e: Exception) {
                state = ShareState.Failed
                return@LaunchedEffect
            }
            val started = withContext(Dispatchers.IO) {
                ReceiverShare.serve(scope, file) { scope.launchMain { downloads++ } }
            }
            if (started == null) {
                state = ShareState.Failed
                return@LaunchedEffect
            }
            server = started
            state = ShareState.Ready("$ip:${started.port}")
        }

        Box(Modifier.fillMaxSize().remoteBackground()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .displayCutoutPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    stringResource(R.string.receiver_share_title),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(16.dp))
                GlassBox(RoundedCornerShape(20.dp), Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (val s = state) {
                            ShareState.Loading -> {
                                CircularProgressIndicator(color = Palette.accent, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(stringResource(R.string.receiver_share_loading), color = Palette.muted, fontSize = 15.sp)
                            }
                            ShareState.NoWifi, ShareState.Failed -> {
                                Text(
                                    stringResource(if (s == ShareState.NoWifi) R.string.receiver_share_no_wifi else R.string.receiver_share_failed),
                                    color = Palette.onSurface, fontSize = 16.sp, textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_try_again)) }
                            }
                            is ShareState.Ready -> {
                                Text(stringResource(R.string.receiver_share_type), color = Palette.muted, fontSize = 15.sp)
                                Spacer(Modifier.height(10.dp))
                                SelectionContainer {
                                    Text(
                                        s.address,
                                        fontSize = 40.sp, lineHeight = 46.sp, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold, color = Palette.accent, textAlign = TextAlign.Center,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.receiver_share_full_address, "http://${s.address}"),
                                    color = Palette.muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (downloads > 0) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.ok, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.receiver_share_done), color = Palette.ok, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    } else {
                                        CircularProgressIndicator(color = Palette.muted, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.receiver_share_waiting), color = Palette.muted, fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                GlassBox(RoundedCornerShape(20.dp), Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            R.string.receiver_share_step1,
                            R.string.receiver_share_step2,
                            R.string.receiver_share_step3,
                            R.string.receiver_share_step4,
                        ).forEachIndexed { i, step -> Step(i + 1, stringResource(step)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.receiver_share_keep), color = Palette.muted, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

/** One numbered step: the number in an accent circle, then the text. */
@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).background(Palette.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Palette.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.onSurface, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Runs [block] on the main thread: the download count is Compose state. */
private fun kotlinx.coroutines.CoroutineScope.launchMain(block: () -> Unit) {
    launch(Dispatchers.Main) { block() }
}
