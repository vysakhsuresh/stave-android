package com.layerbit.stave.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.engine.text.Timecode
import com.layerbit.stave.ui.components.LabelledValue
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.Pill
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents
import java.util.Locale

/** Tempo, key and chords read off the song, with the click and the bar markers that follow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisCard(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val music by vm.music.collectAsState()
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.music_analyse))
        Text(stringResource(R.string.music_analyse_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
        PrimaryButton(stringResource(R.string.music_analyse), onClick = vm::analyseSong, enabled = ui.session.length > 0 && ui.busy == null, tall = false, modifier = Modifier.fillMaxWidth())
        val a = music.analysis
        if (a != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                LabelledValue(stringResource(R.string.music_tempo), if (a.bpm > 0) stringResource(R.string.music_bpm, String.format(Locale.getDefault(), "%.1f", a.bpm)) else "—", Modifier.weight(1f), numeric = true)
                LabelledValue(stringResource(R.string.music_key), a.key ?: "—", Modifier.weight(1f))
            }
            Text(stringResource(R.string.music_confidence, (a.confidence * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 4.dp))
            if (a.chords.isNotEmpty()) {
                SectionHeading(stringResource(R.string.music_chords), modifier = Modifier.padding(top = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    for (c in a.chords.take(64)) {
                        val under = ui.window.playhead * 1000 / ui.session.sampleRate in c.startMs until c.endMs
                        Pill("${c.name} · ${Timecode.display(c.startMs)}", if (under) StaveColors.Band else StaveColors.Muted, filled = under)
                    }
                }
                // The chord under the play-head as a guitar shape, when the engine knows one.
                val now = ui.window.playhead * 1000 / ui.session.sampleRate
                val current = a.chords.lastOrNull { it.startMs <= now } ?: a.chords.first()
                current.shape?.let { shape ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                        ChordDiagram(shape, Modifier.size(width = 84.dp, height = 96.dp))
                        Text(stringResource(R.string.music_chord_shape, current.name), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                SecondaryButton(stringResource(R.string.music_half_tempo), onClick = { vm.tempoOctave(false) }, enabled = a.beatsMs.size >= 4, tall = false, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.music_double_tempo), onClick = { vm.tempoOctave(true) }, enabled = a.beatsMs.size >= 2, tall = false, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SecondaryButton(stringResource(R.string.music_markers_from_beats), onClick = vm::markBars, enabled = a.beatsMs.isNotEmpty(), tall = false, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.music_shift_bar), onClick = vm::shiftDownbeat, enabled = a.beatsMs.size >= 4, tall = false, modifier = Modifier.weight(1f))
            }
            ToggleRow(stringResource(R.string.music_click_to_beats), music.clickFollows, { vm.setClickFollowsBeats(it) }, enabled = a.bpm > 0)
        }
    }
}

/**
 * A guitar chord shape: six strings low to high across, the first four frets down, a dot where
 * a finger goes, "o" over an open string and "×" over a muted one. The whole thing is one
 * named node for accessibility, reading its frets as numbers.
 */
@Composable
fun ChordDiagram(frets: IntArray, modifier: Modifier = Modifier) {
    val description = frets.joinToString(" ") { if (it < 0) "x" else it.toString() }
    Canvas(modifier.semantics { contentDescription = "Chord shape $description" }) {
        val w = size.width; val h = size.height
        val left = w * 0.12f; val right = w * 0.88f; val top = h * 0.2f; val bottom = h * 0.95f
        val strings = 6; val fretsShown = 4
        val sx = (right - left) / (strings - 1); val fy = (bottom - top) / fretsShown
        // The lowest fretted position decides where the window starts (a barre at the 5th fret is drawn from the 5th).
        val fretted = frets.filter { it > 0 }
        val base = if (fretted.isEmpty() || fretted.min() <= 1) 1 else fretted.min()
        drawLine(StaveColors.Text, Offset(left, top), Offset(right, top), if (base == 1) 5f else 1.5f)
        for (f in 1..fretsShown) drawLine(StaveColors.Border, Offset(left, top + f * fy), Offset(right, top + f * fy), 1.5f)
        for (sIdx in 0 until strings) drawLine(StaveColors.Muted, Offset(left + sIdx * sx, top), Offset(left + sIdx * sx, bottom), 1.5f)
        for (sIdx in 0 until strings) {
            val x = left + sIdx * sx
            val f = frets.getOrElse(sIdx) { -1 }
            when {
                f < 0 -> { drawLine(StaveColors.Hot, Offset(x - 4f, top - 16f), Offset(x + 4f, top - 8f), 2f); drawLine(StaveColors.Hot, Offset(x + 4f, top - 16f), Offset(x - 4f, top - 8f), 2f) }
                f == 0 -> drawCircle(StaveColors.Muted, 4f, Offset(x, top - 12f), style = Stroke(1.5f))
                else -> { val row = f - base; if (row in 0 until fretsShown) drawCircle(ToolAccents.Music, 6f, Offset(x, top + (row + 0.5f) * fy)) }
            }
        }
    }
}
