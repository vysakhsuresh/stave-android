package com.layerbit.stave.ui.settings

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.layerbit.stave.R
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.files.CrashLog
import com.layerbit.stave.prefs.Settings
import com.layerbit.stave.prefs.ThemeChoice
import com.layerbit.stave.ui.components.AmbientBackdrop
import com.layerbit.stave.ui.components.ChipRow
import com.layerbit.stave.ui.components.LabelledValue
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.SecondaryButton
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.components.ToggleRow
import com.layerbit.stave.ui.components.formatBytes
import com.layerbit.stave.ui.components.rise
import com.layerbit.stave.ui.theme.StaveColors

@Composable
fun SettingsScreen(
    settings: Settings,
    projectBytes: Long,
    scratchBytes: Long,
    projectCount: Int,
    onChange: ((Settings) -> Settings) -> Unit,
    onClearScratch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    Box(modifier.fillMaxSize()) {
        AmbientBackdrop(strength = 0.6f)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Panel(modifier = rise(0).fillMaxWidth()) {
                    SectionHeading(stringResource(R.string.settings_appearance))
                    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                    ChipRow(
                        options = listOf(ThemeChoice.DARK to stringResource(R.string.app_theme_dark), ThemeChoice.LIGHT to stringResource(R.string.app_theme_light), ThemeChoice.SYSTEM to stringResource(R.string.app_theme_system)),
                        selected = settings.theme, onSelect = { t -> onChange { it.copy(theme = t) } }
                    )
                    ToggleRow(stringResource(R.string.settings_lively), settings.lively, { v -> onChange { it.copy(lively = v) } }, detail = stringResource(R.string.settings_lively_detail), modifier = Modifier.padding(top = 6.dp))
                    ToggleRow(stringResource(R.string.settings_haptics), settings.haptics, { v -> onChange { it.copy(haptics = v) } }, detail = stringResource(R.string.settings_haptics_detail))
                }
            }
            item {
                Panel(modifier = rise(1).fillMaxWidth()) {
                    SectionHeading(stringResource(R.string.settings_audio))
                    Text(stringResource(R.string.settings_sample_rate), style = MaterialTheme.typography.bodyLarge, color = StaveColors.Text, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                    ChipRow(
                        options = listOf(0 to stringResource(R.string.settings_sample_rate_match), 44_100 to "44.1 kHz", 48_000 to "48 kHz", 96_000 to "96 kHz"),
                        selected = settings.sampleRate, onSelect = { r -> onChange { it.copy(sampleRate = r) } }
                    )
                    Text(stringResource(R.string.settings_bit_depth), style = MaterialTheme.typography.bodyLarge, color = StaveColors.Text, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    Text(stringResource(R.string.settings_bit_depth_detail), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(bottom = 8.dp))
                    ChipRow(options = listOf(32 to "32-bit float", 24 to "24-bit"), selected = settings.recordBits, onSelect = { b -> onChange { it.copy(recordBits = b) } })
                    Text(stringResource(R.string.settings_latency), style = MaterialTheme.typography.bodyLarge, color = StaveColors.Text, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    ChipRow(options = listOf(true to stringResource(R.string.settings_latency_low), false to stringResource(R.string.settings_latency_safe)), selected = settings.lowLatency, onSelect = { v -> onChange { it.copy(lowLatency = v) } })
                    Text(stringResource(R.string.settings_target), style = MaterialTheme.typography.bodyLarge, color = StaveColors.Text, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                    ChipRow(options = LoudnessTarget.all.map { it.id to it.label }, selected = settings.loudnessTargetId, onSelect = { id -> onChange { it.copy(loudnessTargetId = id) } })
                    ToggleRow(stringResource(R.string.settings_keep_screen), settings.keepScreenOn, { v -> onChange { it.copy(keepScreenOn = v) } }, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                Panel(modifier = rise(2).fillMaxWidth()) {
                    SectionHeading(stringResource(R.string.settings_storage))
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabelledValue(stringResource(R.string.settings_storage_projects), formatBytes(projectBytes) + " · " + projectCount, Modifier.weight(1f), numeric = true)
                        LabelledValue(stringResource(R.string.settings_storage_scratch), formatBytes(scratchBytes), Modifier.weight(1f), numeric = true)
                    }
                    Text(stringResource(R.string.settings_storage_clear_detail), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 10.dp))
                    SecondaryButton(stringResource(R.string.settings_storage_clear), onClick = onClearScratch, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), tall = false)
                }
            }
            item {
                Panel(modifier = rise(3).fillMaxWidth()) {
                    SectionHeading(stringResource(R.string.settings_crash))
                    Text(if (crash == null) stringResource(R.string.settings_crash_none) else stringResource(R.string.settings_crash_detail), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 8.dp))
                    if (crash != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            SecondaryButton(stringResource(R.string.app_share), onClick = {
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", CrashLog.file(context))
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, null))
                            }, modifier = Modifier.weight(1f), tall = false)
                            SecondaryButton(stringResource(R.string.app_delete), onClick = { CrashLog.clear(context); crash = null }, modifier = Modifier.weight(1f), tall = false)
                        }
                    }
                }
            }
        }
    }
}
