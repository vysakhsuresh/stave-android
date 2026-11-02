package com.layerbit.stave.ui.home

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.layerbit.stave.R
import com.layerbit.stave.engine.text.Timecode
import com.layerbit.stave.files.ProjectStore
import com.layerbit.stave.ui.components.AmbientBackdrop
import com.layerbit.stave.ui.components.IconTile
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.Pill
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.RoundIconButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.rise
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents
import java.text.DateFormat
import java.util.Date

/** One of the six jobs the app does; the id is a handle in routes and settings, never shown. */
enum class Tool(val id: String, val titleRes: Int, val hintRes: Int, val iconRes: Int) {
    EDIT("edit", R.string.home_tool_edit, R.string.home_tool_edit_hint, R.drawable.ic_wave),
    VOICE("voice", R.string.home_tool_voice, R.string.home_tool_voice_hint, R.drawable.ic_mic),
    STUDIO("studio", R.string.home_tool_studio, R.string.home_tool_studio_hint, R.drawable.ic_studio),
    MUSIC("music", R.string.home_tool_music, R.string.home_tool_music_hint, R.drawable.ic_stems),
    BEATS("beats", R.string.home_tool_beats, R.string.home_tool_beats_hint, R.drawable.ic_beats),
    FINISH("finish", R.string.home_tool_finish, R.string.home_tool_finish_hint, R.drawable.ic_finish);

    val accent: Color @Composable get() = when (this) {
        EDIT -> ToolAccents.Edit; VOICE -> ToolAccents.Voice; STUDIO -> ToolAccents.Studio
        MUSIC -> ToolAccents.Music; BEATS -> ToolAccents.Beats; FINISH -> ToolAccents.Finish
    }

    companion object {
        fun byId(id: String?): Tool? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The front door. Three big actions - open, record, a new session - then the six tools, then the
 * projects. The waveform mark in the header is the only decoration; the colour is in the tiles.
 */
@Composable
fun HomeScreen(
    projects: List<ProjectStore.Entry>,
    onOpenSounds: () -> Unit,
    onRecord: () -> Unit,
    onNewSession: () -> Unit,
    onOpenProject: (ProjectStore.Entry) -> Unit,
    onRemoveProject: (ProjectStore.Entry) -> Unit,
    onRenameProject: (ProjectStore.Entry, String) -> Unit,
    onDuplicateProject: (ProjectStore.Entry) -> Unit,
    onTool: (String) -> Unit,
    onGuide: () -> Unit,
    onContact: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLibrary: () -> Unit = {},
    onConvert: () -> Unit = {}
) {
    var renaming by remember { mutableStateOf<ProjectStore.Entry?>(null) }
    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.9f)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                // The name and the five doors share one row, evenly spaced; the tagline gets the whole
                // width under them. Squeezed beside the buttons it broke over three lines on a 420 dpi
                // screen, and the buttons sat 0 and 6 dp apart by turns.
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = StaveColors.Text, maxLines = 1, modifier = Modifier.weight(1f))
                        RoundIconButton(iconRes = R.drawable.ic_finish, contentDescription = stringResource(R.string.home_library), onClick = onLibrary)
                        RoundIconButton(iconRes = R.drawable.ic_convert, contentDescription = stringResource(R.string.convert_title), onClick = onConvert)
                        RoundIconButton(iconRes = R.drawable.ic_guide, contentDescription = stringResource(R.string.home_guide), onClick = onGuide)
                        RoundIconButton(iconRes = R.drawable.ic_contact, contentDescription = stringResource(R.string.home_contact), onClick = onContact)
                        RoundIconButton(iconRes = R.drawable.ic_settings, contentDescription = stringResource(R.string.app_settings), onClick = onSettings)
                    }
                    Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = rise(0)) {
                    Pill(stringResource(R.string.app_pill_free), StaveColors.Good)
                    Pill(stringResource(R.string.app_pill_no_internet), StaveColors.Band)
                    Pill(stringResource(R.string.app_pill_no_account), StaveColors.Muted)
                    Pill(stringResource(R.string.app_pill_no_limits), StaveColors.Muted)
                }
            }
            item {
                Panel(modifier = rise(1).fillMaxWidth(), onClick = onOpenSounds, highlighted = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(iconRes = R.drawable.ic_folder, accent = StaveColors.Band, size = 48.dp, filled = true)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(stringResource(R.string.home_open), style = MaterialTheme.typography.titleLarge, color = StaveColors.Text)
                            Text(stringResource(R.string.home_open_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = rise(2)) {
                    PrimaryButton(stringResource(R.string.home_record), onClick = onRecord, iconRes = R.drawable.ic_record, danger = true, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.home_new_session), onClick = onNewSession, iconRes = R.drawable.ic_studio, modifier = Modifier.weight(1f))
                }
            }
            item { SectionHeading(stringResource(R.string.home_tools), modifier = rise(3).padding(top = 6.dp)) }
            item {
                // Six tiles, two a row; a grid inside a column needs its height stated.
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = rise(4).fillMaxWidth().height(3 * 92.dp + 2 * 10.dp), userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(Tool.entries) { tool ->
                        Panel(modifier = Modifier.height(92.dp), onClick = { onTool(tool.id) }, inset = 12.dp) {
                            Row(verticalAlignment = Alignment.Top) {
                                IconTile(iconRes = tool.iconRes, accent = tool.accent, size = 36.dp)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(stringResource(tool.titleRes), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                                    Text(stringResource(tool.hintRes), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = rise(5).padding(top = 6.dp)) {
                    SectionHeading(stringResource(R.string.home_projects), modifier = Modifier.weight(1f))
                    if (projects.isNotEmpty()) Text(pluralStringResource(R.plurals.app_project_count, projects.size, projects.size), style = MaterialTheme.typography.labelSmall, color = StaveColors.Dim)
                }
            }
            if (projects.isEmpty()) {
                item {
                    Panel(modifier = rise(6).fillMaxWidth()) {
                        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
                        Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else {
                projects.forEachIndexed { i, p ->
                    item(key = p.id) {
                        ProjectRow(p, modifier = rise(6 + i), onOpen = { onOpenProject(p) }, onRemove = { onRemoveProject(p) }, onRename = { renaming = p }, onDuplicate = { onDuplicateProject(p) })
                    }
                }
            }
            item {
                TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_about), color = StaveColors.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = StaveColors.Surface,
            title = { Text(stringResource(R.string.home_rename), color = StaveColors.Text) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onRenameProject(p, name.trim()); renaming = null }) { Text(stringResource(R.string.app_save), color = StaveColors.Band) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.app_cancel), color = StaveColors.Muted) } }
        )
    }
}

@Composable
private fun ProjectRow(p: ProjectStore.Entry, modifier: Modifier, onOpen: () -> Unit, onRemove: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val tool = Tool.byId(p.kind) ?: Tool.EDIT
    Panel(modifier = modifier.fillMaxWidth(), onClick = onOpen, onLongClick = { menu = true }, inset = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(iconRes = tool.iconRes, accent = tool.accent, size = 40.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.name.ifBlank { stringResource(R.string.home_project_untitled) }, style = MaterialTheme.typography.titleMedium, color = StaveColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_project_modified, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.modifiedAt))), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
                    if (p.durationMs > 0) Pill(Timecode.display(p.durationMs), StaveColors.Muted, numeric = true)
                    if (p.tracks > 1) Pill(pluralStringResource(R.plurals.app_track_count, p.tracks, p.tracks), StaveColors.Muted)
                }
            }
            Box {
                RoundIconButton(icon = androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = stringResource(R.string.app_more), onClick = { menu = true }, size = 36.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.app_delete), color = StaveColors.Failed) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}
