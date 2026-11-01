package com.layerbit.stave.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.ValueSlider
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors

/**
 * The Musician tab: the tuner, the song pulled apart into stems on their own tracks, a practice
 * copy slowed and transposed, and (once the song is read) its tempo, key and chords.
 */
@Composable
fun MusicPanel(vm: EditorViewModel, ui: EditorViewModel.Ui, modifier: Modifier = Modifier) {
    val voice by vm.voice.collectAsState()
    val music by vm.music.collectAsState()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TunerCard(vm.transport, armed = voice.armed)
        AnalysisCard(vm, ui)
        KaraokeCard(vm, ui)
        PitchTraceCard(vm, ui)
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.music_stems))
            Text(stringResource(R.string.music_stems_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            if (!vm.stemsAvailable) Text(stringResource(R.string.music_stems_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Hot)
            music.separating?.let { p ->
                Text(stringResource(R.string.music_stems_working, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = StaveColors.Band, trackColor = StaveColors.Border)
            }
            PrimaryButton(stringResource(R.string.music_stems_go), onClick = vm::separateStems, enabled = vm.stemsAvailable && music.separating == null && ui.session.length > 0, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            if (music.stemsDone) Text(stringResource(R.string.music_stems_done), style = MaterialTheme.typography.bodySmall, color = StaveColors.Good, modifier = Modifier.padding(top = 6.dp))
        }
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.music_practice))
            Text(stringResource(R.string.music_practice_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            var speed by remember { mutableStateOf(1f) }
            var transpose by remember { mutableStateOf(0f) }
            ValueSlider(stringResource(R.string.music_speed), speed, 0.25f..1.5f, { speed = it }, format = Formats::times)
            ValueSlider(stringResource(R.string.music_transpose), transpose, -12f..12f, { transpose = it }, format = { t -> String.format(java.util.Locale.getDefault(), "%+d st", t.toInt()) })
            PrimaryButton(stringResource(R.string.music_practice_apply), onClick = { vm.practiceCopy(speed.toDouble(), transpose.toInt().toDouble()) }, enabled = ui.session.length > 0 && (speed != 1f || transpose.toInt() != 0), tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.music_to_midi_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            SecondaryButton(stringResource(R.string.music_to_midi), onClick = vm::notesToMidi, enabled = ui.selection != null, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
