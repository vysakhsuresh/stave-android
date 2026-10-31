package com.layerbit.stave.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.layerbit.stave.R
import com.layerbit.stave.audio.Transport
import com.layerbit.stave.engine.dsp.Notes
import com.layerbit.stave.engine.dsp.PitchDetector
import com.layerbit.stave.ui.components.Formats
import com.layerbit.stave.ui.components.Panel
import com.layerbit.stave.ui.components.SectionHeading
import com.layerbit.stave.ui.theme.Clock
import com.layerbit.stave.ui.theme.StaveColors
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * A tuner off the graph's input tap: the last 4096 samples through YIN thirty times a second,
 * the note's name large, a needle for the cents, green within five. The needle reads the
 * frequency smoothed in log space so vibrato shows as a gentle wobble, not a jitter.
 */
@Composable
fun TunerCard(transport: Transport, armed: Boolean, a4: Int = 440) {
    var hz by remember { mutableStateOf<Double?>(null) }
    var clarity by remember { mutableStateOf(0f) }
    LaunchedEffect(armed, a4) {
        if (!armed) { hz = null; return@LaunchedEffect }
        val buffer = FloatArray(4096)
        val detector = PitchDetector(transport.sampleRate, frameSize = 4096, minHz = 30.0, maxHz = 2500.0)
        var smoothed: Double? = null
        while (true) {
            val n = transport.readInputTap(buffer)
            val e = if (n >= 4096) detector.detect(buffer) else null
            if (e != null && e.clarity > 0.7f) { smoothed = PitchDetector.smoothLog(smoothed, e.hz, 0.4); hz = smoothed; clarity = e.clarity } else { clarity = clarity * 0.8f; if (clarity < 0.2f) { hz = null; smoothed = null } }
            delay(33)
        }
    }
    val f = hz
    val midi: Int? = f?.let { Notes.nearest(it, a4.toDouble()) }
    val cents = f?.let { Notes.centsOff(it, a4.toDouble()) } ?: 0.0
    val inTune = f != null && kotlin.math.abs(cents) <= 5
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(stringResource(R.string.music_tuner))
        if (!armed) Text(stringResource(R.string.music_tuner_hint), style = MaterialTheme.typography.bodySmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 6.dp))
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = if (midi == null) "Listening" else "${Notes.name(midi)}, ${String.format(Locale.ROOT, "%+.0f", cents)} cents" }) {
            Text(if (midi == null) (if (armed) stringResource(R.string.music_tuner_listening) else "—") else Notes.name(midi), style = MaterialTheme.typography.displayMedium, color = if (inTune) StaveColors.Good else StaveColors.Text)
            Text(if (f == null) "" else "${Formats.hz(f.toFloat())} · " + stringResource(R.string.music_tuner_cents, String.format(Locale.getDefault(), "%+.0f", cents)), style = Clock, color = StaveColors.Muted)
            Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 8.dp)) {
                val w = size.width; val mid = w / 2
                drawLine(StaveColors.Border, Offset(0f, size.height / 2), Offset(w, size.height / 2), 2f)
                for (c in -50..50 step 10) { val x = mid + c / 50f * (w / 2 - 4f); drawLine(StaveColors.Dim, Offset(x, size.height / 2 - (if (c == 0) 10f else 5f)), Offset(x, size.height / 2 + (if (c == 0) 10f else 5f)), 1.5f) }
                if (f != null) {
                    val x = mid + (cents.coerceIn(-50.0, 50.0) / 50.0).toFloat() * (w / 2 - 4f)
                    drawLine(if (inTune) StaveColors.Good else StaveColors.Band, Offset(x, 0f), Offset(x, size.height), 3f)
                }
            }
            Text(stringResource(R.string.music_reference, a4), style = MaterialTheme.typography.labelSmall, color = StaveColors.Dim, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
