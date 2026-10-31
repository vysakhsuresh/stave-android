package com.layerbit.stave.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
