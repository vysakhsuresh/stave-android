package com.layerbit.stave.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.ui.components.AmbientBackdrop
import com.layerbit.stave.ui.components.IconTile
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.rise
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents

/** How the app works, in the order a first day goes: open, edit, clean up, words, tracks, songs, finish, privacy. */
@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    val sections = listOf(
        Triple(R.drawable.ic_folder, R.string.guide_open_title, R.string.guide_open_body),
        Triple(R.drawable.ic_wave, R.string.guide_edit_title, R.string.guide_edit_body),
        Triple(R.drawable.ic_wave, R.string.guide_repair_title, R.string.guide_repair_body),
        Triple(R.drawable.ic_mic, R.string.guide_voice_title, R.string.guide_voice_body),
        Triple(R.drawable.ic_studio, R.string.guide_studio_title, R.string.guide_studio_body),
        Triple(R.drawable.ic_stems, R.string.guide_music_title, R.string.guide_music_body),
        Triple(R.drawable.ic_beats, R.string.guide_beats_title, R.string.guide_beats_body),
        Triple(R.drawable.ic_finish, R.string.guide_finish_title, R.string.guide_finish_body),
        Triple(R.drawable.ic_lock, R.string.guide_privacy_title, R.string.guide_privacy_body)
    )
    // One accent per section above, in the same order; a ninth section without a ninth colour would read past the list.
    val accents = listOf(StaveColors.Band, ToolAccents.Edit, ToolAccents.Edit, ToolAccents.Voice, ToolAccents.Studio, ToolAccents.Music, ToolAccents.Beats, ToolAccents.Finish, StaveColors.Good)
    Box(modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.7f)
        LazyColumn(modifier = Modifier.fillMaxWidth().testTag("guideList"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted, modifier = Modifier.padding(bottom = 4.dp)) }
            items(sections.size) { i ->
                val (icon, title, body) = sections[i]
                Panel(modifier = rise(i).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconTile(iconRes = icon, accent = accents[i])
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.guide_outro), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
