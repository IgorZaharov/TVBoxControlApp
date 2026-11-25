package com.tvboxremote.app.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.tvboxremote.app.R
import com.tvboxremote.app.data.Preferences

/** Preferences: five matte glass cards on the remote background (docs/UI_SPEC.md, "Preferences"). */
@Composable
fun PreferencesScreen(
    prefs: Preferences,
    onChange: (Preferences) -> Unit,
    onControllerLayout: () -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize().remoteBackground()) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        ) {
            Header(stringResource(R.string.prefs_title), onBack)

            SectionTitle(R.string.prefs_section_appearance)
            GlassCard {
                ThemeRow(prefs.theme) { onChange(prefs.copy(theme = it)) }
                Hairline()
                AccentRow(prefs.accent) { onChange(prefs.copy(accent = it)) }
            }

            SectionTitle(R.string.prefs_section_language)
            GlassCard { LanguageRow() }

            SectionTitle(R.string.prefs_section_remote)
            GlassCard {
                ToggleRow(Icons.Filled.Vibration, R.string.prefs_haptics, R.string.prefs_haptics_detail, prefs.haptics) {
                    onChange(prefs.copy(haptics = it))
                }
                Hairline()
                ToggleRow(Icons.Filled.WbSunny, R.string.prefs_screen_on, R.string.prefs_screen_on_detail, prefs.keepScreenOn) {
                    onChange(prefs.copy(keepScreenOn = it))
                }
            }

            SectionTitle(R.string.prefs_section_pointer)
            GlassCard {
                SliderRow(Icons.Filled.ScreenRotation, R.string.prefs_gyro_speed, prefs.gyroSpeed) { onChange(prefs.copy(gyroSpeed = it)) }
                Hairline()
                SliderRow(Icons.Filled.TouchApp, R.string.prefs_touch_speed, prefs.touchpadSpeed) { onChange(prefs.copy(touchpadSpeed = it)) }
            }

            SectionTitle(R.string.prefs_section_controller)
            GlassCard {
                NavRow(
                    Icons.Filled.SportsEsports,
                    stringResource(R.string.prefs_controller_layout),
                    stringResource(R.string.prefs_controller_layout_detail),
                    onClick = onControllerLayout,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- frame

/** The title row: a raised round back button and the screen title. */
@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // A 48 dp touch target around the 44 dp raised button. The outer box does not clip, so the button
        // shadow shows in full; only the touch overlay is clipped to the circle for the ripple.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).raisedSurface(CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Palette.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            val back = stringResource(R.string.action_back)
            Box(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = back }
            )
        }
        Text(
            title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.onSurface,
            modifier = Modifier.padding(start = 10.dp).semantics { heading() },
        )
    }
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        stringResource(text).uppercase(),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = Palette.muted,
        modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 6.dp).semantics { heading() },
    )
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glassSurface(CardShape).padding(horizontal = 12.dp, vertical = 2.dp),
        content = content,
    )
}

/** A row separator, inset 64 dp from the card edge (12 dp card padding + 52 dp). */
@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(start = 52.dp)
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind { drawRect(Palette.onSurface.copy(alpha = 0.08f)) }
    )
}

/** The leading icon of a row: a 36 dp rounded square with a soft accent fill. */
@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .drawBehind { drawRect(Palette.accent.copy(alpha = 0.14f)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowTexts(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.onSurface)
        if (detail != null) Text(detail, fontSize = 13.sp, color = Palette.muted)
    }
}

// ---------------------------------------------------------------- rows

/** A row with a switch. A tap anywhere on the row toggles the switch. */
@Composable
private fun ToggleRow(icon: ImageVector, @StringRes title: Int, @StringRes detail: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        RowTexts(stringResource(title), stringResource(detail), Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.accent, checkedThumbColor = Palette.onAccent),
        )
    }
}

@Composable
private fun SliderRow(icon: ImageVector, @StringRes title: Int, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.onSurface, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.prefs_speed_value, value), fontSize = 14.sp, color = Palette.muted)
            }
            Slider(
                value = value,
                onValueChange = { onChange((it * 10).toInt() / 10f) },
                valueRange = 0.5f..3f,
                steps = 24,
                colors = SliderDefaults.colors(
                    thumbColor = Palette.accent,
                    activeTrackColor = Palette.accent,
                    inactiveTrackColor = Palette.onSurface.copy(alpha = 0.12f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

/** A row that opens something: a chevron on the right. */
@Composable
private fun NavRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        RowTexts(title, detail, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.muted)
    }
}

// ---------------------------------------------------------------- theme

/** Theme: System, Dark or Light, as a segmented control in a glass track. */
@Composable
private fun ThemeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        Triple(ThemeMode.SYSTEM, R.string.theme_system, Icons.Filled.BrightnessAuto),
        Triple(ThemeMode.DARK, R.string.theme_dark, Icons.Filled.DarkMode),
        Triple(ThemeMode.LIGHT, R.string.theme_light, Icons.Filled.LightMode),
    )
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.Contrast)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.prefs_theme), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Palette.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        val track = if (Palette.isDark) Color.Black.copy(alpha = 0.35f) else Color(0xFF14161B).copy(alpha = 0.06f)
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                // A background shape, not a clip: the selected segment's shadow stays visible.
                .background(track, RoundedCornerShape(22.dp))
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (mode, label, icon) ->
                ThemeSegment(icon, stringResource(label), mode == selected) { onSelect(mode) }
            }
        }
    }
}

private val SegmentShape = RoundedCornerShape(18.dp)

/** One theme segment. The selected one is a raised body with an accent border. */
@Composable
private fun RowScope.ThemeSegment(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(
                if (selected) Modifier.raisedSurface(SegmentShape).border(1.5.dp, Palette.accent, SegmentShape)
                else Modifier
            )
            .clip(SegmentShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) Palette.onSurface else Palette.muted
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            label, fontSize = 14.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------- accent

private val accentNames = mapOf(
    AccentChoice.BLUE to R.string.accent_blue,
    AccentChoice.GREEN to R.string.accent_green,
    AccentChoice.ORANGE to R.string.accent_orange,
    AccentChoice.RED to R.string.accent_red,
    AccentChoice.RAINBOW to R.string.accent_rainbow,
)

/** Accent: five glass tiles with a jewel each (docs/UI_SPEC.md, "Accent picker"). */
@Composable
private fun AccentRow(selected: AccentChoice, onSelect: (AccentChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Filled.ColorLens)
            Spacer(Modifier.width(14.dp))
            RowTexts(stringResource(R.string.prefs_accent), stringResource(accentNames.getValue(selected)))
        }
        Spacer(Modifier.height(12.dp))
        // 6 dp gaps keep each tile at least 56 dp wide on a 360 dp screen.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AccentChoice.entries.forEach { choice ->
                AccentTile(choice, choice == selected) { onSelect(choice) }
            }
        }
    }
}

private val TileShape = RoundedCornerShape(16.dp)

@Composable
private fun RowScope.AccentTile(choice: AccentChoice, selected: Boolean, onClick: () -> Unit) {
    val dark = Palette.isDark
    val rainbow = choice == AccentChoice.RAINBOW
    val name = stringResource(accentNames.getValue(choice))
    val label = if (rainbow) stringResource(R.string.accent_rgb_short) else name
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduce = rememberReducedMotion()
    val scale by animateFloatAsState(if (pressed && !reduce) 0.97f else 1f, Motion.press(reduce), label = "tile")
    // The tile's own color. The RGB tile follows the live accent, so its border and glow cycle with it.
    val fixed = if (rainbow) null else AccentColors.of(choice, dark).accent
    fun tileColor() = fixed ?: Palette.accent

    Box(Modifier.weight(1f).height(84.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Column(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val path = TileShape.createOutline(size, layoutDirection, this).toPath()
                    if (selected) drawBlurredPath(path, tileColor().copy(alpha = 0.35f), 14.dp)
                    if (!dark) drawBlurredPath(path, Color.Black.copy(alpha = 0.06f), 6.dp, Offset(0f, 2.dp.toPx()))
                    val fill = if (dark) {
                        listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f))
                    } else {
                        listOf(Color.White, Color(0xFFF1F2F5))
                    }
                    drawPath(path, Brush.verticalGradient(fill))
                    val stroke = if (selected) 1.5.dp.toPx() else 1.dp.toPx()
                    val border = when {
                        selected -> tileColor()
                        dark -> Color.White.copy(alpha = 0.08f)
                        else -> Color.Black.copy(alpha = 0.07f)
                    }
                    drawPath(path, border, style = Stroke(width = stroke))
                }
                .clip(TileShape)
                .selectable(
                    selected = selected, interactionSource = interaction, indication = null,
                    role = Role.RadioButton, onClick = onClick,
                )
                .semantics { contentDescription = name },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Jewel(rainbow, fixed, dark)
            Spacer(Modifier.height(8.dp))
            FitText(
                label,
                color = if (selected) Palette.onSurface else Palette.muted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 4.dp).clearAndSetSemantics { },
            )
        }
        if (selected) {
            // The check badge. Together with the border, it shows the selection without color.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .size(20.dp)
                    .drawBehind {
                        drawBlurredPath(Path().apply { addOval(size.toRect()) }, Color.Black.copy(alpha = 0.35f), 3.dp, Offset(0f, 2.dp.toPx()))
                        drawCircle(tileColor())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.onAccent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * One line of 12 sp text that gets smaller, down to 9 sp, when the name does not fit the tile
 * (for example "Помаранчевий" on a 360 dp screen). The full name is still read by screen readers.
 */
@Composable
private fun FitText(text: String, color: Color, fontWeight: FontWeight, modifier: Modifier = Modifier) {
    var fontSize by remember(text) { mutableFloatStateOf(12f) }
    var ready by remember(text) { mutableStateOf(false) }
    Text(
        text, color = color, fontWeight = fontWeight, fontSize = fontSize.sp, maxLines = 1, softWrap = false,
        overflow = if (fontSize <= 9f) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.didOverflowWidth && fontSize > 9f) fontSize -= 0.5f else ready = true
        },
        modifier = modifier.drawWithContent { if (ready) drawContent() },
    )
}

private fun androidx.compose.ui.geometry.Size.toRect() = androidx.compose.ui.geometry.Rect(Offset.Zero, this)

private val wheelDark = listOf(0xFFFF5A5F, 0xFFFFD04A, 0xFF5BD495, 0xFF4FD1FF, 0xFF6F7BFF, 0xFFE85AFF, 0xFFFF5A5F).map(::Color)
private val wheelLight = listOf(0xFFC9272D, 0xFF9A6A00, 0xFF127A46, 0xFF0B7285, 0xFF2563EB, 0xFF9C36B5, 0xFFC9272D).map(::Color)

/** The jewel: a 34 dp ring with a soft inner glow. The RGB jewel ring is a color wheel. */
@Composable
private fun Jewel(rainbow: Boolean, color: Color?, dark: Boolean) {
    Canvas(Modifier.size(34.dp).clearAndSetSemantics { }) {
        val stroke = 3.dp.toPx()
        val radius = size.minDimension / 2f - stroke / 2f
        if (rainbow || color == null) {
            drawCircle(Brush.sweepGradient(if (dark) wheelDark else wheelLight), radius = radius, style = Stroke(width = stroke))
        } else {
            drawBlurredPath(Path().apply { addOval(size.toRect()) }, color.copy(alpha = 0.5f), 5.dp)
            drawCircle(if (dark) Color(0xFF121418) else Color.White, radius = radius)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), radius = size.minDimension * 0.5f))
            drawCircle(color, radius = radius, style = Stroke(width = stroke))
        }
    }
}

// ---------------------------------------------------------------- language

/** The app languages. An empty tag means "the language of the phone". */
private val languages = listOf(
    "" to R.string.language_system,
    "en" to R.string.language_en,
    "uk" to R.string.language_uk,
)

/**
 * App language: System, English or Ukrainian. This uses the per-app language of Android (AppCompat keeps it
 * on Android 12 and earlier). A change recreates the activity, and the screen stays on Preferences.
 */
@Composable
private fun LanguageRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
    val selected = languages.firstOrNull { it.first == current }?.first ?: ""
    val label = stringResource(languages.first { it.first == selected }.second)
    NavRow(Icons.Filled.Language, stringResource(R.string.prefs_language), label) { open = true }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.prefs_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    languages.forEach { (tag, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = tag == selected, role = Role.RadioButton) {
                                    open = false
                                    if (tag != selected) {
                                        AppCompatDelegate.setApplicationLocales(
                                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                                        )
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = tag == selected, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Palette.accent),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(name), fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel), color = Palette.accent) }
            },
        )
    }
}
