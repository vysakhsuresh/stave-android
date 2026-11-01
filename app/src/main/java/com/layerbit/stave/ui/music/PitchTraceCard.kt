package com.layerbit.stave.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.ui.beats.noteName
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The pitch of the selection drawn over time: every 20 ms a dot at the note sung (YIN), the
 * semitone lines behind, the scale-lock's notes brighter when the Beats tab has one. A singer
 * sees where the line sags under the note; a player sees the vibrato's width.
 */
@Composable
fun PitchTraceCard(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val trace by vm.pitchTrace.collectAsState()
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.music_pitch_trace))
        Text(stringResource(R.string.music_pitch_trace_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
        SecondaryButton(stringResource(R.string.music_pitch_trace_go), onClick = vm::tracePitch, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.selection != null && ui.busy == null)
        if (trace.isNotEmpty()) {
            val lo = floor(trace.minOf { it.midi }).toInt() - 1; val hi = ceil(trace.maxOf { it.midi }).toInt() + 1
            val fromMs = trace.first().ms; val toMs = trace.last().ms.coerceAtLeast(fromMs + 1)
            val description = stringResource(R.string.music_pitch_trace_description, noteName(lo + 1), noteName(hi - 1))
            Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp).semantics { contentDescription = description }) {
                val w = size.width; val h = size.height
                drawRect(StaveColors.Canvas)
                for (m in lo..hi) {
                    val y = h - (m - lo).toFloat() / (hi - lo) * h
                    val inScale = vm.inScale(m)
                    drawLine(if (inScale) StaveColors.Border else StaveColors.Border.copy(alpha = 0.35f), Offset(0f, y), Offset(w, y), if (m % 12 == 0) 2f else 1f)
                }
                var prev: Offset? = null
                for (p in trace) {
                    val x = (p.ms - fromMs).toFloat() / (toMs - fromMs) * w
                    val y = h - ((p.midi - lo) / (hi - lo)).toFloat() * h
                    val here = Offset(x, y)
                    // A jump of more than two semitones between neighbours is a new phrase, not a glide.
                    if (prev != null && kotlin.math.abs(prev.y - y) < 2f * h / (hi - lo)) drawLine(StaveColors.Music, prev, here, 2.5f) else drawCircle(StaveColors.Music, 2f, here)
                    prev = here
                }
            }
            Text(stringResource(R.string.music_pitch_trace_range, noteName(lo + 1), noteName(hi - 1)), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
