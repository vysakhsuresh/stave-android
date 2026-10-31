package com.layerbit.stave.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.layerbit.stave.R
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.engine.text.Timecode
import com.layerbit.stave.ui.components.ChipShape
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PanelShape
import com.layerbit.stave.ui.components.RoundIconButton
import com.layerbit.stave.ui.components.pressScale
import com.layerbit.stave.ui.theme.Clock
import com.layerbit.stave.ui.theme.StaveColors
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable

/** The editor route: builds the view model for the project and shows the screen. */
@Composable
fun EditorRoute(projectId: String, startTool: String?, onBack: () -> Unit, onAddSounds: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as StaveApplication
    val vm: EditorViewModel = viewModel(key = projectId, factory = viewModelFactory { initializer { EditorViewModel(app, projectId) } })
    LaunchedEffect(startTool) { startTool?.let { EditorTab.entries.firstOrNull { t -> t.id == it } }?.let(vm::setTab) }
    EditorScreen(vm, onBack = { vm.saveNow(); onBack() }, onAddSounds = onAddSounds)
}

/**
 * The editor: a bar, the tabs, the strip, the transport, the tool rail and the open sheet. The
 * strip is the one thing that is always there; everything else folds away under it.
 */
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onAddSounds: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val transport by vm.transport.state.collectAsState()
    LaunchedEffect(transport.position, transport.playing) { if (transport.playing) vm.followPlayhead(transport.position) }
    BackHandler(enabled = ui.sheet != null) { vm.setSheet(null) }
    var renaming by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(StaveColors.Background)) {
        // Bar: back, the name, undo and redo.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back), onClick = onBack)
            Text(
                ui.session.name.ifBlank { stringResource(R.string.editor_untitled) }, style = MaterialTheme.typography.titleLarge, color = StaveColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            RoundIconButton(iconRes = R.drawable.ic_redo, contentDescription = stringResource(R.string.app_undo) + (ui.undoLabel?.let { " $it" } ?: ""), onClick = vm::undo, enabled = ui.canUndo, size = 36.dp)
            RoundIconButton(iconRes = R.drawable.ic_redo_fwd, contentDescription = stringResource(R.string.app_redo), onClick = vm::redo, enabled = ui.canRedo, size = 36.dp)
            RoundIconButton(icon = Icons.Filled.Add, contentDescription = stringResource(R.string.editor_add), onClick = onAddSounds, size = 36.dp)
        }
        TabRow(ui.tab, vm::setTab)
        // The strip takes what is left above the transport and the sheet.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp).clip(PanelShape)) {
            WaveformStrip(
                session = ui.session, window = ui.window, selection = ui.selection, selectedTrackId = ui.selectedTrackId,
                peaksFor = vm::peaksFor, onWindow = vm::setWindow, onSelect = vm::select, onSelectTrack = vm::selectTrack,
                onSeek = vm::seek, onFit = vm::fit, onWidth = vm::setWidth
            )
            ui.busy?.let { b ->
                Box(Modifier.fillMaxSize().background(StaveColors.Background.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Panel { Text(b.label, style = MaterialTheme.typography.titleMedium, color = StaveColors.Text); LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = StaveColors.Band, trackColor = StaveColors.Border) }
                }
            }
            if (!ui.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = StaveColors.Band) }
        }
        TransportBar(vm, ui, transport.playing)
        AnimatedContent(targetState = ui.tab, label = "tab") { tab ->
            when (tab) {
                EditorTab.EDIT -> Column {
                    ToolRail(ui.sheet, vm::setSheet)
                    AnimatedVisibility(visible = ui.sheet != null, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Box(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(horizontal = 8.dp, vertical = 6.dp).clip(PanelShape).background(StaveColors.Surface).padding(14.dp)) {
                            Column(Modifier.verticalScroll(rememberScrollState())) { ui.sheet?.let { EditSheetContent(it, vm, ui) } }
                        }
                    }
                }
                else -> ComingPanel(tab)
            }
        }
        ui.message?.let { m ->
            Snackbar(modifier = Modifier.padding(8.dp), containerColor = StaveColors.Surface, contentColor = StaveColors.Text, action = { TextButton(onClick = vm::dismissMessage) { Text(stringResource(R.string.app_ok), color = StaveColors.Band) } }) { Text(m) }
        }
    }
}

@Composable
private fun TabRow(current: EditorTab, onTab: (EditorTab) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (tab in EditorTab.entries) {
            val active = tab == current
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier.pressScale(interaction, 0.96f).background(if (active) StaveColors.Band else StaveColors.Surface, ChipShape).border(1.dp, if (active) StaveColors.Band else StaveColors.Border, ChipShape)
                    .selectable(selected = active, interactionSource = interaction, indication = null, role = Role.Tab) { onTab(tab) }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(painterResource(tab.iconRes), contentDescription = null, tint = if (active) StaveColors.OnBand else StaveColors.Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(tab.titleRes), style = MaterialTheme.typography.labelMedium, color = if (active) StaveColors.OnBand else StaveColors.Muted)
            }
        }
    }
}

@Composable
private fun TransportBar(vm: EditorViewModel, ui: EditorViewModel.Ui, playing: Boolean) {
    val rate = ui.session.sampleRate
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Timecode.display(Timecode.ofSamples(ui.window.playhead, rate), 2), style = Clock, color = StaveColors.Text, modifier = Modifier.weight(1f))
        ui.selection?.let { s ->
            Text(Timecode.display(Timecode.ofSamples(s.last + 1 - s.first, rate), 2), style = MaterialTheme.typography.labelMedium.copy(fontFamily = Clock.fontFamily), color = StaveColors.Band)
        }
        RoundIconButton(iconRes = R.drawable.ic_zoom_out, contentDescription = stringResource(R.string.editor_zoom_out), onClick = { vm.zoomBy(0.5f) }, size = 36.dp)
        RoundIconButton(iconRes = R.drawable.ic_zoom_in, contentDescription = stringResource(R.string.editor_zoom_in), onClick = { vm.zoomBy(2f) }, size = 36.dp)
        RoundIconButton(iconRes = R.drawable.ic_fit, contentDescription = stringResource(R.string.editor_fit), onClick = vm::fit, size = 36.dp)
        RoundIconButton(
            icon = if (playing) null else Icons.Filled.PlayArrow, iconRes = if (playing) R.drawable.ic_pause else null,
            contentDescription = stringResource(if (playing) R.string.editor_pause else R.string.editor_play), onClick = vm::togglePlay, filled = true, size = 48.dp
        )
    }
}

@Composable
private fun ToolRail(open: EditSheet?, onSheet: (EditSheet?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (sheet in EditSheet.entries) {
            val active = sheet == open
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.pressScale(interaction, 0.96f).background(if (active) StaveColors.BandDim else StaveColors.Surface, ChipShape).border(1.dp, if (active) StaveColors.Band else StaveColors.Border, ChipShape)
                    .selectable(selected = active, interactionSource = interaction, indication = null, role = Role.Tab) { onSheet(sheet) }.padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text(stringResource(sheet.titleRes), style = MaterialTheme.typography.labelMedium, color = if (active) StaveColors.BandBright else StaveColors.Muted) }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** A pillar whose engine exists and whose screen does not yet: says so, honestly, with the version. */
@Composable
private fun ComingPanel(tab: EditorTab) {
    val version = when (tab) { EditorTab.VOICE -> "v0.2"; EditorTab.STUDIO -> "v0.3"; EditorTab.MUSIC -> "v0.4"; EditorTab.BEATS -> "v0.5"; EditorTab.FINISH -> "v0.7"; EditorTab.EDIT -> "" }
    Panel(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(stringResource(tab.titleRes), style = MaterialTheme.typography.titleMedium, color = StaveColors.Text)
        Text(stringResource(R.string.editor_coming, version), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 4.dp))
    }
}
