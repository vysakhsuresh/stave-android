package com.layerbit.stave.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.engine.text.Timecode
import com.layerbit.stave.engine.wave.Peaks
import com.layerbit.stave.engine.wave.Ruler
import com.layerbit.stave.engine.wave.TimeWindow
import com.layerbit.stave.ui.theme.FiraCode
import com.layerbit.stave.ui.theme.StaveColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The strip: a ruler, one lane per track with its clips drawn from the peak pyramids, the
 * selection, the markers and the fixed play-head. Gestures: one finger drags a selection (or
 * a selection edge, when it lands within a few dp of one); a tap puts the play-head; two
 * fingers scroll and zoom; a double tap fits the file. The film moves under the finger and the
 * play-head line never does (TimeWindow says why).
 */
@Composable
fun WaveformStrip(
    session: Session,
    window: TimeWindow,
    selection: LongRange?,
    selectedTrackId: String?,
    peaksFor: (Source) -> List<Peaks>?,
    onWindow: (TimeWindow) -> Unit,
    onSelect: (LongRange?) -> Unit,
    onSelectTrack: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onFit: () -> Unit,
    onWidth: (Int) -> Unit,
    /** The spectrogram of the selected track's visible stretch, when the Spectral sheet asked for it. */
    spectrogram: EditorViewModel.SpectrogramImage? = null,
    spectralBox: com.layerbit.stave.engine.dsp.SpectralEdit.Box? = null,
    onSpectralBox: (com.layerbit.stave.engine.dsp.SpectralEdit.Box?) -> Unit = {},
    /** A clip under a long press follows the finger: (clipId, position, trackId, done). */
    onCarry: (String, Long, String?, Boolean) -> Unit = { _, _, _, _ -> },
    /** The automation lane being drawn ("volume", "pan") on the selected track, or null; a finger on that track then draws rather than selects. */
    automationParam: String? = null,
    onDrawAutomation: (trackId: String, samples: List<com.layerbit.stave.engine.project.ValueTrack.Point>, done: Boolean) -> Unit = { _, _, _ -> },
    /** Comping: a swipe over one take lane of the selected track hears that lane there: (trackId, lane, from, to). */
    compMode: Boolean = false,
    onComp: (trackId: String, lane: Int, from: Long, to: Long) -> Unit = { _, _, _, _ -> },
    /** A hold on a clip picks it up (the Studio tab); elsewhere a hold starts a selection. */
    carryClips: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val rulerPx = with(density) { 26.dp.toPx() }
    val minLanePx = with(density) { 72.dp.toPx() }
    val handlePx = with(density) { 14.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = FiraCode, fontSize = 10.sp, color = StaveColors.Dim)
    val nameStyle = TextStyle(fontSize = 11.sp, color = StaveColors.Muted)
    val currentWindow by rememberUpdatedState(window)
    val currentSelection by rememberUpdatedState(selection)
    val currentSession by rememberUpdatedState(session)
    var lanesHeight by remember { mutableStateOf(0f) }
    val tracks = session.tracks.filter { it.kind != com.layerbit.stave.engine.project.Track.Kind.MASTER }
    val laneCount = max(1, tracks.size)

    /** How many take lanes a track's row is split into: the lanes its clips use, at least one. */
    fun laneCountOf(track: com.layerbit.stave.engine.project.Track): Int = maxOf(1, track.lanes, (session.clipsOn(track.id).maxOfOrNull { it.lane } ?: 0) + 1)
    /** The take lane under y inside a track's row, for comping. */
    fun takeLaneAt(track: com.layerbit.stave.engine.project.Track, y: Float): Int {
        val laneH = max(minLanePx, lanesHeight / laneCount)
        val top = rulerPx + tracks.indexOf(track) * laneH
        val n = laneCountOf(track)
        return (((y - top) / laneH) * n).toInt().coerceIn(0, n - 1)
    }
    fun trackAt(y: Float): String? {
        if (y < rulerPx || tracks.isEmpty()) return null
        val lane = max(minLanePx, lanesHeight / laneCount)
        val i = ((y - rulerPx) / lane).toInt()
        return tracks.getOrNull(i)?.id
    }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { onWidth(it.width); lanesHeight = it.height - rulerPx }
            .semantics { contentDescription = "Waveform, ${Timecode.display(Timecode.ofSamples(session.length, session.sampleRate))}" }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { onFit() },
                    onTap = { p -> onSelect(null); trackAt(p.y)?.let(onSelectTrack); onSeek(currentWindow.sampleAt(p.x).coerceIn(0L, currentSession.length)) }
                )
            }
            .pointerInput(Unit) {
                // One finger: a drag scrolls the film under the fixed play-head (and the sound
                // follows it); a hold of 400 ms, then a drag, selects; a selection's edge drags that
                // edge. Two fingers: pan and zoom. On the phone (1 Nov 2026) a plain drag selected,
                // and people trying to move along a song made selections instead.
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    var anchor: Long? = null
                    var editingEdge: Int = 0   // 1 start, 2 end
                    val sel = currentSelection
                    if (sel != null) {
                        val xs = currentWindow.pixelOf(sel.first); val xe = currentWindow.pixelOf(sel.last + 1)
                        if (abs(first.position.x - xs) < handlePx) editingEdge = 1 else if (abs(first.position.x - xe) < handlePx) editingEdge = 2
                    }
                    val downTime = System.currentTimeMillis()
                    val downTrack = trackAt(first.position.y)
                    val downClip = downTrack?.let { tid -> currentSession.clipAt(tid, currentWindow.sampleAt(first.position.x)) }
                    var carrying: com.layerbit.stave.engine.project.Clip? = null
                    var carryGrab = 0L
                    val spectralMode = spectrogram != null && downTrack == selectedTrackId && downTrack != null
                    // Drawing automation: on the selected track with a lane chosen, the finger is a pen.
                    val drawMode = automationParam != null && downTrack == selectedTrackId && downTrack != null
                    // Comping: a swipe over one lane of the selected track, horizontal only, the lane fixed where the finger landed.
                    val compTrack = if (compMode && !drawMode && downTrack == selectedTrackId && downTrack != null) tracks.first { it.id == downTrack } else null
                    val compLane = compTrack?.let { takeLaneAt(it, first.position.y) } ?: 0
                    var compFrom = 0L; var compTo = 0L
                    val drawn = ArrayList<com.layerbit.stave.engine.project.ValueTrack.Point>()
                    fun laneValue(y: Float): Double {
                        val laneH = max(minLanePx, lanesHeight / laneCount)
                        val laneTop = rulerPx + tracks.indexOfFirst { it.id == downTrack } * laneH
                        return com.layerbit.stave.engine.project.AutomationDraw.fromLane(automationParam ?: "volume", ((y - laneTop) / laneH).toDouble())
                    }
                    if (drawMode) drawn += com.layerbit.stave.engine.project.ValueTrack.Point(currentWindow.sampleAt(first.position.x).coerceIn(0L, currentSession.length), laneValue(first.position.y))
                    var twoFinger = false
                    var moved = false
                    var selecting = false
                    val holdable = editingEdge == 0 && !spectralMode && !drawMode && compTrack == null
                    while (true) {
                        // Until the finger moves, wait no longer than the hold: a finger held still
                        // sends no events, and the hold must still be noticed.
                        val waitHold = holdable && !moved && !twoFinger && !selecting && carrying == null
                        val left = 400L - (System.currentTimeMillis() - downTime)
                        val event = if (waitHold && left > 0) withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Main) } else if (waitHold) null else awaitPointerEvent(PointerEventPass.Main)
                        if (event == null) {
                            // Held still: pick up the clip (Studio), or begin a selection here.
                            if (carryClips && downClip != null) {
                                carrying = downClip; carryGrab = currentWindow.sampleAt(first.position.x) - downClip.position
                                onCarry(downClip.id, downClip.position, downClip.trackId, false)
                            } else {
                                selecting = true
                                anchor = currentWindow.sampleAt(first.position.x).coerceIn(0L, currentSession.length)
                                trackAt(first.position.y)?.let(onSelectTrack)
                                onSelect(null)
                            }
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            carrying?.let { c -> onCarry(c.id, c.position, c.trackId, true) }
                            if (drawMode && !twoFinger) onDrawAutomation(downTrack!!, drawn.toList(), true)
                            if (compTrack != null && !twoFinger && compTo > compFrom) { onComp(compTrack.id, compLane, compFrom, compTo); onSelect(null) }
                            break
                        }
                        if (pressed.size >= 2) {
                            twoFinger = true
                            val zoom = event.calculateZoom(); val pan = event.calculatePan(); val centroid = event.calculateCentroid()
                            var w = currentWindow
                            if (zoom != 1f) w = w.zoomed(zoom, centroid.x)
                            if (pan.x != 0f) w = w.dragged(pan.x)
                            onWindow(w)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (twoFinger) continue
                        val change = pressed.first()
                        if (!change.positionChanged()) continue
                        val x = change.position.x
                        val sample = currentWindow.sampleAt(x).coerceIn(0L, currentSession.length)
                        carrying?.let { c ->
                            change.consume()
                            onCarry(c.id, (currentWindow.sampleAt(x) - carryGrab).coerceAtLeast(0L), trackAt(change.position.y) ?: c.trackId, false)
                            continue
                        }
                        if (drawMode) {
                            change.consume()
                            drawn += com.layerbit.stave.engine.project.ValueTrack.Point(sample, laneValue(change.position.y))
                            onDrawAutomation(downTrack!!, drawn.toList(), false)
                            continue
                        }
                        if (compTrack != null) {
                            change.consume()
                            val a = currentWindow.sampleAt(first.position.x).coerceIn(0L, currentSession.length)
                            compFrom = min(a, sample); compTo = max(a, sample)
                            onSelect(compFrom until compTo)   // the stretch is shown as a selection while the finger moves
                            continue
                        }
                        if (!moved && abs(change.position.x - first.position.x) < 6f && abs(change.position.y - first.position.y) < 6f) continue
                        moved = true
                        change.consume()
                        if (spectralMode) {
                            // A box in time and frequency, the lane's height a log scale from the picture's low to its high.
                            val img = spectrogram!!
                            val laneTop = rulerPx + (tracks.indexOfFirst { it.id == downTrack }) * max(minLanePx, lanesHeight / laneCount)
                            val laneH = max(minLanePx, lanesHeight / laneCount)
                            fun hzAt(y: Float): Double { val f = ((y - laneTop) / laneH).coerceIn(0f, 1f); return Math.exp(Math.log(img.highHz) - (Math.log(img.highHz) - Math.log(img.lowHz)) * f) }
                            val s0 = currentWindow.sampleAt(first.position.x).coerceIn(0L, currentSession.length)
                            val h0 = hzAt(first.position.y); val h1 = hzAt(change.position.y)
                            onSpectralBox(com.layerbit.stave.engine.dsp.SpectralEdit.Box(min(s0, sample), max(s0, sample), min(h0, h1), max(h0, h1)))
                            continue
                        }
                        when {
                            editingEdge == 1 -> { val s = currentSelection ?: continue; onSelect(min(sample, s.last) until max(sample, s.last + 1)) }
                            editingEdge == 2 -> { val s = currentSelection ?: continue; onSelect(min(sample, s.first) until max(sample, s.first + 1)) }
                            selecting -> { val a = anchor!!; onSelect(min(a, sample) until max(a, sample)) }
                            else -> {
                                // The film follows the finger and the play-head (and the sound) with it.
                                val w = currentWindow.dragged(change.position.x - change.previousPosition.x)
                                onWindow(w)
                                onSeek(w.playhead)
                            }
                        }
                    }
                }
            }
    ) {
        val w = size.width; val h = size.height
        drawRect(StaveColors.Canvas, Offset.Zero, size)
        val lane = max(minLanePx, (h - rulerPx) / laneCount)
        // Lanes and clips.
        tracks.forEachIndexed { i, track ->
            val top = rulerPx + i * lane
            clipRect(0f, top, w, top + lane) {
                if (track.id == selectedTrackId) drawRect(StaveColors.Band.copy(alpha = 0.04f), Offset(0f, top), Size(w, lane))
                drawLine(StaveColors.Border, Offset(0f, top + lane - 0.5f), Offset(w, top + lane - 0.5f), 1f)
                if (spectrogram != null && track.id == selectedTrackId) {
                    val x0 = window.pixelOf(spectrogram.fromSample); val x1 = window.pixelOf(spectrogram.toSample)
                    drawImage(spectrogram.bitmap.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset(x0.roundToInt(), top.roundToInt()), dstSize = androidx.compose.ui.unit.IntSize((x1 - x0).roundToInt().coerceAtLeast(1), lane.roundToInt()))
                    spectralBox?.let { b ->
                        fun yOf(hz: Double): Float { val f = ((Math.log(spectrogram.highHz) - Math.log(hz)) / (Math.log(spectrogram.highHz) - Math.log(spectrogram.lowHz))).toFloat().coerceIn(0f, 1f); return top + f * lane }
                        val bx0 = window.pixelOf(b.fromSample); val bx1 = window.pixelOf(b.toSample)
                        val by0 = yOf(b.highHz); val by1 = yOf(b.lowHz)
                        drawRect(StaveColors.Band.copy(alpha = 0.25f), Offset(bx0, by0), Size(bx1 - bx0, by1 - by0))
                        drawRect(StaveColors.Band, Offset(bx0, by0), Size(bx1 - bx0, by1 - by0), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                    return@clipRect
                }
                // Take lanes stacked in the row: lane 0 on top. One lane fills the row as before.
                val takeLanes = laneCountOf(track)
                val subH = lane / takeLanes
                if (takeLanes > 1) for (l in 1 until takeLanes) drawLine(StaveColors.Border.copy(alpha = 0.5f), Offset(0f, top + l * subH), Offset(w, top + l * subH), 1f)
                for (clip in session.clipsOn(track.id)) {
                    val x0 = window.pixelOf(clip.position); val x1 = window.pixelOf(clip.end)
                    if (x1 < 0 || x0 > w) continue
                    val source = session.source(clip.sourceId) ?: continue
                    val subTop = top + clip.lane.coerceIn(0, takeLanes - 1) * subH
                    drawClip(clip, source, peaksFor(source), x0, x1, subTop + 2f, subH - 4f, window, clip.muted)
                    // A clip playing a processed copy says what was done to it: on 2 November the owner heard a
                    // "Remove clicks" copy under the song's plain name and took it for the song.
                    val label = if (source.kind == com.layerbit.stave.engine.project.Source.Kind.RENDER && source.name.contains(" · ")) clip.name + " · " + source.name.substringAfter(" · ") else clip.name
                    if (x1 - x0 > 40f && label.isNotBlank() && subH > 30f) labelAt(measurer, label, Offset(max(x0, 0f) + 6f, subTop + 4f), nameStyle, plate = true)
                }
                if (track.name.isNotBlank()) labelAt(measurer, track.name, Offset(6f, top + lane - measurer.measure(track.name, labelStyle).size.height - 4f), labelStyle, plate = true)
            }
        }
        // Selection.
        selection?.let { s ->
            val xs = window.pixelOf(s.first); val xe = window.pixelOf(s.last + 1)
            drawRect(StaveColors.Selection, Offset(xs, rulerPx), Size(xe - xs, h - rulerPx))
            drawLine(StaveColors.Band, Offset(xs, rulerPx), Offset(xs, h), 2f)
            drawLine(StaveColors.Band, Offset(xe, rulerPx), Offset(xe, h), 2f)
            // Handles at the top of each edge.
            drawRect(StaveColors.Band, Offset(xs - 6f, rulerPx), Size(12f, 14f))
            drawRect(StaveColors.Band, Offset(xe - 6f, rulerPx), Size(12f, 14f))
        }
        // Ruler.
        drawRect(StaveColors.Background, Offset.Zero, Size(w, rulerPx))
        drawLine(StaveColors.Border, Offset(0f, rulerPx - 0.5f), Offset(w, rulerPx - 0.5f), 1f)
        // Labels spaced by their own measured width: 90 px was narrower than "0:05.0" on a 400 dpi
        // screen, and at a large font size the times ran into one another.
        val labelGap = measurer.measure("0:00.000", labelStyle).size.width + 12.dp.toPx()
        val majorMs = Ruler.majorMs(window.samplesPerPixel, session.sampleRate, labelGap.toInt())
        val minor = Ruler.minorPerMajor(majorMs)
        val firstMs = Timecode.ofSamples(window.firstSample, session.sampleRate)
        val lastMs = Timecode.ofSamples(window.lastSample, session.sampleRate)
        var ms = (firstMs / majorMs) * majorMs
        val decimals = if (majorMs < 1000) 3 else if (majorMs < 10_000) 1 else 0
        while (ms <= lastMs + majorMs) {
            val x = window.pixelOf(ms * session.sampleRate / 1000)
            if (ms >= 0) {
                drawLine(StaveColors.Dim, Offset(x, rulerPx - 10f), Offset(x, rulerPx), 1f)
                labelAt(measurer, Timecode.display(ms, decimals), Offset(x + 3f, 2f), labelStyle)
            }
            for (k in 1 until minor) { val mx = window.pixelOf((ms + majorMs * k / minor) * session.sampleRate / 1000); drawLine(StaveColors.Border, Offset(mx, rulerPx - 5f), Offset(mx, rulerPx), 1f) }
            ms += majorMs
        }
        // Markers.
        for (m in session.markers) {
            val x = window.pixelOf(m.position)
            if (x < -20 || x > w + 20) continue
            val c = if (m.kind == Marker.Kind.CHAPTER) StaveColors.Running else StaveColors.Hot
            drawLine(c, Offset(x, rulerPx), Offset(x, h), 1f)
            drawRect(c, Offset(x, 2f), Size(8f, rulerPx - 6f))
        }
        // The play-head: a fixed line.
        val px = window.pixelOf(window.playhead)
        drawLine(StaveColors.Band, Offset(px, 0f), Offset(px, h), 2f)
        drawRect(StaveColors.Band, Offset(px - 5f, 0f), Size(10f, 6f))
    }
}

/** One clip: its body, then each channel's min/max in the wave ink and RMS in a brighter core. */
private fun DrawScope.drawClip(clip: com.layerbit.stave.engine.project.Clip, source: Source, peaks: List<Peaks>?, x0: Float, x1: Float, top: Float, height: Float, window: TimeWindow, muted: Boolean) {
    val left = max(x0, 0f); val right = min(x1, size.width)
    val bodyColour = if (muted) StaveColors.SurfaceDim else StaveColors.Surface
    drawRect(bodyColour, Offset(left, top), Size(right - left, height))
    drawRect(StaveColors.BorderStrong, Offset(left, top), Size(right - left, 1f))
    if (peaks == null || right - left < 1f) return
    val channels = peaks.size.coerceAtLeast(1)
    val chHeight = height / channels
    val columns = (right - left).roundToInt().coerceAtLeast(1)
    // The columns' source frames: the timeline stretch under the visible part of the clip, through
    // the clip's own mapping (rate ratio and stretch), so a 44.1k file in a 48k session draws true.
    val tlStart = (window.sampleAt(left) - clip.position).coerceIn(0, clip.length)
    val tlEnd = (window.sampleAt(right) - clip.position).coerceIn(0, clip.length)
    val srcStart = clip.offset + clip.sourceFrames(tlStart)
    val srcEnd = clip.offset + clip.sourceFrames(tlEnd)
    val wave = if (muted) StaveColors.Dim else StaveColors.Wave
    val rms = if (muted) StaveColors.Dim else StaveColors.WaveRms
    for (c in 0 until channels) {
        // Columns into one reused buffer (Peaks.columnsInto), lines into two more: no object per
        // column and no array per lane per frame. With 24 lanes the UI thread spent 12 ms a frame.
        val buf = DrawBuffers.get(columns)
        peaks[c].columnsInto(srcStart, srcEnd, columns, buf.cols)
        val mid = top + chHeight * c + chHeight / 2
        val scale = (chHeight / 2 - 2f)
        var nr = 0
        for (i in 0 until columns) {
            val k = if (clip.reversed) columns - 1 - i else i
            val x = left + i + 0.5f
            val yMax = mid - buf.cols[k * 3 + 1].coerceIn(-1f, 1f) * scale
            val yMin = mid - buf.cols[k * 3].coerceIn(-1f, 1f) * scale
            buf.peaks[i * 4] = x; buf.peaks[i * 4 + 1] = min(yMax, yMin); buf.peaks[i * 4 + 2] = x; buf.peaks[i * 4 + 3] = max(yMax, yMin) + 1f
            val r = buf.cols[k * 3 + 2].coerceIn(0f, 1f) * scale
            if (r > 0.5f) { buf.rms[nr] = x; buf.rms[nr + 1] = mid - r; buf.rms[nr + 2] = x; buf.rms[nr + 3] = mid + r; nr += 4 }
        }
        drawIntoCanvas { canvas ->
            val paint = buf.paint
            paint.color = wave.toArgb(); canvas.nativeCanvas.drawLines(buf.peaks, 0, columns * 4, paint)
            if (nr > 0) { paint.color = rms.toArgb(); canvas.nativeCanvas.drawLines(buf.rms, 0, nr, paint) }
        }
    }
}

/** Scratch for drawing waveforms, grown to the widest clip seen; drawing is on the main thread only. */
private object DrawBuffers {
    class Set(n: Int) { val cols = FloatArray(n * 3); val peaks = FloatArray(n * 4); val rms = FloatArray(n * 4); val paint = android.graphics.Paint().apply { strokeWidth = 1f; isAntiAlias = false } }
    private var set = Set(2048)
    fun get(columns: Int): Set { if (set.cols.size < columns * 3) set = Set(columns * 2); return set }
}

/**
 * drawText with no size lays the text out in the room between [at] and the canvas's far edges:
 * it throws when that room is negative and wraps the label a letter a line when it is small.
 * The ruler draws one tick past the right edge on purpose (so a label slides in rather than
 * pops), and that label crashed the editor the first time a sound was opened on a phone, then,
 * guarded, stood as a column of single characters. The label is laid out at its own width and
 * clipped to the canvas instead.
 */
private fun DrawScope.labelAt(measurer: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, style: TextStyle, plate: Boolean = false) {
    if (at.x >= size.width || at.y >= size.height) return
    val layout = measurer.measure(text, style, maxLines = 1, softWrap = false)
    clipRect(0f, 0f, size.width, size.height) {
        // A clip or track name sits on its own small plate so it reads over a loud waveform;
        // in the first screenshots the names were lost in the wave's ink.
        if (plate) drawRoundRect(StaveColors.Canvas.copy(alpha = 0.78f), Offset(at.x - 4f, at.y - 1f), Size(layout.size.width + 8f, layout.size.height + 2f), androidx.compose.ui.geometry.CornerRadius(6f, 6f))
        drawText(layout, topLeft = at)
    }
}
