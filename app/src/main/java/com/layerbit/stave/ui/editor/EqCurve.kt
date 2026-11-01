package com.layerbit.stave.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.layerbit.stave.engine.dsp.EqBand
import com.layerbit.stave.engine.dsp.ParametricEq
import com.layerbit.stave.ui.theme.StaveColors
import kotlin.math.ln

/**
 * The EQ's response, drawn from the same coefficients the audio runs through, over a log
 * frequency axis from 20 Hz to 20 kHz and ±18 dB; each band is a dot the finger can pick.
 */
@Composable
fun EqCurve(bands: List<EqBand>, sampleRate: Int, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, /** The selection's spectrum in dB (0 at its loudest) at the curve's 96 points, drawn behind the curve. */ spectrum: FloatArray? = null) {
    val eq = ParametricEq(bands, sampleRate, 1)
    val points = 96
    val curve = eq.curve(points)
    Canvas(modifier.semantics { contentDescription = "Equaliser curve, ${bands.size} bands" }.pointerInput(bands) {
        detectTapGestures { p ->
            val x = p.x / size.width
            val hz = 20.0 * Math.pow(1000.0, x.toDouble())
            val nearest = bands.indices.minByOrNull { kotlin.math.abs(ln(bands[it].hz / hz)) } ?: return@detectTapGestures
            onSelect(nearest)
        }
    }) {
        val w = size.width; val h = size.height
        drawRect(StaveColors.Canvas)
        // Grid: decades and ±6, ±12 dB.
        for (hz in listOf(100.0, 1000.0, 10_000.0)) { val x = (ln(hz / 20.0) / ln(1000.0)).toFloat() * w; drawLine(StaveColors.Border, Offset(x, 0f), Offset(x, h), 1f) }
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) { val y = h / 2 - db / 18f * h / 2; drawLine(if (db == 0f) StaveColors.BorderStrong else StaveColors.Border, Offset(0f, y), Offset(w, y), 1f) }
        // The analyser: the selection's long-term spectrum as a filled area, 0 dB at the top and -60 at the bottom.
        if (spectrum != null && spectrum.size == points) {
            val area = Path()
            area.moveTo(0f, h)
            for (i in 0 until points) { val x = i.toFloat() / (points - 1) * w; val y = ((-spectrum[i]).coerceIn(0f, 60f) / 60f) * h; area.lineTo(x, y) }
            area.lineTo(w, h); area.close()
            drawPath(area, StaveColors.Band.copy(alpha = 0.14f))
        }
        val path = Path()
        for (i in 0 until points) {
            val x = i.toFloat() / (points - 1) * w
            val y = h / 2 - (curve[i].coerceIn(-18.0, 18.0) / 18.0).toFloat() * h / 2
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, StaveColors.Band, style = Stroke(2.5f))
        bands.forEachIndexed { i, b ->
            val x = (ln(b.hz.coerceIn(20.0, 20_000.0) / 20.0) / ln(1000.0)).toFloat() * w
            val y = h / 2 - (eq.responseDb(b.hz).coerceIn(-18.0, 18.0) / 18.0).toFloat() * h / 2
            drawCircle(if (i == selected) StaveColors.BandBright else StaveColors.Muted, if (i == selected) 8f else 6f, Offset(x, y))
        }
    }
}
