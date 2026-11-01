package com.layerbit.stave.ui.convert

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.engine.export.AudioFormat
import com.layerbit.stave.engine.export.ExportSettings
import com.layerbit.stave.export.Converter
import com.layerbit.stave.export.Exporter
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.PrimaryButton
import com.layerbit.stave.ui.components.ScreenTitle
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.StaveTopBar
import com.layerbit.stave.ui.components.formatBytes
import com.layerbit.stave.ui.theme.StaveColors
import kotlinx.coroutines.launch

/** Convert: pick any number of files, choose a format and a bitrate, and every one lands in Music/Stave. The utility WaveEditor sells separately. */
@Composable
fun ConvertScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as StaveApplication
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var format by remember { mutableStateOf(AudioFormat.MP3.id) }
    var bitrate by remember { mutableStateOf(0) }
    var normalise by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var label by remember { mutableStateOf("") }
    var outcomes by remember { mutableStateOf<List<Converter.Outcome>>(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) { picked = uris; outcomes = emptyList() } }
    val fmt = AudioFormat.byId(format)
    Column(modifier.fillMaxSize()) {
        StaveTopBar(title = stringResource(R.string.convert_title), onBack = onBack)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The top bar already names the screen; a second, larger title repeated it.
            Text(stringResource(R.string.convert_hint), style = MaterialTheme.typography.bodyMedium, color = StaveColors.Muted)
            Panel(modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(if (picked.isEmpty()) stringResource(R.string.convert_pick) else pluralStringResource(R.plurals.convert_picked, picked.size, picked.size), onClick = { pick.launch(arrayOf("audio/*", "video/*")) }, tall = false, modifier = Modifier.fillMaxWidth(), enabled = !running)
                for (u in picked.take(12)) Text(app.media.displayName(u), style = MaterialTheme.typography.bodySmall, color = StaveColors.Muted, modifier = Modifier.padding(top = 4.dp))
                if (picked.size > 12) Text(stringResource(R.string.convert_more, picked.size - 12), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
            }
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.export_format))
                ChipRow(AudioFormat.all.map { it.id to it.label }, format, { format = it; bitrate = 0 }, modifier = Modifier.padding(vertical = 6.dp))
                Text(fmt.note, style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim)
                if (!fmt.lossless) {
                    SectionHeading(stringResource(R.string.export_bitrate), modifier = Modifier.padding(top = 8.dp))
                    ChipRow(fmt.bitrates.map { it to "$it kbps" }, if (bitrate == 0) fmt.defaultBitrate else bitrate, { bitrate = it }, modifier = Modifier.padding(vertical = 6.dp))
                }
                ToggleRow(stringResource(R.string.convert_normalise), normalise, { normalise = it }, detail = stringResource(R.string.convert_normalise_hint))
            }
            if (running) {
                Panel(modifier = Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = StaveColors.Band, trackColor = StaveColors.Border)
                }
            }
            PrimaryButton(pluralStringResource(R.plurals.convert_go, picked.size, picked.size), onClick = {
                running = true; outcomes = emptyList()
                val settings = ExportSettings(formatId = format, bitrateKbps = if (bitrate == 0) fmt.defaultBitrate else bitrate, normaliseToTarget = normalise)
                scope.launch {
                    val converter = Converter(context, app.media, Exporter(context))
                    val result = converter.convert(picked, settings) { i, p, l -> progress = (i + p) / picked.size; label = l }
                    outcomes = result; running = false
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && !running)
            if (outcomes.isNotEmpty()) Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading(stringResource(R.string.convert_done, outcomes.count { it.result != null }, outcomes.size))
                for (o in outcomes) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(o.name, style = MaterialTheme.typography.bodyMedium, color = StaveColors.Text, modifier = Modifier.weight(1f))
                        Text(o.result?.let { formatBytes(it.bytes) } ?: (o.error ?: ""), style = MaterialTheme.typography.bodySmall, color = if (o.result != null) StaveColors.Good else StaveColors.Hot)
                    }
                }
            }
        }
    }
}
