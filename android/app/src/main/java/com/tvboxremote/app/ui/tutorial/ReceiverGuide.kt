package com.tvboxremote.app.ui.tutorial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import com.tvboxremote.app.MainViewModel
import com.tvboxremote.app.R
import com.tvboxremote.app.receiver.ReceiverState
import com.tvboxremote.app.ui.Palette
import com.tvboxremote.app.ui.ReceiverHost
import com.tvboxremote.app.ui.ShareState
import com.tvboxremote.app.ui.rememberReceiverHost
import com.tvboxremote.app.ui.rememberReducedMotion
import kotlinx.coroutines.delay

private const val STEPS = 6
private const val FIRST_AUTO_STEP = 3
private const val CODE_STEP = 5

/** The drawings of the TV screen use the mockup size: 322 by 181. */
private const val TV_W = 322f

/** TV colors: a TV screen looks dark whatever the phone theme is. */
private val TvBack = Color(0xFF0B0C10)
private val TvEdge = Color(0xFF2A2D35)
private val TvTile = Color(0xFF1C1E24)
private val TvRaised = Color(0xFF23262D)
private val TvBody = Color(0xFFC9CCD4)
private val TvStrong = Color(0xFFE8EAF0)
private val TvMuted = Color(0xFF8A8F9C)
private val TvBlue = Color(0xFF3A74E8)
private val TvOrange = Color(0xFFF07F1A)

/**
 * The receiver install guide (docs/UI_SPEC.md, "Receiver install guide"): six cards with a drawing of the TV screen
 * and a hand-drawn arrow at the thing to select. The phone serves receiver.apk on the Wi-Fi while the guide is open.
 * Two steps move on by themselves: when the box downloads the file, and when the phone finds the receiver.
 */
@Composable
fun ReceiverGuide(vm: MainViewModel, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val ui by vm.ui.collectAsState()
        val host = rememberReceiverHost()
        var step by rememberSaveable { mutableIntStateOf(0) }
        val reduce = rememberReducedMotion()
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        // The box downloaded the file: on to Install.
        LaunchedEffect(host.downloads) { if (host.downloads > 0 && step < FIRST_AUTO_STEP) step = FIRST_AUTO_STEP }
        // While the receiver gets installed and turned on, look for it every 3 seconds.
        LaunchedEffect(step) {
            while (step in FIRST_AUTO_STEP until CODE_STEP) {
                delay(3_000)
                vm.connectReceiver()
            }
        }
        // The receiver answers: it asks for its code, or the mouse is ready.
        LaunchedEffect(ui.receiver) {
            when (ui.receiver) {
                is ReceiverState.PairRequired -> step = CODE_STEP
                is ReceiverState.Ready -> onClose()
                else -> Unit
            }
        }
        val dim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { dim.animateTo(1f, tween(if (reduce) 200 else 400)) }

        Box(
            Modifier
                .fillMaxSize()
                .background(ScrimColor.copy(alpha = SCRIM_ALPHA * dim.value))
                .systemBarsPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .coachCard(24f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.guide_label, step + 1, STEPS).uppercase(),
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = Palette.muted,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.guide_close), tint = Palette.muted)
                    }
                }
                val density = LocalDensity.current
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        val shift = with(density) { 24.dp.roundToPx() }
                        val change = if (reduce) {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        } else {
                            (fadeIn(tween(300, delayMillis = 150, easing = EaseDraw)) +
                                slideInHorizontally(tween(300, delayMillis = 150, easing = EaseDraw)) { shift * dir }) togetherWith
                                (fadeOut(tween(150, easing = EaseDraw)) + slideOutHorizontally(tween(150, easing = EaseDraw)) { -shift * dir })
                        }
                        change.using(SizeTransform(clip = false))
                    },
                    label = "guide step",
                ) { s ->
                    GuideStep(s, host, ui.receiver, onTypeLink = vm::startTypeLink)
                }
                Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(80.dp)) {
                        if (step > 0) TextButton(onClick = { step-- }) { Text(stringResource(R.string.key_back), color = Palette.accent, fontSize = 15.sp) }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        repeat(STEPS) { i -> Dot(active = i == step) }
                    }
                    Box(Modifier.width(80.dp), contentAlignment = Alignment.CenterEnd) {
                        if (step < CODE_STEP) {
                            Button(
                                onClick = { step++ },
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.onAccent),
                            ) { Text(stringResource(R.string.guide_next), fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(active: Boolean) {
    val width by animateDpAsState(if (active) 18.dp else 6.dp, spring(dampingRatio = 1f, stiffness = 500f), label = "dot")
    Box(Modifier.size(width, 6.dp).clip(CircleShape).background(if (active) Palette.accent else Palette.muted.copy(alpha = 0.4f)))
}

@Composable
private fun GuideStep(step: Int, host: ReceiverHost, receiver: ReceiverState, onTypeLink: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val address = (host.state as? ShareState.Ready)?.address
        TvScreen(step, address)
        val (title, body) = when (step) {
            0 -> R.string.guide1_title to R.string.guide1_text
            1 -> R.string.guide2_title to R.string.guide2_text
            2 -> R.string.guide3_title to R.string.guide3_text
            3 -> R.string.guide4_title to R.string.guide4_text
            4 -> R.string.guide5_title to R.string.guide5_text
            else -> R.string.guide6_title to R.string.guide6_text
        }
        Text(stringResource(title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.onSurface,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(body), fontSize = 15.sp, lineHeight = 22.sp, color = Palette.onSurface.copy(alpha = 0.86f))
        when (step) {
            2 -> AddressPart(host, onTypeLink)
            4 -> {
                Text(stringResource(R.string.guide5_gray), fontSize = 13.sp, lineHeight = 18.sp, color = Palette.muted)
                Status(stringResource(R.string.guide5_looking))
            }
            3 -> if (receiver is ReceiverState.Connecting) Status(stringResource(R.string.guide5_looking))
        }
    }
}

/** The address in big text, what the phone is doing, and the other way: the phone types the link on the box. */
@Composable
private fun AddressPart(host: ReceiverHost, onTypeLink: () -> Unit) {
    when (val s = host.state) {
        is ShareState.Ready -> {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.background)
                    .border(1.5.dp, Palette.accent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.address, fontFamily = FontFamily.Monospace, fontSize = 24.sp, color = Palette.onSurface, textAlign = TextAlign.Center)
            }
            Status(stringResource(R.string.guide3_waiting))
        }
        ShareState.Loading -> Status(stringResource(R.string.receiver_share_loading))
        ShareState.NoWifi -> Text(stringResource(R.string.receiver_share_no_wifi), fontSize = 14.sp, color = Palette.power)
        ShareState.Failed -> {
            Text(stringResource(R.string.receiver_share_failed), fontSize = 14.sp, color = Palette.power)
            TextButton(onClick = host.retry) { Text(stringResource(R.string.action_try_again), color = Palette.accent) }
        }
    }
    TextButton(onClick = onTypeLink) { Text(stringResource(R.string.guide3_type), color = Palette.accent) }
}

@Composable
private fun Status(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = Palette.muted)
    }
}

/** The arrow of each drawing, in the 322 by 181 space of the mockup: from, then to. Step 6 has none. */
private val ARROWS = listOf(
    floatArrayOf(196f, 162f, 148f, 122f),
    floatArrayOf(190f, 166f, 262f, 118f),
    floatArrayOf(226f, 162f, 282f, 112f),
    floatArrayOf(306f, 172f, 260f, 140f),
    floatArrayOf(214f, 164f, 282f, 100f),
)

/** A drawing of the TV screen for [step], with the thing to select lit and an arrow at it. */
@Composable
private fun TvScreen(step: Int, address: String?) {
    val reduce = rememberReducedMotion()
    val accent = Palette.accent
    val draw = remember(step) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(step) {
        if (!reduce) {
            delay(450)
            draw.animateTo(1f, tween(650, easing = EaseDraw))
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(TvBack)
            .border(1.dp, TvEdge, RoundedCornerShape(12.dp)),
    ) {
        val k = maxWidth.value / TV_W
        when (step) {
            0 -> {
                TvText(stringResource(R.string.tv_apps), 16f, 46f, k, color = TvMuted, bold = true)
                TvBox(16f, 70f, 62f, 44f, k, TvTile) { TvLabel(stringResource(R.string.tv_video), k) }
                TvBox(92f, 70f, 62f, 44f, k, TvRaised, lit = true) { TvLabel(stringResource(R.string.tv_play), k, color = Color.White) }
                TvBox(168f, 70f, 62f, 44f, k, TvTile) { TvLabel(stringResource(R.string.tv_music), k) }
                TvBox(244f, 70f, 62f, 44f, k, TvTile) { TvLabel(stringResource(R.string.tv_settings), k) }
            }
            1 -> {
                TvBox(16f, 18f, 290f, 28f, k, TvTile, radius = 14f, center = false) {
                    TvLabel(stringResource(R.string.tv_search), k, color = TvStrong, modifier = Modifier.padding(start = (12 * k).dp))
                }
                TvBox(16f, 70f, 290f, 60f, k, Color(0xFF16181D), radius = 10f) {}
                TvBox(26f, 80f, 40f, 40f, k, TvOrange, radius = 9f) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size((22 * k).dp))
                }
                TvText("Downloader", 76f, 82f, k, color = TvStrong, bold = true, size = 12f)
                TvText("AFTVnews", 76f, 100f, k, color = TvMuted)
                TvBox(236f, 88f, 62f, 24f, k, TvBlue, radius = 12f, lit = true) { TvLabel(stringResource(R.string.tv_install), k, color = Color.White, bold = true) }
            }
            2 -> {
                TvText("Downloader", 16f, 20f, k, color = Color(0xFFF5A524), bold = true, size = 13f)
                TvBox(16f, 74f, 234f, 32f, k, TvStrong, radius = 6f, center = false) {
                    Text(
                        address ?: "…", fontFamily = FontFamily.Monospace, fontSize = (12 * k).sp, color = Color(0xFF14161B),
                        modifier = Modifier.padding(start = (10 * k).dp), maxLines = 1,
                    )
                }
                TvBox(258f, 74f, 48f, 32f, k, TvOrange, radius = 6f, lit = true) { TvLabel(stringResource(R.string.tv_go), k, color = Color.White, bold = true) }
            }
            3 -> {
                TvBox(56f, 26f, 210f, 124f, k, TvRaised, radius = 10f, center = false) {}
                TvText("TVBox Remote Receiver", 68f, 36f, k, color = TvStrong, bold = true, size = 12f)
                TvText(stringResource(R.string.tv_install_question), 68f, 56f, k)
                TvBox(112f, 112f, 66f, 26f, k, Color.Transparent, radius = 13f) { TvLabel(stringResource(R.string.tv_cancel), k, color = TvMuted) }
                TvBox(186f, 112f, 68f, 26f, k, TvBlue, radius = 13f, lit = true) { TvLabel(stringResource(R.string.tv_install), k, color = Color.White, bold = true) }
            }
            4 -> {
                TvText(stringResource(R.string.tv_accessibility), 16f, 20f, k, color = TvStrong, bold = true, size = 13f)
                TvBox(16f, 60f, 290f, 44f, k, TvTile, radius = 10f, center = false) {
                    TvLabel(stringResource(R.string.tv_mouse_service), k, color = TvStrong, modifier = Modifier.padding(start = (12 * k).dp))
                }
                TvBox(262f, 72f, 36f, 20f, k, TvBlue, radius = 10f, lit = true, center = false) {
                    Box(Modifier.padding(start = (19 * k).dp).size((14 * k).dp).clip(CircleShape).background(Color.White))
                }
                TvBox(16f, 112f, 290f, 36f, k, Color(0xFF16181D), radius = 10f, center = false) {
                    TvLabel(stringResource(R.string.tv_captions), k, color = TvMuted, modifier = Modifier.padding(start = (12 * k).dp))
                }
            }
            else -> {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("TVBox Remote Receiver · " + stringResource(R.string.tv_pair_code), fontSize = (12 * k).sp, color = TvMuted)
                    Box(
                        Modifier
                            .padding(top = (8 * k).dp)
                            .tvLit(10f * k)
                            .padding(horizontal = (16 * k).dp, vertical = (6 * k).dp),
                    ) {
                        // Dots, not digits: the real code is on the TV.
                        Text("● ● ● ●", fontSize = (24 * k).sp, color = Color.White)
                    }
                }
            }
        }
        ARROWS.getOrNull(step)?.let { a ->
            Canvas(Modifier.fillMaxSize()) {
                val s = size.width / TV_W
                val arrow = CoachLayout.curve(CoachPoint(a[0] * s, a[1] * s), CoachPoint(a[2] * s, a[3] * s))
                drawCoachArrow(arrow, draw.value, accent, 2.5f * s, 10f * s)
            }
        }
    }
}

/** A box on the TV drawing, placed in the 322 by 181 space. [lit] marks the thing to select. */
@Composable
private fun BoxWithConstraintsScope.TvBox(
    x: Float, y: Float, w: Float, h: Float, k: Float, color: Color,
    radius: Float = 8f, lit: Boolean = false, center: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset(x = (x * k).dp, y = (y * k).dp)
            .size((w * k).dp, (h * k).dp)
            .then(if (lit) Modifier.tvLit(radius * k) else Modifier)
            .clip(RoundedCornerShape((radius * k).dp))
            .background(color),
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) { content() }
}

/** The light on the thing to select: an accent ring and glow. */
@Composable
private fun Modifier.tvLit(radius: Float): Modifier {
    val accent = Palette.accent
    val density = LocalDensity.current.density
    return this
        .drawBehind {
            drawGlowRing(CoachRect(0f, 0f, size.width, size.height), radius * density, accent.copy(alpha = 0.55f), 3.dp.toPx(), 10.dp.toPx())
        }
        .border(1.5.dp, accent, RoundedCornerShape(radius.dp))
}

@Composable
private fun BoxWithConstraintsScope.TvText(
    text: String, x: Float, y: Float, k: Float,
    color: Color = TvBody, bold: Boolean = false, size: Float = 11f,
) {
    Text(
        text, fontSize = (size * k).sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1, modifier = Modifier.offset(x = (x * k).dp, y = (y * k).dp),
    )
}

@Composable
private fun TvLabel(text: String, k: Float, color: Color = TvBody, bold: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = (10 * k).sp, lineHeight = (12 * k).sp, color = color, textAlign = TextAlign.Center,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, modifier = modifier,
    )
}
