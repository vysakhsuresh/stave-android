package com.layerbit.stave.ui.beats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.stave.engine.sequencer.Pattern
import com.layerbit.stave.engine.sequencer.Ticks
import com.layerbit.stave.ui.components.ChipShape
import com.layerbit.stave.ui.theme.StaveColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** A row of the grid: a pad or a pitch, with the name on its left. */
data class GridRow(val pitch: Int, val label: String, val dark: Boolean = false)

/**
 * The step sequencer and the piano roll in one canvas: rows are pads or pitches, columns the
 * steps of the grid's division, a note a rounded bar from its start to its end, lit by its
 * velocity. A tap toggles a step; a vertical drag that starts on a note sets its velocity
 * (up is louder), so the usual accent dance of long-press menus is not needed. The loop's
 * play-head is a column lit behind the notes.
 *
 * Drawn, not laid out: a four-bar 32nd-note grid is 128 columns by 24 rows, which is three
 * thousand composables as buttons and one draw call here. The grid names itself to
 * accessibility as a whole, with the pattern's count of notes, since each cell cannot be a
 * node; the pads and keys below the grid are the accessible way to play.
 */
@Composable
fun StepGrid(
    pattern: Pattern,
    rows: List<GridRow>,
    division: Int,
    playTick: Long,
    description: String,
    onToggle: (pitch: Int, step: Int) -> Unit,
    onVelocity: (pitch: Int, step: Int, velocity: Int) -> Unit,
    onVelocityDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = pattern.stepCount(division).coerceAtLeast(1)
    val stepTicks = Ticks.perStep(division)
    val rowDp = 24.dp
    val labelDp = 64.dp
    val cellDp = (if (steps <= 16) 26 else if (steps <= 32) 20 else 16).dp
    val density = LocalDensity.current
    val rowPx = with(density) { rowDp.toPx() }
    val cellPx = with(density) { cellDp.toPx() }
    val current by rememberUpdatedState(pattern)
    val currentRows by rememberUpdatedState(rows)
    val beatsPerBar = 4
    val stepsPerBeat = (division / 4).coerceAtLeast(1)

    Row(modifier.fillMaxWidth()) {
        Column(Modifier.width(labelDp)) {
            for (r in rows) {
                Box(Modifier.height(rowDp).fillMaxWidth().padding(end = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(r.label, style = MaterialTheme.typography.labelSmall, color = if (r.dark) StaveColors.Dim else StaveColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Canvas(
                Modifier
                    .width(cellDp * steps)
                    .height(rowDp * rows.size)
                    .clip(ChipShape)
                    .background(StaveColors.SurfaceDim)
                    .semantics { contentDescription = description }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val row = currentRows.getOrNull((down.position.y / rowPx).toInt()) ?: return@awaitEachGesture
                            val step = (down.position.x / cellPx).toInt()
                            if (step !in 0 until current.stepCount(division)) return@awaitEachGesture
                            val onNote = current.notesInStep(row.pitch, step, division).firstOrNull()
                            var startVelocity = onNote?.velocity ?: 100
                            var dragging = false
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (dragging) onVelocityDone() else onToggle(row.pitch, step)
                                    break
                                }
                                val dy = change.position.y - down.position.y
                                val dx = change.position.x - down.position.x
                                if (onNote != null && !dragging && abs(dy) > slop && abs(dy) > abs(dx)) dragging = true
                                if (dragging) {
                                    change.consume()
                                    // A row's height is 40 of velocity: three rows from a mezzo-forte to the top.
                                    val v = (startVelocity - dy / rowPx * 40).roundToInt().coerceIn(1, 127)
                                    onVelocity(row.pitch, step, v)
                                }
                            }
                        }
                    }
            ) {
                val w = size.width; val h = size.height
                // Beats shaded in pairs, bars marked by a stronger line.
                for (s in 0 until steps) {
                    val beat = s / stepsPerBeat
                    if (beat % 2 == 1) drawRect(StaveColors.Surface.copy(alpha = 0.5f), Offset(s * cellPx, 0f), Size(cellPx, h))
                }
                for ((i, r) in rows.withIndex()) {
                    if (r.dark) drawRect(Color.Black.copy(alpha = 0.10f), Offset(0f, i * rowPx), Size(w, rowPx))
                }
                // The play-head column.
                if (playTick >= 0 && pattern.lengthTicks > 0) {
                    val playStep = (playTick / stepTicks).toInt()
                    if (playStep in 0 until steps) drawRect(StaveColors.Beats.copy(alpha = 0.18f), Offset(playStep * cellPx, 0f), Size(cellPx, h))
                }
                // Lines.
                for (s in 0..steps) {
                    val bar = s % (stepsPerBeat * beatsPerBar) == 0
                    val beat = s % stepsPerBeat == 0
                    drawLine(if (bar) StaveColors.BorderStrong else if (beat) StaveColors.Border else StaveColors.Border.copy(alpha = 0.4f), Offset(s * cellPx, 0f), Offset(s * cellPx, h), if (bar) 2f else 1f)
                }
                for (i in 0..rows.size) drawLine(StaveColors.Border.copy(alpha = 0.6f), Offset(0f, i * rowPx), Offset(w, i * rowPx), 1f)
                // Notes.
                val index = HashMap<Int, Int>()
                rows.forEachIndexed { i, r -> index[r.pitch] = i }
                val pad = 2.5f
                for (n in pattern.notes) {
                    val i = index[n.pitch] ?: continue
                    val x0 = n.startTick.toFloat() / stepTicks * cellPx
                    val x1 = (n.endTick.toFloat() / stepTicks * cellPx).coerceAtMost(w)
                    if (x0 >= w) continue
                    val alpha = 0.35f + 0.65f * (n.velocity / 127f)
                    drawRoundRect(StaveColors.Beats.copy(alpha = alpha), Offset(x0 + pad, i * rowPx + pad), Size((x1 - x0 - 2 * pad).coerceAtLeast(cellPx * 0.4f), rowPx - 2 * pad), CornerRadius(5f, 5f))
                    if (n.probability < 1.0) drawRoundRect(StaveColors.SurfaceDim.copy(alpha = 0.6f), Offset(x0 + pad, i * rowPx + rowPx * 0.55f), Size((x1 - x0 - 2 * pad) * (1 - n.probability.toFloat()), rowPx * 0.45f - pad), CornerRadius(4f, 4f))
                    if (n.ratchet > 1) for (k in 1 until n.ratchet) { val xr = x0 + (x1 - x0) * k / n.ratchet; drawLine(StaveColors.SurfaceDim, Offset(xr, i * rowPx + pad), Offset(xr, (i + 1) * rowPx - pad), 1.5f) }
                }
            }
        }
    }
}
