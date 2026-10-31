package com.layerbit.stave.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.layerbit.stave.engine.dsp.Gain
import com.layerbit.stave.ui.theme.Numerals
import com.layerbit.stave.ui.theme.StaveColors
import java.util.Locale

/**
 * A labelled slider that reads its value in Fira Code and tells a screen reader both its name
 * and its value. [onChange] fires on every move (a gesture), [onCommit] when the finger lifts.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    format: (Float) -> String = { String.format(Locale.getDefault(), "%.1f", it) },
    steps: Int = 0,
    onCommit: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val shown = format(value)
    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = label; stateDescription = shown }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) StaveColors.Text else StaveColors.Dim, modifier = Modifier.weight(1f))
            Text(shown, style = Numerals, color = if (enabled) StaveColors.BandBright else StaveColors.Dim)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled,
            onValueChangeFinished = onCommit,
            colors = SliderDefaults.colors(thumbColor = StaveColors.Band, activeTrackColor = StaveColors.Band, inactiveTrackColor = StaveColors.Border, disabledThumbColor = StaveColors.Dim, disabledActiveTrackColor = StaveColors.Border)
        )
    }
}

/** The formatters sliders share: decibels, hertz, milliseconds, percent, semitones, a ratio. */
object Formats {
    fun db(v: Float): String = String.format(Locale.getDefault(), "%+.1f dB", v)
    fun dbPlain(v: Float): String = String.format(Locale.getDefault(), "%.1f dB", v)
    fun hz(v: Float): String = if (v >= 1000f) String.format(Locale.getDefault(), "%.2f kHz", v / 1000f) else String.format(Locale.getDefault(), "%.0f Hz", v)
    fun ms(v: Float): String = if (v >= 1000f) String.format(Locale.getDefault(), "%.2f s", v / 1000f) else String.format(Locale.getDefault(), "%.0f ms", v)
    fun percent(v: Float): String = String.format(Locale.getDefault(), "%.0f%%", v * 100)
    fun semitones(v: Float): String = String.format(Locale.getDefault(), "%+.1f st", v)
    fun ratio(v: Float): String = String.format(Locale.getDefault(), "%.1f:1", v)
    fun times(v: Float): String = String.format(Locale.getDefault(), "%.2f×", v)
    fun q(v: Float): String = String.format(Locale.getDefault(), "Q %.2f", v)
    fun lufs(v: Double): String = if (v < -90) "—" else String.format(Locale.getDefault(), "%.1f LUFS", v)
    fun dbtp(v: Double): String = if (v < -90) "—" else String.format(Locale.getDefault(), "%.1f dBTP", v)
}

/**
 * A level meter: the peak as a bar on a dB scale from -60 to 0, green until -12, amber to -3,
 * red above, with a hold line. [peakLinear] is a linear peak; [holdLinear] the held one.
 */
@Composable
fun LevelMeter(peakLinear: Float, modifier: Modifier = Modifier, holdLinear: Float = peakLinear, vertical: Boolean = false, label: String? = null) {
    val db = Gain.linearToDb(peakLinear.toDouble()).toFloat().coerceIn(-60f, 0f)
    val hold = Gain.linearToDb(holdLinear.toDouble()).toFloat().coerceIn(-60f, 0f)
    val good = StaveColors.Good; val hot = StaveColors.Hot; val clip = StaveColors.Failed; val track = StaveColors.SurfaceDim
    val description = label?.let { "$it ${Formats.dbPlain(db)}" }
    Canvas(modifier.then(if (vertical) Modifier else Modifier.height(10.dp)).semantics { if (description != null) contentDescription = description }) {
        val f = (db + 60f) / 60f
        val fh = (hold + 60f) / 60f
        fun colourAt(x: Float): Color = if (x < 0.8f) good else if (x < 0.95f) hot else clip
        if (vertical) {
            drawRect(track, Offset.Zero, size)
            val h = size.height * f
            drawRect(colourAt(f), Offset(0f, size.height - h), Size(size.width, h))
            drawRect(colourAt(fh), Offset(0f, size.height * (1 - fh) - 1f), Size(size.width, 2f))
        } else {
            drawRect(track, Offset.Zero, size)
            drawRect(colourAt(f), Offset.Zero, Size(size.width * f, size.height))
            drawRect(colourAt(fh), Offset(size.width * fh - 1f, 0f), Size(2f, size.height))
        }
    }
}
