package com.tvboxremote.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvboxremote.app.BuildConfig
import com.tvboxremote.app.R

private class HelpSection(@StringRes val title: Int, @StringRes vararg val paragraphs: Int)

private val HELP = listOf(
    HelpSection(R.string.help_start_title, R.string.help_start_1, R.string.help_start_2, R.string.help_start_3),
    HelpSection(R.string.help_pair_title, R.string.help_pair_1, R.string.help_pair_2, R.string.help_pair_3, R.string.help_pair_4),
    HelpSection(R.string.help_bt_title, R.string.help_bt_1, R.string.help_bt_2, R.string.help_bt_3, R.string.help_bt_4),
    HelpSection(R.string.help_mouse_title, R.string.help_mouse_1, R.string.help_mouse_2, R.string.help_mouse_3, R.string.help_mouse_4),
    HelpSection(
        R.string.help_controller_title,
        R.string.help_controller_1, R.string.help_controller_2, R.string.help_controller_3, R.string.help_controller_4,
    ),
    HelpSection(R.string.help_widget_title, R.string.help_widget_1, R.string.help_widget_2),
    HelpSection(R.string.help_keyboard_title, R.string.help_keyboard_1, R.string.help_keyboard_2, R.string.help_keyboard_3),
    HelpSection(
        R.string.help_trouble_title,
        R.string.help_trouble_1, R.string.help_trouble_2, R.string.help_trouble_3, R.string.help_trouble_4, R.string.help_trouble_5,
    ),
)

/** Help: one collapsible card per section. The first section is open. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(setOf(0)) }
    Column(
        Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(stringResource(R.string.help_title), onBack)
        HELP.forEachIndexed { index, section ->
            HelpCard(section, expanded = index in open) {
                open = if (index in open) open - index else open + index
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun HelpCard(section: HelpSection, expanded: Boolean, onToggle: () -> Unit) {
    val reduce = rememberReducedMotion()
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, Motion.press(reduce), label = "chevron")
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(section.title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.muted,
                modifier = Modifier.size(22.dp).rotate(chevron),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold)) +
                fadeIn(Motion.fadeOut()),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = IntSize.VisibilityThreshold)) +
                fadeOut(Motion.fadeOut()),
        ) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                section.paragraphs.forEach {
                    Text(stringResource(it), fontSize = 15.sp, lineHeight = 22.sp, color = Palette.bodyText)
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.about_title), onBack)
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.SettingsRemote, contentDescription = null, tint = Palette.accent,
                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(Palette.surface).padding(20.dp),
            )
            Text(stringResource(R.string.store_name), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = Palette.muted)
        }
        AboutBlock(R.string.about_what_title, R.string.about_what)
        AboutBlock(R.string.about_how_title, R.string.about_how)
        AboutBlock(R.string.about_privacy_title, R.string.about_privacy)
        AboutBlock(R.string.about_credits_title, R.string.about_credits)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AboutBlock(@StringRes title: Int, @StringRes body: Int) {
    Text(
        stringResource(title).uppercase(), fontSize = 13.sp, color = Palette.muted, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
    )
    Text(stringResource(body), fontSize = 15.sp, lineHeight = 22.sp)
}
