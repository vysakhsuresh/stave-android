package com.layerbit.stave.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors

/**
 * Sing along: the transcript's words in lines of seven, the line under the play-head large with
 * the word being sung lit and the ones sung dimmed, the next line under it; and the Vocals stem
 * muted with a switch once the song has been pulled apart. The words are the Voice tab's
 * transcript, so a song transcribed once is a karaoke screen for nothing more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KaraokeCard(vm: EditorViewModel, ui: EditorViewModel.Ui) {
    val voice by vm.voice.collectAsState()
    val t = voice.transcript
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.music_karaoke))
        Text(stringResource(R.string.music_karaoke_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
        val lyrics = voice.lyrics
        val ms = if (ui.session.sampleRate > 0) ui.window.playhead * 1000 / ui.session.sampleRate else 0L
        if (lyrics.isNotEmpty()) {
            // The typed lines, placed by the alignment: the line under the play-head large, the one before dimmed, the next waiting.
            val placed = lyrics.withIndex().filter { it.value.isPlaced }
            val currentIndex = placed.lastOrNull { it.value.startMs!! <= ms }?.index ?: 0
            for (i in (currentIndex - 1).coerceAtLeast(0)..(currentIndex + 2).coerceAtMost(lyrics.size - 1)) {
                val line = lyrics[i]
                Text(line.text, style = if (i == currentIndex) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                    color = when { i < currentIndex -> StaveColors.Dim; i == currentIndex -> StaveColors.Music; else -> StaveColors.Text }, modifier = Modifier.padding(top = 4.dp))
            }
        } else if (t == null || t.isEmpty) Text(stringResource(R.string.music_karaoke_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted)
        else {
            val words = t.words
            val current = words.indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
            val perLine = 7
            val lineStart = (current / perLine) * perLine
            val line = words.subList(lineStart, minOf(words.size, lineStart + perLine))
            val next = words.subList(minOf(words.size, lineStart + perLine), minOf(words.size, lineStart + 2 * perLine))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 6.dp)) {
                line.forEachIndexed { i, w ->
                    val index = lineStart + i
                    Text(w.text, style = MaterialTheme.typography.headlineSmall, color = when { index < current -> StaveColors.Dim; index == current -> StaveColors.Music; else -> StaveColors.Text })
                }
            }
            if (next.isNotEmpty()) Text(next.joinToString(" ") { it.text }, style = MaterialTheme.typography.bodyLarge, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
        }
        val vocals = vm.vocalsTrackId()
        ToggleRow(stringResource(R.string.music_mute_vocals), vocals?.let { ui.session.track(it)?.muted } ?: false, { vm.muteVocals(it) }, enabled = vocals != null, detail = if (vocals == null) stringResource(R.string.music_karaoke_no_vocals) else null)
    }
}
