package com.layerbit.stave.ui.beats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.stave.ui.components.ChipShape
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.ToolAccents

/**
 * A pad or a key: sounds on the press and releases on the lift (a gated pad or a held synth
 * note needs the lift), rather than on a click, which would be a tenth of a second late. Named
 * to accessibility as a button with its sound's name.
 */
@Composable
private fun Trigger(label: String, colour: Color, dark: Boolean, onDown: () -> Unit, onUp: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    Box(
        modifier
            .clip(ChipShape)
            .background(if (pressed) colour else if (dark) StaveColors.Surface else StaveColors.SurfaceDim)
            .border(1.dp, if (pressed) colour else StaveColors.Border, ChipShape)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true; down()
                    tryAwaitRelease()
                    pressed = false; up()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (pressed) StaveColors.OnBand else if (dark) StaveColors.Dim else StaveColors.Muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(2.dp))
    }
}

/** Sixteen pads in four rows, the first pad at the bottom left as on every drum machine. */
@Composable
fun DrumPads(labels: List<String>, basePitch: Int, onDown: (pitch: Int) -> Unit, onUp: (pitch: Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 3 downTo 0) {
            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 4) {
                    val pad = row * 4 + col
                    Trigger(labels.getOrNull(pad) ?: "Pad ${pad + 1}", ToolAccents.Beats, dark = false, onDown = { onDown(basePitch + pad) }, onUp = { onUp(basePitch + pad) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** One octave and the C above it: a row of black keys over a row of white keys, each a trigger. */
@Composable
fun Keyboard(octave: Int, onDown: (pitch: Int) -> Unit, onUp: (pitch: Int) -> Unit, modifier: Modifier = Modifier) {
    val base = (octave + 1) * 12 // MIDI: C4 is 60, and octave 4 starts there
    val whites = listOf(0, 2, 4, 5, 7, 9, 11, 12)
    val blacks = listOf(1, 3, null, 6, 8, 10, null, null)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in blacks) {
                if (b == null) Box(Modifier.weight(1f)) else Trigger(noteName(base + b), ToolAccents.Beats, dark = true, onDown = { onDown(base + b) }, onUp = { onUp(base + b) }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (w in whites) Trigger(noteName(base + w), ToolAccents.Beats, dark = false, onDown = { onDown(base + w) }, onUp = { onUp(base + w) }, modifier = Modifier.weight(1f))
        }
    }
}

private val NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
fun noteName(pitch: Int): String = NAMES[pitch.mod(12)] + (pitch / 12 - 1)
fun isBlackKey(pitch: Int): Boolean = pitch.mod(12) in setOf(1, 3, 6, 8, 10)
