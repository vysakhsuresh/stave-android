package com.layerbit.stave.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.layerbit.stave.R
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.engine.text.Timecode
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.ChipShape
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.LevelMeter
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.ValueSlider
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents

/**
 * The Voice tab: a recorder with a meter, the transcript as words you can select and delete,
 * and the one-tap clean-up chain. Every button here is a Session edit or an offline render
 * through the same view model as the Edit tab, so Undo is one stack.
 */
@Composable
fun VoicePanel(vm: EditorViewModel, ui: EditorViewModel.Ui, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val voice by vm.voice.collectAsState()
    val transport by vm.transport.state.collectAsState()
    var hasMic by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasMic = granted; refused = !granted; if (granted) vm.armInput() }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- Record ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.voice_record_title))
            Spacer(Modifier.height(8.dp))
            LevelMeter(peakLinear = transport.inputPeak, holdLinear = voice.inputHold, modifier = Modifier.fillMaxWidth().height(12.dp), label = stringResource(R.string.voice_level))
            if (!hasMic) Text(stringResource(if (refused) R.string.voice_permission_denied else R.string.voice_permission), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
            if (voice.armed) Text(stringResource(R.string.voice_latency, Formats.ms(voice.roundTripMs.toFloat())), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                if (!voice.armed) SecondaryButton(stringResource(R.string.voice_arm), onClick = { if (hasMic) vm.armInput() else ask.launch(Manifest.permission.RECORD_AUDIO) }, tall = false, modifier = Modifier.weight(1f))
                else SecondaryButton(stringResource(R.string.voice_disarm), onClick = vm::disarmInput, tall = false, modifier = Modifier.weight(1f), enabled = !transport.recording)
                if (!transport.recording) PrimaryButton(stringResource(R.string.voice_record), onClick = { if (hasMic) vm.startRecording() else ask.launch(Manifest.permission.RECORD_AUDIO) }, danger = true, tall = false, modifier = Modifier.weight(1f), enabled = hasMic || !voice.armed)
                else {
                    SecondaryButton(stringResource(if (voice.recordPaused) R.string.voice_resume else R.string.voice_pause), onClick = { vm.pauseRecording(!voice.recordPaused) }, tall = false, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.voice_stop), onClick = vm::stopRecording, tall = false, modifier = Modifier.weight(1f))
                }
            }
            if (transport.recording) Text(stringResource(R.string.voice_recording, Timecode.display(voice.recordedMs, 1)), style = MaterialTheme.typography.titleMedium, color = StaveColors.Record, modifier = Modifier.padding(top = 8.dp))
            ToggleRow(stringResource(R.string.voice_monitor), voice.monitor, { vm.setMonitor(it) }, detail = stringResource(R.string.voice_monitor_detail), enabled = voice.armed, modifier = Modifier.padding(top = 4.dp))
            ToggleRow(stringResource(R.string.voice_punch), voice.punch, { vm.setPunch(it) }, detail = stringResource(if (ui.selection != null) R.string.voice_punch_detail else R.string.voice_punch_needs_selection), enabled = !transport.recording)
            ToggleRow(stringResource(R.string.voice_count_in), voice.countIn, { vm.setCountIn(it) }, detail = stringResource(R.string.voice_count_in_detail), enabled = !transport.recording)
            if (voice.punch) ToggleRow(stringResource(R.string.voice_pre_roll), voice.preRoll, { vm.setPreRoll(it) }, detail = stringResource(R.string.voice_pre_roll_detail), enabled = !transport.recording)
            if (voice.countingIn) Text(stringResource(R.string.voice_counting_in), style = MaterialTheme.typography.titleMedium, color = StaveColors.Record, modifier = Modifier.padding(top = 8.dp))
            voice.dropped.takeIf { it > 0 }?.let { Text(pluralStringResource(R.plurals.voice_dropped, it.toInt(), it), style = MaterialTheme.typography.bodySmall, color = StaveColors.Hot) }
        }
        // ---- Looper ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            val looper by vm.looper.collectAsState()
            SectionHeading(stringResource(R.string.looper_title))
            Text(stringResource(R.string.looper_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.looper_bars), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
                ChipRow(listOf(1, 2, 4, 8).map { it to "$it" }, looper.bars, { vm.looperSet(it) }, modifier = Modifier.weight(1f), enabled = { !transport.recording })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (!looper.active) SecondaryButton(stringResource(R.string.looper_set), onClick = { vm.looperSet(looper.bars) }, tall = false, modifier = Modifier.weight(1f))
                else SecondaryButton(stringResource(R.string.looper_off), onClick = vm::looperOff, tall = false, modifier = Modifier.weight(1f), enabled = !transport.recording)
                if (!transport.recording) PrimaryButton(stringResource(R.string.looper_record), onClick = { if (hasMic) vm.startRecording() else ask.launch(Manifest.permission.RECORD_AUDIO) }, danger = true, tall = false, modifier = Modifier.weight(1f), enabled = looper.active)
                else PrimaryButton(stringResource(R.string.looper_stop), onClick = vm::stopRecording, tall = false, modifier = Modifier.weight(1f), enabled = looper.recordingLayer)
            }
            if (looper.layers > 0) Text(pluralStringResource(R.plurals.looper_layers, looper.layers, looper.layers), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 6.dp))
        }
        // ---- Show notes ----
        if (ui.session.markers.any { it.kind == com.layerbit.stave.engine.project.Marker.Kind.CHAPTER } || voice.transcript?.isEmpty == false) {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.voice_show_notes))
                Text(stringResource(R.string.voice_show_notes_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.voice_show_notes_copy), onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Show notes", vm.showNotes()))
                    }, tall = false, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.voice_show_notes_share), onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, vm.showNotes())
                        context.startActivity(android.content.Intent.createChooser(send, null))
                    }, tall = false, modifier = Modifier.weight(1f))
                }
            }
        }
        // ---- Keep only the voice ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.voice_isolate))
            Text(stringResource(R.string.voice_isolate_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            if (!vm.stemsAvailable) Text(stringResource(R.string.music_stems_none), style = MaterialTheme.typography.bodySmall, color = StaveColors.Hot)
            PrimaryButton(stringResource(R.string.voice_isolate), onClick = vm::isolateVoice, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), enabled = vm.stemsAvailable && ui.session.length > 0 && ui.busy == null)
        }
        // ---- Lyrics ----
        if (voice.transcript?.isEmpty == false) {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.voice_lyrics_title))
                Text(stringResource(R.string.voice_lyrics_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
                var lyrics by remember { mutableStateOf(voice.lyrics.joinToString("\n") { it.text }) }
                OutlinedTextField(lyrics, { lyrics = it }, label = { Text(stringResource(R.string.voice_lyrics_text)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
                SecondaryButton(stringResource(R.string.voice_lyrics_align), onClick = { vm.alignLyrics(lyrics) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = lyrics.isNotBlank())
                if (voice.lyrics.isNotEmpty()) Text(stringResource(R.string.voice_lyrics_aligned, voice.lyrics.count { it.isPlaced }, voice.lyrics.size), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        // ---- Read aloud ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.voice_read_aloud_title))
            Text(stringResource(R.string.voice_read_aloud_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            var line by remember { mutableStateOf("") }
            OutlinedTextField(line, { line = it }, label = { Text(stringResource(R.string.voice_read_aloud_text)) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            PrimaryButton(stringResource(R.string.voice_read_aloud), onClick = { vm.readAloud(line) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = line.isNotBlank() && ui.busy == null)
        }
        // ---- Words ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.voice_transcript_title))
            Spacer(Modifier.height(8.dp))
            // One row saying the language, the grid of twenty-four only when asked for: open, it stood
            // between the recorder and the words for two screens of scrolling.
            var choosing by remember { mutableStateOf(false) }
            val chosen = vm.whisper.languages.firstOrNull { it.first == voice.language }?.second ?: voice.language
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                .toggleable(value = choosing, role = Role.Button) { choosing = it }.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.voice_language), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
                Text(chosen, style = MaterialTheme.typography.bodyMedium, color = StaveColors.Band)
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = StaveColors.Muted, modifier = Modifier.padding(start = 4.dp).size(20.dp).graphicsLayer { rotationZ = if (choosing) 180f else 0f })
            }
            if (choosing) ChipRow(options = vm.whisper.languages, selected = voice.language, onSelect = { vm.setLanguage(it); choosing = false }, modifier = Modifier.padding(vertical = 6.dp))
            when {
                !vm.whisper.available && !com.layerbit.stave.native.WhisperNative.loaded -> Text(stringResource(R.string.voice_no_whisper), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
                !vm.whisper.available -> Text(stringResource(R.string.voice_no_model), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            }
            voice.transcribing?.let { p ->
                Text(stringResource(R.string.voice_transcribing, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted)
                com.layerbit.stave.ui.components.JobProgress(p, Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            PrimaryButton(stringResource(R.string.voice_transcribe), onClick = vm::transcribe, enabled = vm.whisper.available && voice.transcribing == null && ui.session.length > 0, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            val t = voice.transcript
            if (t == null || t.isEmpty) Text(stringResource(R.string.voice_transcript_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 8.dp))
            else {
                if (voice.stale) Text(stringResource(R.string.voice_stale), style = MaterialTheme.typography.bodySmall, color = StaveColors.Hot, modifier = Modifier.padding(top = 8.dp))
                WordFlow(t.words.map { it.text }, voice.selectedWords, onToggle = vm::toggleWord)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    PrimaryButton((if (voice.selectedWords.isEmpty()) stringResource(R.string.voice_delete_words_none) else pluralStringResource(R.plurals.voice_delete_words, voice.selectedWords.size, voice.selectedWords.size)), onClick = vm::deleteSelectedWords, enabled = voice.selectedWords.isNotEmpty(), tall = false, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.voice_remove_fillers), onClick = vm::removeFillers, tall = false, modifier = Modifier.weight(1f))
                }
                SecondaryButton(stringResource(R.string.voice_chapter_here), onClick = { vm.addMarker(com.layerbit.stave.engine.project.Marker.Kind.CHAPTER) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        // ---- Tune ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.tune_title))
            Text(stringResource(R.string.tune_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(vertical = 4.dp))
            var kind by remember { mutableStateOf("chromatic") }
            var root by remember { mutableStateOf(0) }
            var strength by remember { mutableStateOf(1f) }
            var retune by remember { mutableStateOf(20f) }
            Text(stringResource(R.string.tune_scale), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
            ChipRow(options = listOf("chromatic" to stringResource(R.string.tune_chromatic), "major" to stringResource(R.string.tune_major), "minor" to stringResource(R.string.tune_minor), "pentatonic" to stringResource(R.string.tune_pentatonic)), selected = kind, onSelect = { kind = it }, modifier = Modifier.padding(vertical = 6.dp))
            if (kind != "chromatic") {
                Text(stringResource(R.string.tune_root), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
                ChipRow(options = (0 until 12).map { it to com.layerbit.stave.engine.dsp.Notes.name(60 + it).dropLast(1) }, selected = root, onSelect = { root = it }, modifier = Modifier.padding(vertical = 6.dp))
            }
            ValueSlider(stringResource(R.string.tune_strength), strength, 0f..1f, { strength = it }, format = Formats::percent)
            ValueSlider(stringResource(R.string.tune_retune), retune, 0f..400f, { retune = it }, format = Formats::ms)
            PrimaryButton(stringResource(R.string.tune_apply), onClick = {
                val scale = when (kind) { "major" -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.major(root); "minor" -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.minor(root); "pentatonic" -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.pentatonic(root); else -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.CHROMATIC }
                vm.tune(scale, strength, retune.toDouble())
            }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), enabled = ui.session.length > 0)
            // Live: the same scale, strength and retune on the monitored voice through the graph's corrector.
            ToggleRow(stringResource(R.string.tune_live), voice.liveTune, { on -> if (on && !hasMic) ask.launch(Manifest.permission.RECORD_AUDIO) else vm.setLiveTune(on, kind, root, strength.toDouble(), retune.toDouble()) },
                detail = stringResource(R.string.tune_live_hint), modifier = Modifier.padding(top = 6.dp))
            if (voice.liveTune) {
                androidx.compose.runtime.LaunchedEffect(kind, root, strength, retune) { vm.setLiveTune(true, kind, root, strength.toDouble(), retune.toDouble()) }
                val heard = voice.liveHeardHz; val target = voice.liveTargetHz
                Text(
                    if (heard > 0f && target > 0f) stringResource(R.string.tune_live_hearing, com.layerbit.stave.engine.dsp.Notes.name(com.layerbit.stave.engine.dsp.Notes.nearest(heard.toDouble())), com.layerbit.stave.engine.dsp.Notes.name(com.layerbit.stave.engine.dsp.Notes.nearest(target.toDouble()))) else stringResource(R.string.tune_live_quiet),
                    style = MaterialTheme.typography.titleMedium, color = ToolAccents.Voice, modifier = Modifier.padding(top = 6.dp)
                )
                ToggleRow(stringResource(R.string.tune_record_tuned), voice.recordTuned, { vm.setLiveTune(true, kind, root, strength.toDouble(), retune.toDouble(), recordTuned = it) }, detail = stringResource(R.string.tune_record_tuned_hint))
            }
        }
        // ---- Effects ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.fx_title))
            var preset by remember { mutableStateOf("doubler") }
            ChipRow(options = com.layerbit.stave.engine.dsp.VocalPresets.all.map { it.id to it.label }, selected = preset, onSelect = { preset = it }, modifier = Modifier.padding(vertical = 8.dp))
            Text(com.layerbit.stave.engine.dsp.VocalPresets.byId(preset)?.note ?: "", style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            PrimaryButton(stringResource(R.string.fx_apply_preset, com.layerbit.stave.engine.dsp.VocalPresets.byId(preset)?.label ?: ""), onClick = { vm.applyEffect(preset) }, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = ui.session.length > 0)
        }
        // ---- Clean up ----
        Panel(modifier = Modifier.fillMaxWidth()) {
            SectionHeading(stringResource(R.string.voice_cleanup_title))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.voice_clean_voice_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            PrimaryButton(stringResource(R.string.voice_clean_voice), onClick = vm::cleanUpVoice, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = ui.session.length > 0)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.voice_even_out_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            SecondaryButton(stringResource(R.string.voice_even_out), onClick = vm::evenOutVolume, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = ui.session.length > 0)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.voice_silences_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            ValueSlider(stringResource(R.string.voice_silences_min), voice.silenceMinMs.toFloat(), 300f..3000f, { vm.setSilenceRule(minMs = it.toLong()) }, format = Formats::ms)
            ValueSlider(stringResource(R.string.voice_silences_keep), voice.silenceKeepMs.toFloat(), 50f..600f, { vm.setSilenceRule(keepMs = it.toLong()) }, format = Formats::ms)
            SecondaryButton(stringResource(R.string.voice_silences), onClick = vm::removeSilences, tall = false, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), enabled = ui.session.length > 0)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.voice_loudness), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
            ChipRow(options = LoudnessTarget.all.map { it.id to it.label }, selected = voice.targetId, onSelect = vm::setVoiceTarget, modifier = Modifier.padding(vertical = 6.dp))
            SecondaryButton(stringResource(R.string.voice_loudness_apply, Formats.lufs(LoudnessTarget.byId(voice.targetId).lufs)), onClick = vm::applyVoiceTarget, tall = false, modifier = Modifier.fillMaxWidth(), enabled = ui.session.length > 0)
        }
    }
}

/** The words as chips, each `selectable` so a screen reader hears which are chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordFlow(words: List<String>, selected: Set<Int>, onToggle: (Int) -> Unit) {
    // Read as text: a box around every word turned a minute of speech into a wall of chips. A
    // chosen word is highlighted; a filler (the ones Take out fillers will cut) reads dim and
    // slanted so it is found before it is removed.
    val fillers = remember(words) { com.layerbit.stave.engine.transcript.Fillers.find(words.map { com.layerbit.stave.engine.transcript.Word(it, 0, 0) }).toSet() }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        words.forEachIndexed { i, w ->
            val on = i in selected
            val filler = i in fillers
            Text(
                w,
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = if (filler) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal),
                color = when { on -> StaveColors.OnBand; filler -> StaveColors.Dim; else -> StaveColors.Text },
                modifier = Modifier.background(if (on) StaveColors.Band else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .selectable(selected = on, role = Role.Checkbox) { onToggle(i) }.padding(horizontal = 3.dp, vertical = 3.dp)
            )
        }
    }
}
