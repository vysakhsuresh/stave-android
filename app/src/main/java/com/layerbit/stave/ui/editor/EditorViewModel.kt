package com.layerbit.stave.ui.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.export.ExportSettings
import com.layerbit.stave.engine.project.Clip
import com.layerbit.stave.engine.project.History
import com.layerbit.stave.engine.project.Ids
import com.layerbit.stave.engine.project.Marker
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.SessionEdits
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.engine.project.Track
import com.layerbit.stave.engine.wave.Peaks
import com.layerbit.stave.engine.wave.TimeWindow
import com.layerbit.stave.export.Exporter
import com.layerbit.stave.files.PeakStore
import com.layerbit.stave.files.ProjectFiles
import com.layerbit.stave.processing.OfflineTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One open project: its session and history, the view of it, what is selected, which sheet is
 * up, and every operation the screens call. The graph is told about every change; the file is
 * saved 1.5 s after the last one and on close.
 */
class EditorViewModel(private val app: StaveApplication, val projectId: String) : ViewModel() {

    val files = ProjectFiles(app.projects.dir(projectId))
    val peaks = PeakStore(files)
    val transport get() = app.transport

    data class Busy(val label: String, val progress: Float)
    data class Ui(
        val session: Session = Session(),
        val window: TimeWindow = TimeWindow.initial(0, 1000),
        val selection: LongRange? = null,
        val selectedTrackId: String? = null,
        val tab: EditorTab = EditorTab.EDIT,
        val sheet: EditSheet? = null,
        val busy: Busy? = null,
        val message: String? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val undoLabel: String? = null,
        val loaded: Boolean = false,
        val exportSettings: ExportSettings = ExportSettings(),
        val lastExport: Exporter.Result? = null,
        val clipboard: SessionEdits.Clipboard? = null
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private lateinit var history: History
    private var saveJob: Job? = null
    private var widthPx = 1000

    val session: Session get() = _ui.value.session

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { files.load() } ?: Session(kind = app.projects.list().firstOrNull { it.id == projectId }?.kind ?: "edit", sampleRate = app.settings.current.sampleRate.takeIf { it > 0 } ?: 48_000)
            history = History(loaded)
            transport.open(loaded.sampleRate)
            val rate = if (transport.available) transport.sampleRate else loaded.sampleRate
            val s = if (loaded.sources.isEmpty() && rate != loaded.sampleRate) loaded.copy(sampleRate = rate) else loaded
            if (s !== loaded) history = History(s)
            _ui.value = _ui.value.copy(session = s, window = TimeWindow.initial(s.length, widthPx), loaded = true, exportSettings = _ui.value.exportSettings.copy(loudnessTargetId = app.settings.current.loudnessTargetId), selectedTrackId = s.tracks.firstOrNull()?.id)
            pushToGraph()
            val pending = app.handoff.take(projectId)
            if (pending.isNotEmpty()) importAll(pending)
        }
    }

    // ---- the view --------------------------------------------------------------------------

    fun setWidth(px: Int) { if (px > 0 && px != widthPx) { widthPx = px; _ui.value = _ui.value.copy(window = _ui.value.window.copy(widthPx = px)) } }
    fun setWindow(w: TimeWindow) { _ui.value = _ui.value.copy(window = w) }
    fun fit() { _ui.value = _ui.value.copy(window = _ui.value.window.copy(totalSamples = session.length).fitted()) }
    fun zoomBy(factor: Float) { val w = _ui.value.window; setWindow(w.zoomed(factor, w.widthPx * TimeWindow.PLAYHEAD_FRACTION.toFloat())) }
    fun select(range: LongRange?) { _ui.value = _ui.value.copy(selection = range?.takeIf { it.last > it.first }) }
    fun selectAll() { select(0L until session.length) }
    fun selectTrack(id: String?) { _ui.value = _ui.value.copy(selectedTrackId = id) }
    fun setTab(tab: EditorTab) { _ui.value = _ui.value.copy(tab = tab) }
    fun setSheet(sheet: EditSheet?) { _ui.value = _ui.value.copy(sheet = if (_ui.value.sheet == sheet) null else sheet) }
    fun dismissMessage() { _ui.value = _ui.value.copy(message = null) }
    fun setExportSettings(s: ExportSettings) { _ui.value = _ui.value.copy(exportSettings = s) }

    /** The play-head follows the graph while playing; a seek moves both. */
    fun seek(frame: Long) {
        val f = frame.coerceIn(0, session.length.coerceAtLeast(0))
        transport.seek(f)
        _ui.value = _ui.value.copy(window = _ui.value.window.copy(playhead = f))
    }
    fun followPlayhead(frame: Long) { if (transport.state.value.playing) _ui.value = _ui.value.copy(window = _ui.value.window.copy(playhead = frame)) }
    fun togglePlay() {
        if (transport.state.value.playing) transport.pause()
        else { transport.seek(_ui.value.window.playhead); transport.setLoop(_ui.value.selection); transport.play() }
    }

    /** The current track's clips' peaks, by source id, for the canvas. */
    fun peaksFor(source: Source): List<Peaks>? = runCatching { peaks.forSource(source.id, files.sourceFile(source.file), source.channels, source.sampleRate) }.getOrNull()

    // ---- history -----------------------------------------------------------------------------

    private fun commit(next: Session, label: String, gesture: String? = null) {
        history.record(next, label, gesture)
        after()
    }

    private fun after() {
        val s = history.current
        _ui.value = _ui.value.copy(session = s, canUndo = history.canUndo, canRedo = history.canRedo, undoLabel = history.undoLabel, window = _ui.value.window.copy(totalSamples = s.length))
        pushToGraph()
        scheduleSave()
    }

    fun endGesture() { history.endGesture() }
    fun undo() { if (history.undo()) { _ui.value = _ui.value.copy(selection = null); after() } }
    fun redo() { if (history.redo()) { _ui.value = _ui.value.copy(selection = null); after() } }

    private fun pushToGraph() { if (transport.available) transport.setSession(session, files.dir) }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(1500); saveNow() }
    }

    fun saveNow() {
        val s = session
        viewModelScope.launch(Dispatchers.IO) { runCatching { files.save(s); files.snapshotIfDue(s) } }
    }

    override fun onCleared() {
        saveJob?.cancel()
        runCatching { files.save(session) }
        transport.pause()
    }

    // ---- importing --------------------------------------------------------------------------

    fun importAll(uris: List<Uri>) {
        viewModelScope.launch {
            for ((i, uri) in uris.withIndex()) {
                val name = app.media.displayName(uri)
                _ui.value = _ui.value.copy(busy = Busy("Opening $name", 0f))
                try {
                    val source = app.media.import(uri, files, session.sampleRate) { p -> _ui.value = _ui.value.copy(busy = Busy("Opening $name", (i + p) / uris.size)) }
                    withContext(Dispatchers.IO) { peaksFor(source) }
                    addSource(source)
                } catch (e: Exception) {
                    _ui.value = _ui.value.copy(message = e.message ?: "Could not open $name")
                }
            }
            _ui.value = _ui.value.copy(busy = null)
            if (session.name.isBlank() && session.sources.isNotEmpty()) rename(session.sources.first().name)
            fit()
        }
    }

    /** A new source laid at the end of the selected track (a new track when there is none), as one step. */
    private fun addSource(source: Source) {
        var s = session.copy(sources = session.sources + source)
        val trackId = _ui.value.selectedTrackId?.takeIf { id -> s.track(id)?.kind == Track.Kind.AUDIO } ?: run {
            val t = Track(Ids.next("t"), "Track ${s.audioTracks.size + 1}")
            s = SessionEdits.addTrack(s, t); t.id
        }
        val at = s.clipsOn(trackId).maxOfOrNull { it.end } ?: 0L
        s = SessionEdits.add(s, SessionEdits.clipFor(source, trackId, at, s.sampleRate))
        _ui.value = _ui.value.copy(selectedTrackId = trackId)
        commit(s, "Open ${source.name}")
    }

    fun rename(name: String) { commit(session.copy(name = name), "Rename"); viewModelScope.launch(Dispatchers.IO) { app.projects.rename(projectId, name) } }

    // ---- edits over the selection ----------------------------------------------------------

    private val range: LongRange? get() = _ui.value.selection
    private val trackId: String? get() = _ui.value.selectedTrackId

    fun deleteSelection(ripple: Boolean = true) { val r = range ?: return; commit(SessionEdits.deleteRange(session, r.first, r.last + 1, trackId, ripple), "Delete"); select(null); seek(r.first) }
    fun cutSelection() { val r = range ?: return; _ui.value = _ui.value.copy(clipboard = SessionEdits.copyRange(session, r.first, r.last + 1, setOfNotNull(trackId))); deleteSelection() }
    fun copySelection() { val r = range ?: return; _ui.value = _ui.value.copy(clipboard = SessionEdits.copyRange(session, r.first, r.last + 1, setOfNotNull(trackId))); _ui.value = _ui.value.copy(message = "Copied") }
    fun paste(insert: Boolean = true) {
        val board = _ui.value.clipboard ?: return
        val at = _ui.value.window.playhead
        commit(SessionEdits.paste(session, board, at, trackId, insert), "Paste")
        select(at until at + board.length)
    }
    fun splitAtPlayhead() { commit(SessionEdits.splitAll(session, _ui.value.window.playhead, trackId), "Split") }
    fun insertSilence(samples: Long) { commit(SessionEdits.insertSilence(session, _ui.value.window.playhead, samples, trackId), "Insert silence") }
    fun cropToSelection() { val r = range ?: return; commit(SessionEdits.crop(session, r.first, r.last + 1), "Crop"); select(null); seek(0) }
    fun silenceSelection() = applyOffline("Silence", OfflineTools.silence())
    fun addMarker(kind: Marker.Kind = Marker.Kind.MARKER, name: String = "") {
        val at = _ui.value.window.playhead
        val n = name.ifBlank { if (kind == Marker.Kind.CHAPTER) "Chapter ${session.markers.count { it.kind == Marker.Kind.CHAPTER } + 1}" else "Marker ${session.markers.size + 1}" }
        commit(SessionEdits.addMarker(session, at, n, kind), "Add marker")
    }
    fun removeMarker(id: String) { commit(SessionEdits.removeMarker(session, id), "Remove marker") }
    fun renameMarker(id: String, name: String) { commit(session.copy(markers = session.markers.map { if (it.id == id) it.copy(name = name) else it }), "Rename marker") }
    fun setMarkerKind(id: String, kind: Marker.Kind) { commit(session.copy(markers = session.markers.map { if (it.id == id) it.copy(kind = kind) else it }), "Marker kind") }

    /** A clip-level change on every clip inside the selection (or under the play-head when there is none). */
    fun updateClipsInSelection(label: String, change: (Clip) -> Clip) {
        val r = range
        val targets = if (r != null) session.clipsIn(r.first, r.last + 1, trackId) else listOfNotNull(trackId?.let { session.clipAt(it, _ui.value.window.playhead) })
        if (targets.isEmpty()) return
        var s = session
        for (c in targets) s = SessionEdits.replace(s, change(c))
        commit(s, label)
    }

    fun setClipGain(db: Double, gesture: String? = "clipgain") { updateClipsInSelection("Gain") { it.copy(gainDb = db) }.also { if (gesture == null) endGesture() } }
    fun setFades(inLength: Long?, inShape: FadeShape?, outLength: Long?, outShape: FadeShape?) {
        updateClipsInSelection("Fades") { c -> SessionEdits.fitFades(c.copy(fadeIn = c.fadeIn.copy(length = inLength ?: c.fadeIn.length, shape = inShape ?: c.fadeIn.shape), fadeOut = c.fadeOut.copy(length = outLength ?: c.fadeOut.length, shape = outShape ?: c.fadeOut.shape))) }
    }
    fun toggleMuteClips() = updateClipsInSelection("Mute") { it.copy(muted = !it.muted) }
    fun reverseClips() = applyOffline("Reverse", OfflineTools.reverse())

    /**
     * An offline tool over the selection (or the whole track with none): on a worker, landing
     * through recordLate so a gesture under way is not split by the result.
     */
    fun applyOffline(label: String, transform: OfflineTools.Transform, onDone: (() -> Unit)? = null) {
        val r = range ?: (0L until session.length).takeIf { it.last > 0 } ?: return
        val tid = trackId
        val before = session
        _ui.value = _ui.value.copy(busy = Busy(label, 0f))
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { OfflineTools.applyToRange(before, files, r, tid, label, transform) } }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { next ->
                if (next !== before) {
                    // Build the new sources' peaks before they are drawn.
                    withContext(Dispatchers.IO) { next.sources.filter { s -> before.source(s.id) == null }.forEach { peaksFor(it) } }
                    history.recordLate(label) { current -> if (current === before) next else OfflineTools.applyToRange(current, files, r, tid, label, transform) }
                    after()
                    onDone?.invoke()
                }
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "$label failed") }
        }
    }

    /** The planar samples of the selection on the selected track's first clip, for analysis (hum, noise, loudness). */
    suspend fun grabSelection(): OfflineTools.Grab? = withContext(Dispatchers.IO) {
        val r = range ?: return@withContext null
        val clip = session.clipsIn(r.first, r.last + 1, trackId).firstOrNull() ?: return@withContext null
        val source = session.source(clip.sourceId) ?: return@withContext null
        val from = maxOf(r.first, clip.position); val to = minOf(r.last + 1, clip.end)
        val part = clip.copy(position = from, offset = clip.offset + clip.sourceFrames(from - clip.position), length = to - from)
        OfflineTools.read(files, part, source, session.sampleRate)
    }

    // ---- tracks ------------------------------------------------------------------------------

    fun addTrack(kind: Track.Kind = Track.Kind.AUDIO) {
        val t = Track(Ids.next("t"), if (kind == Track.Kind.BUS) "Bus ${session.buses.size + 1}" else "Track ${session.audioTracks.size + 1}", kind)
        commit(SessionEdits.addTrack(session, t), "Add track"); selectTrack(t.id)
    }
    fun removeTrack(id: String) { commit(SessionEdits.removeTrack(session, id), "Remove track"); if (trackId == id) selectTrack(session.tracks.firstOrNull()?.id) }
    fun updateTrack(id: String, label: String, gesture: String? = null, change: (Track) -> Track) { commit(SessionEdits.updateTrack(session, id, change), label, gesture) }
    fun liveVolume(id: String, db: Double) { val i = session.tracks.indexOfFirst { it.id == id }; if (i >= 0) transport.liveVolume(i, com.layerbit.stave.engine.dsp.Gain.dbToLinear(db).toFloat()) }
    fun livePan(id: String, pan: Double) { val i = session.tracks.indexOfFirst { it.id == id }; if (i >= 0) transport.livePan(i, pan.toFloat()) }
    fun setMasterVolume(db: Double, gesture: String? = "master") { commit(session.copy(masterVolumeDb = db), "Master", gesture) }

    // ---- export -----------------------------------------------------------------------------

    fun export(fallbackName: String) {
        val s = session
        val settings = _ui.value.exportSettings
        _ui.value = _ui.value.copy(busy = Busy("Exporting", 0f))
        viewModelScope.launch {
            val result = runCatching {
                Exporter(app).export(s, files, settings, s.name.ifBlank { fallbackName }, render = { out ->
                    withContext(Dispatchers.IO) { transport.renderOffline(0, s.length, out) }
                }) { p, label -> _ui.value = _ui.value.copy(busy = Busy(label, p)) }
            }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { r -> _ui.value = _ui.value.copy(lastExport = r, message = null) }
                .onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Export failed") }
        }
    }

    fun exportFile(): File? = _ui.value.lastExport?.file
}
