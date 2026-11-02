package com.layerbit.stave.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.stave.ui.theme.Numerals
import com.layerbit.stave.ui.theme.StaveColors
import java.util.Locale

/**
 * The handful of shapes every screen is built from. One definition of a panel, a heading, a
 * button, a chip and a switch, so screens do not each invent their own padding - and so the
 * whole app changes character when one of these does. Carried from Sheaf and Still, with the
 * audio-specific pieces (meters, knobs, faders) in their own files beside this one.
 */

val PanelShape = RoundedCornerShape(18.dp)
val ButtonShape = RoundedCornerShape(14.dp)
val ChipShape = RoundedCornerShape(999.dp)

/** A press that gives a little, which is most of what makes a surface feel like a button. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, amount: Float = 0.975f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) amount else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    return this.scale(scale)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    /** When the panel is one of a set the person picks from: its selected state is spoken, and it is drawn highlighted when true. */
    selected: Boolean? = null,
    /** A tint behind the panel: a success, a warning, a group's colour. */
    tint: Color? = null,
    inset: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val surface = tint?.let { it.copy(alpha = if (StaveColors.isDark) 0.14f else 0.10f) } ?: StaveColors.Surface
    val border = when {
        onClick != null && focused -> StaveColors.Band
        onClick != null && hovered -> StaveColors.BorderStrong
        highlighted || selected == true -> StaveColors.Band.copy(alpha = 0.6f)
        tint != null -> tint.copy(alpha = 0.35f)
        else -> StaveColors.Border
    }
    var base = modifier
    if (onClick != null) base = base.pressScale(interaction)
    base = base
        .clip(PanelShape)
        .background(StaveColors.Surface)
        .then(if (tint != null) Modifier.background(surface) else Modifier)
        .border(if (onClick != null && focused) 2.dp else 1.dp, border, PanelShape)
    // A panel that marks the current choice by its border alone must say so: `selectable`, so the
    // mixer's strip reads "selected" and not just "button" (a thing you choose says it is chosen).
    val tappable = when {
        onClick == null -> base
        selected != null -> base.selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
        onLongClick == null -> base.clickable(interactionSource = interaction, indication = null, onClick = onClick)
        else -> base.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
    }
    Column(modifier = tappable.padding(inset), content = content)
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (accent != null) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Box(Modifier.size(8.dp))
        }
        Text(text = text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = StaveColors.Dim, letterSpacing = 1.4.sp)
    }
}

/** A screen's own title, bigger than the bar's. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = StaveColors.Text)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Label above, value below - the pairing used for file details throughout. */
@Composable
fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier, numeric: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionHeading(label)
        Text(text = value, style = if (numeric) Numerals.copy(fontSize = 13.sp) else MaterialTheme.typography.bodyMedium, color = StaveColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RowBetween(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * The one coloured button on a screen: what the screen is for. The band runs as a gradient
 * rather than a flat fill, which is what makes it read as a thing with weight. Disabled, it goes
 * quiet rather than grey, so the eye still knows where the action will be.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    tall: Boolean = true,
    /** Red for Record and Delete, the two actions that are not the band's. */
    danger: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val a = if (danger) StaveColors.Record else StaveColors.Band
    val b = if (danger) StaveColors.Record.copy(alpha = 0.85f) else StaveColors.BandBright
    val brush = if (enabled) Brush.horizontalGradient(listOf(a, b)) else Brush.horizontalGradient(listOf(StaveColors.SurfaceDim, StaveColors.SurfaceDim))
    Row(
        modifier = modifier
            .pressScale(interaction)
            .height(if (tall) 52.dp else 40.dp)
            .clip(ButtonShape)
            .background(brush)
            .then(if (!enabled) Modifier.border(1.dp, StaveColors.Border, ButtonShape) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (!enabled) StaveColors.Dim else if (danger) Color.White else StaveColors.OnBand
        if (icon != null) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        else if (iconRes != null) { Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

/** The quieter sibling: outlined, for the second-best thing to do. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    tall: Boolean = true,
    /** Red text for a quiet destructive action (Clear, Remove), the outline kept. */
    danger: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val tint = if (!enabled) StaveColors.Dim else if (danger) StaveColors.Record else StaveColors.Text
    Row(
        modifier = modifier
            .pressScale(interaction)
            .height(if (tall) 52.dp else 40.dp)
            .clip(CellShape)
            .background(StaveColors.Surface)
            .border(1.dp, StaveColors.BorderStrong, CellShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)); Box(Modifier.size(8.dp)) }
        else if (iconRes != null) { Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = tint, maxLines = 1)
    }
}

/** A circular icon button on a quiet surface. The label is required: a round button is nothing but an icon. */
@Composable
fun RoundIconButton(
    icon: ImageVector? = null,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    tint: Color = StaveColors.Muted,
    filled: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.pressScale(interaction, 0.92f).size(size)
            .background(if (filled) StaveColors.Band else StaveColors.Surface, CircleShape)
            .border(1.dp, if (filled) StaveColors.Band else StaveColors.Border, CircleShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        val colour = if (!enabled) StaveColors.Dim else if (filled) StaveColors.OnBand else tint
        if (icon != null) Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.55f))
        else if (iconRes != null) Icon(painterResource(iconRes), contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.55f))
    }
}

/** A small coloured square with an icon in it: the mark every tool and file carries. */
@Composable
fun IconTile(iconRes: Int, accent: Color, modifier: Modifier = Modifier, size: Dp = 40.dp, filled: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.3f)
    val ink = if (filled) Color.White else accent
    Box(
        modifier = modifier.size(size).clip(shape).background(
            if (filled) Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.75f)))
            else Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.12f))),
            shape
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.52f))
    }
}

/** A word in a capsule: a state, a file type, a count. */
@Composable
fun Pill(text: String, colour: Color, modifier: Modifier = Modifier, filled: Boolean = false, numeric: Boolean = false) {
    Text(
        text = text,
        // One line, always: at a large font size "No limits" broke a letter at a time.
        maxLines = 1, softWrap = false,
        style = if (numeric) Numerals else MaterialTheme.typography.labelSmall,
        color = if (filled) Color.White else colour,
        modifier = modifier.background(if (filled) colour else colour.copy(alpha = 0.16f), ChipShape).padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/**
 * A row of mutually exclusive choices, as chips. The chosen chip is solid band with dark type,
 * so the answer to a question is visible from across the room. Each chip is `selectable`, not
 * `clickable`: a tint is not a state a screen reader can hear (Squish's sweep ten).
 */
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: (T) -> Boolean = { true }) {
    EvenGrid(options.map { it.second }, modifier) { i, cellModifier, fontSize ->
        val (value, label) = options[i]
        ChoiceCell(label, value == selected, enabled(value), { onSelect(value) }, cellModifier, fontSize)
    }
}

/** One button in an [ActionGrid]. A [danger] action (Clear, Delete) has red type. */
class GridAction(val label: String, val onClick: () -> Unit, val enabled: Boolean = true, val danger: Boolean = false)

/** Several actions side by side, laid out as [EvenGrid] cells: one size, one line, no stones. */
@Composable
fun ActionGrid(actions: List<GridAction>, modifier: Modifier = Modifier) {
    EvenGrid(actions.map { it.label }, modifier) { i, cellModifier, fontSize -> ActionCell(actions[i], cellModifier, fontSize) }
}

/**
 * An even grid: every cell the same width and height, the columns set by the typical label
 * (the 80th percentile, at most four) and balanced so the rows fill evenly (nine are three by
 * three, not five and four); a label longer than its cell shrinks its type to fit rather than
 * widening every cell. Chips and buttons of their own widths wrapping freely looked, in the
 * owner's words, like stones of different sizes thrown on the ground; 26 languages were
 * thirteen rows of two while the widest label set the columns.
 */
@Composable
fun EvenGrid(labels: List<String>, modifier: Modifier = Modifier, cell: @Composable (index: Int, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit) -> Unit) {
    if (labels.isEmpty()) return
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gap = 8.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widths = remember(labels, style) { labels.map { measurer.measure(it, style, maxLines = 1).size.width } }
        val typical = widths.sorted()[((widths.size - 1) * 4) / 5]
        val cellMin = with(density) { typical.toDp() } + 28.dp
        val fit = (((maxWidth + gap) / (cellMin + gap)).toInt()).coerceIn(1, minOf(4, labels.size))
        val rows = (labels.size + fit - 1) / fit
        val columns = (labels.size + rows - 1) / rows
        val textRoom = with(density) { ((maxWidth - gap * (columns - 1)) / columns - 16.dp).toPx() }
        val sizes = labels.indices.map { i -> if (widths[i] <= textRoom) style.fontSize else (style.fontSize * (textRoom / widths[i]) * 0.97f).let { if (it.value < 10f) 10.sp else it } }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            labels.indices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { i -> cell(i, Modifier.weight(1f), sizes[i]) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val CellShape = RoundedCornerShape(10.dp)

@Composable
private fun ChoiceCell(label: String, active: Boolean, on: Boolean, onClick: () -> Unit, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit) {
    val fill by animateColorAsState(if (active) StaveColors.Band else Color.Transparent, label = "chip")
    val ink by animateColorAsState(if (!on) StaveColors.Dim else if (active) StaveColors.OnBand else StaveColors.Text, label = "chipInk")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(40.dp)
            .pressScale(interaction, 0.97f)
            .clip(CellShape)
            .background(fill, CellShape)
            .border(1.dp, if (active) StaveColors.Band else StaveColors.Border, CellShape)
            .selectable(selected = active, enabled = on, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize), color = ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ActionCell(action: GridAction, modifier: Modifier, fontSize: androidx.compose.ui.unit.TextUnit) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (!action.enabled) StaveColors.Dim else if (action.danger) StaveColors.Record else StaveColors.Text
    Box(
        modifier = modifier
            .height(40.dp)
            .pressScale(interaction, 0.97f)
            .clip(CellShape)
            .background(StaveColors.SurfaceDim.copy(alpha = 0.5f), CellShape)
            .border(1.dp, StaveColors.Border, CellShape)
            .clickable(enabled = action.enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = action.onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = action.label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize), color = ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}


/** A setting that is on or off, with the sentence that says what it costs. `toggleable`, so the state is spoken. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true) {
    Row(
        modifier = modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) StaveColors.Text else StaveColors.Dim)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = StaveColors.OnBand, checkedTrackColor = StaveColors.Band,
                uncheckedThumbColor = StaveColors.Muted, uncheckedTrackColor = StaveColors.SurfaceDim, uncheckedBorderColor = StaveColors.BorderStrong
            )
        )
    }
}

/** Human file sizes. Base 1024 with the shorter unit names, matching what Android's own file managers show. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/**
 * Fades a horizontally scrolled row out at whichever edge has more beyond it, so a chip cut in
 * half reads as "this scrolls" rather than as a layout mistake. Goes before `horizontalScroll`.
 */
fun Modifier.edgeFade(state: androidx.compose.foundation.ScrollState, width: androidx.compose.ui.unit.Dp = 28.dp): Modifier =
    this.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = width.toPx().coerceAtMost(size.width / 4)
            if (state.value > 0) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black), startX = 0f, endX = fade),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
            )
            if (state.value < state.maxValue) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Transparent), startX = size.width - fade, endX = size.width),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn
            )
        }
