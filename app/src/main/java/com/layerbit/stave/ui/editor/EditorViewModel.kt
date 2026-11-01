package com.layerbit.stave.ui.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.audio.AudioService
import com.layerbit.stave.engine.dsp.CompressorSettings
import com.layerbit.stave.engine.dsp.FadeShape
import com.layerbit.stave.engine.dsp.LoudnessTarget
import com.layerbit.stave.engine.json.Json
import com.layerbit.stave.engine.json.JsonArray
import com.layerbit.stave.engine.json.JsonObject
import com.layerbit.stave.engine.transcript.Fillers
import com.layerbit.stave.engine.transcript.Silences
import com.layerbit.stave.engine.transcript.Transcript
import com.layerbit.stave.engine.transcript.Word
import com.layerbit.stave.engine.transcript.WordCuts
import com.layerbit.stave.files.RawAudio
import com.layerbit.stave.voice.WhisperRunner
import com.layerbit.stave.ui.components.Formats
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
    fun setWindow(w: TimeWindow) { _ui.value = _ui.value.copy(window = w); refreshSpectrogram() }
    fun fit() { _ui.value = _ui.value.copy(window = _ui.value.window.copy(totalSamples = session.length).fitted()) }
    fun zoomBy(factor: Float) { val w = _ui.value.window; setWindow(w.zoomed(factor, w.widthPx * TimeWindow.PLAYHEAD_FRACTION.toFloat())) }
    fun select(range: LongRange?) { _ui.value = _ui.value.copy(selection = range?.takeIf { it.last > it.first }) }
    fun selectAll() { select(0L until session.length) }
    fun selectTrack(id: String?) { if (id != _ui.value.selectedTrackId) { _ui.value = _ui.value.copy(selectedTrackId = id); refreshSpectrogram() } }
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
        if (!followingTranscript && _voice.value.transcript != null && next.length != session.length) _voice.value = _voice.value.copy(stale = true)
        after()
    }

    private fun after() {
        val s = history.current
        _ui.value = _ui.value.copy(session = s, canUndo = history.canUndo, canRedo = history.canRedo, undoLabel = history.undoLabel, window = _ui.value.window.copy(totalSamples = s.length))
        pushToGraph()
        scheduleSave()
        refreshSpectrogram()
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
        player?.close()
        midiInput?.stop()
        transport.pause()
        if (transport.state.value.recording) transport.stopRecording()
        transport.closeInput()
        whisper.close()
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

    // ---- voice: recording, words, clean-up ------------------------------------------------

    data class VoiceState(
        val armed: Boolean = false, val monitor: Boolean = false, val roundTripMs: Double = 0.0, val recordedMs: Long = 0, val dropped: Long = 0, val inputHold: Float = 0f,
        val language: String = "auto", val transcribing: Float? = null, val transcript: Transcript? = null, val selectedWords: Set<Int> = emptySet(), val stale: Boolean = false,
        val silenceMinMs: Long = 800, val silenceKeepMs: Long = 200, val targetId: String = "podcast",
        /** Record only inside the selection: the take starts at its start and stops at its end. */
        val punch: Boolean = false,
        /** Typed lyrics aligned to the transcript's words, a line each with its time, for Sing along. */
        val lyrics: List<com.layerbit.stave.engine.transcript.Lyrics.Line> = emptyList()
    )

    private val _voice = MutableStateFlow(VoiceState(targetId = app.settings.current.loudnessTargetId).let { v -> loadTranscript()?.let { v.copy(transcript = it) } ?: v })
    val voice: StateFlow<VoiceState> = _voice.asStateFlow()
    val whisper = WhisperRunner(app.models)
    private var followingTranscript = false
    private var recordStart = 0L
    /** Where a punched take stops itself, in samples on the timeline; null when not punching. */
    private var punchEnd: Long? = null
    private var recordFile: File? = null
    private var recordJob: Job? = null

    private val transcriptFile get() = File(files.dir, "transcript.json")

    private fun loadTranscript(): Transcript? = runCatching {
        val o = Json.parseObject(transcriptFile.readText())
        Transcript(o.arr("words")?.let { a -> (0 until a.size).mapNotNull { i -> a.arr(i)?.let { w -> Word(w.string(2) ?: "", w.long(0), w.long(1)) } } } ?: emptyList(), o.string("language") ?: "")
    }.getOrNull()

    private fun setTranscript(t: Transcript?, stale: Boolean = false) {
        _voice.value = _voice.value.copy(transcript = t, selectedWords = emptySet(), stale = stale)
        viewModelScope.launch(Dispatchers.IO) {
            if (t == null) transcriptFile.delete()
            else transcriptFile.writeText(Json.write(JsonObject().put("language", t.language).put("words", JsonArray().also { a -> t.words.forEach { w -> a.add(JsonArray().add(w.startMs).add(w.endMs).add(w.text)) } })))
        }
    }

    fun armInput() {
        if (!transport.available) { _ui.value = _ui.value.copy(message = "The audio engine is not running on this phone."); return }
        if (!transport.openInput(1, unprocessed = true)) { _ui.value = _ui.value.copy(message = "The microphone could not be opened."); return }
        _voice.value = _voice.value.copy(armed = true, roundTripMs = transport.state.value.latencyMs + transport.inputLatencyMs())
        AudioService.start(app, recording = false, title = session.name.ifBlank { "Stave" })
    }

    fun disarmInput() { transport.setMonitor(false, 1f); transport.closeInput(); _voice.value = _voice.value.copy(armed = false, monitor = false); AudioService.stop(app) }

    fun setMonitor(on: Boolean) { _voice.value = _voice.value.copy(monitor = on); transport.setMonitor(on, 1f) }

    fun startRecording() {
        if (!_voice.value.armed) armInput()
        if (!_voice.value.armed) return
        val take = File(files.takes, "take-${System.currentTimeMillis()}.wav")
        recordStart = _ui.value.window.playhead
        if (!transport.startRecording(take)) { _ui.value = _ui.value.copy(message = "Recording could not start."); return }
        recordFile = take
        AudioService.start(app, recording = true, title = session.name.ifBlank { "Stave" })
        val loop = _looper.value.takeIf { it.active }
        if (loop != null) { recordStart = loop.start; _looper.value = loop.copy(recordingLayer = true) }
        // Punch: the take starts at the selection's start and the recorder stops itself at its end.
        val punch = _ui.value.selection?.takeIf { _voice.value.punch && loop == null && it.last > it.first }
        punchEnd = punch?.let { recordStart = it.first; it.last + 1 }
        transport.seek(recordStart)
        transport.setLoop(loop?.let { it.start until it.start + it.length })
        if (session.length > recordStart || loop != null) transport.play()
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
            while (true) {
                val frames = transport.recordedFrames()
                _voice.value = _voice.value.copy(recordedMs = frames * 1000 / transport.sampleRate, dropped = transport.droppedFrames(), inputHold = maxOf(_voice.value.inputHold * 0.97f, transport.state.value.inputPeak))
                val lp = _looper.value
                val shown = if (lp.active && lp.recordingLayer && lp.length > 0) lp.start + frames % lp.length else recordStart + frames
                _ui.value = _ui.value.copy(window = _ui.value.window.copy(playhead = shown, totalSamples = maxOf(session.length, shown)))
                val end = punchEnd
                if (end != null && recordStart + frames >= end) { stopRecording(); break }
                delay(50)
            }
        }
    }

    fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        transport.stopRecording()
        transport.pause()
        AudioService.start(app, recording = false, title = session.name.ifBlank { "Stave" })
        val wav = recordFile ?: return
        recordFile = null
        punchEnd = null
        viewModelScope.launch {
            val rate = transport.sampleRate
            val count = session.sources.count { it.kind == Source.Kind.TAKE } + 1
            val source = withContext(Dispatchers.IO) {
                RawAudio.repairWavHeader(wav)
                app.media.takeToSource(files, wav, rate, 1, "Take $count").also { peaksFor(it) }
            }
            if (source.frames <= 0) { wav.delete(); return@launch }
            val lp = _looper.value
            if (lp.active && lp.recordingLayer && lp.length > 0) { landLayers(source, lp); return@launch }
            var s = session.copy(sources = session.sources + source)
            val trackId = _ui.value.selectedTrackId?.takeIf { id -> s.track(id)?.kind == Track.Kind.AUDIO } ?: run { val t = Track(Ids.next("t"), "Voice"); s = SessionEdits.addTrack(s, t); t.id }
            // A take over existing sound goes on its own lane, so nothing underneath is covered.
            val lane = if (s.clipsIn(recordStart, recordStart + source.frames, trackId).isEmpty()) 0 else (s.clipsOn(trackId).maxOfOrNull { it.lane } ?: 0) + 1
            s = SessionEdits.add(s, SessionEdits.clipFor(source, trackId, recordStart, s.sampleRate).copy(lane = lane))
            if (lane > 0) s = SessionEdits.updateTrack(s, trackId) { it.copy(lanes = maxOf(it.lanes, lane + 1)) }
            _ui.value = _ui.value.copy(selectedTrackId = trackId)
            if (s.name.isBlank()) s = s.copy(name = "Take $count")
            commit(s, "Record take")
        }
    }

    fun setLanguage(code: String) { _voice.value = _voice.value.copy(language = code) }
    fun setPunch(on: Boolean) { _voice.value = _voice.value.copy(punch = on) }

    /** Typed lyrics laid against the transcript: each line gets the time of the words it matches (engine Lyrics, executed in LyricsChecks). */
    fun alignLyrics(text: String) {
        val t = _voice.value.transcript ?: return
        _voice.value = _voice.value.copy(lyrics = com.layerbit.stave.engine.transcript.Lyrics.align(text, t.words))
    }

    /** Hear one take lane of a track and mute the others' clips; null hears every lane together. One edit, one undo. */
    fun chooseLane(trackId: String, lane: Int?) {
        val s = session
        val next = s.copy(clips = s.clips.map { c -> if (c.trackId == trackId) c.copy(muted = lane != null && c.lane != lane) else c })
        commit(next, if (lane == null) "Hear every take" else "Take ${lane + 1}")
    }

    /** The whole mix as planar channels at the session rate, through the graph's offline render. */
    private suspend fun mixdown(from: Long, to: Long): List<FloatArray>? = withContext(Dispatchers.IO) {
        if (to <= from) return@withContext null
        val tmp = File(app.cacheDir, "work").apply { mkdirs() }.let { File(it, "mix-${System.nanoTime()}.f32") }
        try {
            val n = transport.renderOffline(from, to, tmp)
            if (n <= 0) return@withContext null
            val inter = RawAudio.read(tmp, 2, 0, n.toInt())
            com.layerbit.stave.processing.OfflineTools.deinterleave(inter, 2)
        } finally { tmp.delete() }
    }

    fun transcribe() {
        val s = session
        val range = _ui.value.selection ?: (0L until s.length)
        _voice.value = _voice.value.copy(transcribing = 0f)
        viewModelScope.launch {
            val result = runCatching {
                val chs = mixdown(range.first, range.last + 1) ?: throw IllegalStateException("Nothing to transcribe.")
                whisper.transcribe(chs, s.sampleRate, range.first * 1000 / s.sampleRate, _voice.value.language) { p -> _voice.value = _voice.value.copy(transcribing = p) }
            }
            _voice.value = _voice.value.copy(transcribing = null)
            result.onSuccess { t ->
                if (t == null) _ui.value = _ui.value.copy(message = "The speech model is not on this phone.")
                else {
                    // Words from a partial transcription replace those in the same stretch and keep the rest.
                    val previous = _voice.value.transcript
                    val kept = previous?.words?.filter { w -> (w.startMs + w.endMs) / 2 !in (range.first * 1000 / s.sampleRate) until ((range.last + 1) * 1000 / s.sampleRate) } ?: emptyList()
                    setTranscript(Transcript((kept + t.words).sortedBy { it.startMs }, t.language.ifBlank { previous?.language ?: "" }))
                }
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Transcription failed") }
        }
    }

    fun toggleWord(index: Int) {
        val v = _voice.value
        val set = if (index in v.selectedWords) v.selectedWords - index else v.selectedWords + index
        _voice.value = v.copy(selectedWords = set)
        // The sound under the chosen words is the selection, so Play hears what Delete would cut.
        val t = v.transcript ?: return
        if (set.isEmpty()) select(null) else {
            val rate = session.sampleRate
            val from = set.minOf { t.words[it].startMs } * rate / 1000; val to = set.maxOf { t.words[it].endMs } * rate / 1000
            select(from until to)
        }
    }

    /** Cuts in ms removed from every track as one undo step, the transcript following each. */
    private fun cutRanges(rangesMs: List<LongRange>, label: String) {
        if (rangesMs.isEmpty()) return
        val rate = session.sampleRate
        var s = session
        var t = _voice.value.transcript
        for (r in rangesMs.sortedByDescending { it.first }) {
            val from = r.first * rate / 1000; val to = (r.last + 1) * rate / 1000
            s = SessionEdits.deleteRange(s, from, to, null, ripple = true)
            t = t?.afterCut(r.first, r.last + 1)
        }
        followingTranscript = true
        try { commit(s, label) } finally { followingTranscript = false }
        setTranscript(t, stale = _voice.value.stale)
        select(null)
        seek(rangesMs.minOf { it.first } * rate / 1000)
    }

    fun deleteSelectedWords() {
        val v = _voice.value; val t = v.transcript ?: return
        cutRanges(WordCuts.rangesFor(t.words, v.selectedWords, totalMs = session.durationMs), "Delete words")
    }

    fun removeFillers() {
        val t = _voice.value.transcript ?: return
        val found = Fillers.find(t.words)
        if (found.isEmpty()) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.voice_fillers_none)); return }
        cutRanges(WordCuts.rangesFor(t.words, found, totalMs = session.durationMs), "Take out fillers")
        _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.voice_fillers_removed, found.size))
    }

    fun setSilenceRule(minMs: Long? = null, keepMs: Long? = null) { _voice.value = _voice.value.copy(silenceMinMs = minMs ?: _voice.value.silenceMinMs, silenceKeepMs = keepMs ?: _voice.value.silenceKeepMs) }

    fun removeSilences() {
        val s = session
        _ui.value = _ui.value.copy(busy = Busy("Finding pauses", 0f))
        viewModelScope.launch {
            val chs = mixdown(0, s.length)
            _ui.value = _ui.value.copy(busy = null)
            if (chs == null) return@launch
            val mono = FloatArray(chs[0].size) { (chs[0][it] + chs[1][it]) * 0.5f }
            val levels = Silences.levels(mono, s.sampleRate, 20)
            val found = Silences.find(levels, 20, Silences.thresholdFor(levels), _voice.value.silenceMinMs, _voice.value.silenceKeepMs)
            if (found.isEmpty()) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.voice_silences_found, 0)); return@launch }
            cutRanges(found, "Remove silences")
            _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.voice_silences_found, found.size))
        }
    }

    fun setVoiceTarget(id: String) { _voice.value = _voice.value.copy(targetId = id) }

    private fun chain(vararg steps: com.layerbit.stave.processing.OfflineTools.Transform) = com.layerbit.stave.processing.OfflineTools.Transform { chs, rate -> steps.fold(chs) { acc, t -> t.apply(acc, rate) } }

    /** Noise out, sibilance softened, the loud bits tamed, the whole brought to the target: one step, one undo. */
    fun cleanUpVoice() {
        val target = LoudnessTarget.byId(_voice.value.targetId)
        val T = com.layerbit.stave.processing.OfflineTools
        select(null)
        applyOffline("Clean up voice", chain(T.dcRemove(), T.denoise(18.0, 0.85f, null), T.deess(6500.0, -26.0), T.compress(CompressorSettings(-20.0, 2.5, 6.0, 8.0, 150.0, 0.0)), T.normaliseLoudness(target.lufs, target.truePeakDbtp)))
    }

    fun evenOutVolume() {
        val target = LoudnessTarget.byId(_voice.value.targetId)
        val T = com.layerbit.stave.processing.OfflineTools
        select(null)
        applyOffline("Even out volume", chain(T.compress(CompressorSettings(-26.0, 3.0, 12.0, 25.0, 400.0, 0.0)), T.normaliseLoudness(target.lufs, target.truePeakDbtp)))
    }

    fun applyVoiceTarget() {
        val target = LoudnessTarget.byId(_voice.value.targetId)
        select(null)
        applyOffline("Loudness to ${target.label}", com.layerbit.stave.processing.OfflineTools.normaliseLoudness(target.lufs, target.truePeakDbtp))
    }

    // ---- studio: tempo, bounce, stems, ducking ---------------------------------------------

    fun setTempo(bpm: Double) { commit(session.copy(tempo = listOf(com.layerbit.stave.engine.project.TempoEntry(0, bpm.coerceIn(20.0, 300.0), session.tempo.first().beatsPerBar))), "Tempo", gesture = "tempo") }

    /** The whole mix rendered to a new source on a new track, muted tracks and all as heard. */
    fun bounceToTrack() {
        val s = session
        if (s.length <= 0) return
        _ui.value = _ui.value.copy(busy = Busy("Bouncing", 0f))
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                val id = Ids.next("s")
                val out = files.newSourceFile(id)
                val n = transport.renderOffline(0, s.length, out)
                if (n <= 0) { out.delete(); return@withContext null }
                Source(id, "Bounce", "media/${out.name}", s.sampleRate, 2, n, Source.Kind.RENDER).also { peaksFor(it) }
            }
            _ui.value = _ui.value.copy(busy = null)
            if (source == null) { _ui.value = _ui.value.copy(message = "Nothing to bounce."); return@launch }
            val track = Track(Ids.next("t"), "Bounce ${session.sources.count { it.kind == Source.Kind.RENDER && it.name.startsWith("Bounce") } + 1}")
            var next = SessionEdits.addTrack(session.copy(sources = session.sources + source), track)
            next = SessionEdits.add(next, SessionEdits.clipFor(source, track.id, 0, next.sampleRate))
            history.recordLate("Bounce") { next }
            after()
            selectTrack(track.id)
        }
    }

    /** Every audio track rendered on its own to a 24-bit WAV in exports/, published to Music/Stave. */
    fun exportStems() {
        val s = session
        val tracks = s.audioTracks
        if (tracks.isEmpty() || s.length <= 0) return
        viewModelScope.launch {
            val exporter = Exporter(app)
            for ((i, t) in tracks.withIndex()) {
                _ui.value = _ui.value.copy(busy = Busy("Stem ${i + 1} of ${tracks.size}: ${t.name}", i.toFloat() / tracks.size))
                // Solo this one in a copy of the session, render that, and leave the live snapshot as it was.
                val solo = s.copy(tracks = s.tracks.map { tr -> if (tr.kind == Track.Kind.AUDIO) tr.copy(solo = tr.id == t.id, muted = false) else tr })
                val settings = ExportSettings(formatId = "wav", depth = 24, title = "${s.name.ifBlank { "Stave" }} - ${t.name}", normaliseToTarget = false)
                runCatching {
                    exporter.export(solo, files, settings, t.name, render = { out ->
                        withContext(Dispatchers.IO) { transport.setSession(solo, files.dir); try { transport.renderOffline(0, s.length, out) } finally { transport.setSession(s, files.dir) } }
                    }) { _, _ -> }
                }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Stem export failed") }
            }
            _ui.value = _ui.value.copy(busy = null, message = "${tracks.size} stems saved to Music/Stave")
        }
    }

    /** Volume automation on [musicTrackId] that dips wherever [voiceTrackId] speaks, as one step. */
    fun duckUnder(musicTrackId: String, voiceTrackId: String, depthDb: Double) {
        val s = session
        val music = s.track(musicTrackId) ?: return
        _ui.value = _ui.value.copy(busy = Busy("Listening for speech", 0f))
        viewModelScope.launch {
            val mask = withContext(Dispatchers.Default) {
                // The voice track's level, 20 ms frames over the whole session, from its clips' sources.
                val frame = s.sampleRate / 50
                val frames = ((s.length + frame - 1) / frame).toInt()
                val levels = FloatArray(frames) { -144f }
                for (clip in s.clipsOn(voiceTrackId)) {
                    val source = s.source(clip.sourceId) ?: continue
                    val grab = com.layerbit.stave.processing.OfflineTools.read(files, clip, source, s.sampleRate) ?: continue
                    val mono = grab.channels.first()
                    val clipLevels = Silences.levels(mono, grab.sampleRate, 20)
                    val startFrame = (clip.position / frame).toInt()
                    for ((k, l) in clipLevels.withIndex()) { val f = startFrame + k; if (f in levels.indices) levels[f] = maxOf(levels[f], l + clip.gainDb.toFloat()) }
                }
                com.layerbit.stave.engine.project.Ducking.speechMask(levels, Silences.thresholdFor(levels, aboveFloorDb = 12f, capDb = -35f))
            }
            _ui.value = _ui.value.copy(busy = null)
            val frame = s.sampleRate / 50
            val points = com.layerbit.stave.engine.project.Ducking.points(mask, frame, music.volumeDb, depthDb, attackSamples = s.sampleRate / 5L, releaseSamples = s.sampleRate / 2L, holdSamples = s.sampleRate / 3L)
            if (points.isEmpty()) { _ui.value = _ui.value.copy(message = "No speech heard on that track."); return@launch }
            history.recordLate("Duck under speech") { cur -> SessionEdits.updateTrack(cur, musicTrackId) { it.copy(automation = it.automation + ("volume" to com.layerbit.stave.engine.project.ValueTrack(points))) } }
            after()
        }
    }

    // ---- finish: measure and master --------------------------------------------------------

    data class FinishState(
        val targetId: String = "streaming",
        val reading: com.layerbit.stave.engine.dsp.LoudnessReading? = null,
        val report: com.layerbit.stave.engine.dsp.MasteringReport? = null,
        /** The reference's name and profile for EQ match, once one is picked. */
        val referenceName: String? = null,
        val referenceProfile: DoubleArray? = null,
        val matchBands: List<com.layerbit.stave.engine.dsp.EqBand> = emptyList(),
        /** The last master's before-and-after loudness, so A/B can be level-matched. */
        val abGainDb: Double? = null,
        val comparing: Boolean = false
    )
    private val _finish = MutableStateFlow(FinishState(targetId = app.settings.current.loudnessTargetId))
    val finish: StateFlow<FinishState> = _finish.asStateFlow()

    fun setFinishTarget(id: String) { _finish.value = _finish.value.copy(targetId = id) }

    fun measureMix() {
        val s = session
        _ui.value = _ui.value.copy(busy = Busy("Measuring", 0f))
        viewModelScope.launch {
            val chs = mixdown(0, s.length)
            _ui.value = _ui.value.copy(busy = null)
            if (chs == null) return@launch
            val report = withContext(Dispatchers.Default) { com.layerbit.stave.engine.dsp.MasteringReport.of(chs, s.sampleRate) }
            _finish.value = _finish.value.copy(reading = report.loudness, report = report)
        }
    }

    /**
     * A reference track, decoded through the FFmpeg door into this project's media folder for
     * as long as it takes to read its long-term spectrum, then deleted; the profile and the
     * bands that move the mix toward it stay in the Finish state until another is picked.
     */
    fun pickReference(uri: Uri) {
        val s = session
        _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.finish_reading_reference), 0f))
        viewModelScope.launch {
            val result = runCatching {
                val source = app.media.import(uri, files, s.sampleRate) { p -> _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.finish_reading_reference), p)) }
                try {
                    withContext(Dispatchers.Default) {
                        val f = files.sourceFile(source.file)
                        // The middle minute is enough for a long-term spectrum and keeps a ten-minute reference quick.
                        val take = minOf(source.frames, 60L * source.sampleRate).toInt()
                        val from = ((source.frames - take) / 2).coerceAtLeast(0)
                        val inter = RawAudio.read(f, source.channels, from, take)
                        val mono = FloatArray(take) { i -> var m = 0f; for (c in 0 until source.channels) m += inter[i * source.channels + c]; m / source.channels }
                        source.name to com.layerbit.stave.engine.dsp.EqMatch.profile(mono, source.sampleRate)
                    }
                } finally { withContext(Dispatchers.IO) { files.sourceFile(source.file).delete() } }
            }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { (name, profile) -> _finish.value = _finish.value.copy(referenceName = name, referenceProfile = profile, matchBands = emptyList()); matchToReference(1.0) }
                .onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Could not read the reference") }
        }
    }

    /** The bands that move the mix toward the reference by [strength], shown on the card; Apply match renders them. */
    fun matchToReference(strength: Double) {
        val ref = _finish.value.referenceProfile ?: return
        val s = session
        viewModelScope.launch {
            val chs = mixdown(0, s.length) ?: return@launch
            val bands = withContext(Dispatchers.Default) {
                val n = chs[0].size
                val mono = FloatArray(n) { i -> (chs[0][i] + chs.getOrElse(1) { chs[0] }[i]) * 0.5f }
                com.layerbit.stave.engine.dsp.EqMatch.match(com.layerbit.stave.engine.dsp.EqMatch.profile(mono, s.sampleRate), ref, s.sampleRate, strength)
            }
            _finish.value = _finish.value.copy(matchBands = bands)
        }
    }

    fun applyMatch() {
        val bands = _finish.value.matchBands
        if (bands.isEmpty()) return
        select(null)
        applyOffline("Match EQ to ${_finish.value.referenceName ?: "reference"}", com.layerbit.stave.processing.OfflineTools.eq(bands)) { measureMix() }
    }

    fun applyWidth(amount: Double) { select(null); applyOffline("Width", com.layerbit.stave.processing.OfflineTools.width(amount)) { measureMix() } }

    /** Three bands of gentle compression, each amount 0..1 setting how much its band is held. */
    fun applyMultiband(low: Double, mid: Double, high: Double) {
        fun band(a: Double) = if (a < 0.02) CompressorSettings(ratio = 1.0, thresholdDb = -60.0, kneeDb = 0.0) else CompressorSettings(thresholdDb = -22.0 + 8.0 * (1 - a), ratio = 1.5 + 2.5 * a, kneeDb = 9.0, attackMs = 15.0, releaseMs = 180.0, autoMakeup = true)
        select(null)
        applyOffline("Multiband", com.layerbit.stave.processing.OfflineTools.multiband(150.0, 3000.0, listOf(band(low), band(mid), band(high)))) { measureMix() }
    }

    fun applyTransients(attack: Double, sustain: Double) { select(null); applyOffline("Transients", com.layerbit.stave.processing.OfflineTools.transients(attack, sustain)) { measureMix() } }

    /**
     * A/B at matched loudness: on, the graph plays the session Undo would go back to, its master
     * brought up or down by the last master's loudness change, so the ear compares colour and
     * dynamics and not level; off, the current session. Nothing is recorded in the history.
     */
    fun compareBefore(on: Boolean) {
        val before = history.previous
        if (on && before != null && transport.available) {
            val gain = _finish.value.abGainDb ?: 0.0
            transport.setSession(before.copy(masterVolumeDb = before.masterVolumeDb + gain), files.dir)
            _finish.value = _finish.value.copy(comparing = true)
        } else {
            _finish.value = _finish.value.copy(comparing = false)
            pushToGraph()
        }
    }

    /** Tone, glue, a true-peak limiter and the loudness to the target, over the whole session, as one step. */
    fun master(toneDb: Double, glue: Double) {
        val target = LoudnessTarget.byId(_finish.value.targetId)
        val T = com.layerbit.stave.processing.OfflineTools
        val steps = ArrayList<T.Transform>()
        if (kotlin.math.abs(toneDb) > 0.05) steps += T.eq(com.layerbit.stave.engine.dsp.ParametricEq.tilt(toneDb))
        if (glue > 0.02) steps += T.compress(CompressorSettings(thresholdDb = -24.0 + 8.0 * (1 - glue), ratio = 1.5 + 1.5 * glue, kneeDb = 12.0, attackMs = 30.0, releaseMs = 250.0))
        steps += T.normaliseLoudness(target.lufs, target.truePeakDbtp)
        select(null)
        val before = _finish.value.reading?.integratedLufs
        applyOffline("Master to ${target.label}", chain(*steps.toTypedArray())) {
            // The after-loudness lands with the next measurement; the A/B gain is the difference.
            measureMix()
            viewModelScope.launch {
                val s = session
                val chs = mixdown(0, s.length) ?: return@launch
                val after = withContext(Dispatchers.Default) { val m = com.layerbit.stave.engine.dsp.LoudnessMeter(s.sampleRate, 2); m.process(com.layerbit.stave.processing.OfflineTools.interleave(chs)); m.reading().integratedLufs }
                if (before != null && before > -90 && after > -90) _finish.value = _finish.value.copy(abGainDb = after - before, comparing = false)
            }
        }
    }

    // ---- spectral view and edits -------------------------------------------------------------

    /** The picture of the selected track's visible stretch, and where it sits. */
    class SpectrogramImage(val bitmap: android.graphics.Bitmap, val fromSample: Long, val toSample: Long, val lowHz: Double, val highHz: Double)

    data class SpectralState(val shown: Boolean = false, val image: SpectrogramImage? = null, val box: com.layerbit.stave.engine.dsp.SpectralEdit.Box? = null, val computing: Boolean = false)
    private val _spectral = MutableStateFlow(SpectralState())
    val spectral: StateFlow<SpectralState> = _spectral.asStateFlow()
    private var spectralJob: Job? = null

    fun showSpectrogram(on: Boolean) { _spectral.value = _spectral.value.copy(shown = on, box = null, image = if (on) _spectral.value.image else null); if (on) refreshSpectrogram() }
    fun setSpectralBox(box: com.layerbit.stave.engine.dsp.SpectralEdit.Box?) { _spectral.value = _spectral.value.copy(box = box); box?.let { select(it.fromSample until it.toSample) } }

    /** Recomputes the picture for the current window, debounced; called when the window or the track changes. */
    fun refreshSpectrogram() {
        if (!_spectral.value.shown) return
        spectralJob?.cancel()
        spectralJob = viewModelScope.launch {
            delay(120)
            val w = _ui.value.window; val s = session
            val trackId = _ui.value.selectedTrackId ?: s.tracks.firstOrNull()?.id ?: return@launch
            val from = w.firstSample.coerceAtLeast(0); val to = w.lastSample.coerceAtMost(s.length)
            if (to <= from) return@launch
            _spectral.value = _spectral.value.copy(computing = true)
            val image = withContext(Dispatchers.Default) {
                // The track's mono mix over the window, from its clips' sources.
                val n = (to - from).toInt()
                val mono = FloatArray(n)
                for (clip in s.clipsIn(from, to, trackId)) {
                    val source = s.source(clip.sourceId) ?: continue
                    val a = maxOf(from, clip.position); val b = minOf(to, clip.end)
                    val part = clip.copy(position = a, offset = clip.offset + clip.sourceFrames(a - clip.position), length = b - a)
                    val grab = com.layerbit.stave.processing.OfflineTools.read(files, part, source, s.sampleRate) ?: continue
                    val g = com.layerbit.stave.engine.dsp.Gain.dbToLinear(clip.gainDb).toFloat()
                    val chs = grab.channels
                    val len = minOf(chs[0].size, n - (a - from).toInt())
                    for (c in chs) for (i in 0 until len) mono[(a - from).toInt() + i] += c[i] * g / chs.size
                }
                // A hop that gives about a thousand columns whatever the zoom; the window stays 2048.
                val hop = maxOf(com.layerbit.stave.engine.dsp.SpectralEdit.HOP, n / 1024)
                val frames = com.layerbit.stave.engine.dsp.SpectralEdit.spectrogramDb(mono, s.sampleRate, com.layerbit.stave.engine.dsp.SpectralEdit.SIZE, hop)
                if (frames.isEmpty()) return@withContext null
                val lowHz = 40.0; val highHz = s.sampleRate / 2.0
                val rows = 256
                val bmp = android.graphics.Bitmap.createBitmap(frames.size, rows, android.graphics.Bitmap.Config.ARGB_8888)
                val px = IntArray(frames.size * rows)
                val logLo = Math.log(lowHz); val logHi = Math.log(highHz)
                for (y in 0 until rows) {
                    // Row 0 is the top: the highest frequency. Log scale, so a voice's octaves are even.
                    val hz = Math.exp(logHi - (logHi - logLo) * y / (rows - 1))
                    val k = (hz * com.layerbit.stave.engine.dsp.SpectralEdit.SIZE / s.sampleRate).toInt().coerceIn(0, frames[0].size - 1)
                    for (x in frames.indices) px[y * frames.size + x] = heat(frames[x][k])
                }
                bmp.setPixels(px, 0, frames.size, 0, 0, frames.size, rows)
                SpectrogramImage(bmp, from, from + frames.size.toLong() * hop, lowHz, highHz)
            }
            _spectral.value = _spectral.value.copy(image = image, computing = false)
        }
    }

    /** dBFS to a colour: ink below -90, through violet and the hot amber to a pale lime near full scale. */
    private fun heat(db: Float): Int {
        val t = ((db + 90f) / 90f).coerceIn(0f, 1f)
        fun mix(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()
        val stops = intArrayOf(0x070A12, 0x2A1B5E, 0x7A2E8C, 0xE8705F, 0xF0A93D, 0xE2FF8A)
        val pos = t * (stops.size - 1)
        val i = pos.toInt().coerceAtMost(stops.size - 2); val f = pos - i
        val a = stops[i]; val b = stops[i + 1]
        val r = mix((a shr 16) and 255, (b shr 16) and 255, f); val g = mix((a shr 8) and 255, (b shr 8) and 255, f); val bl = mix(a and 255, b and 255, f)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun spectralAttenuate(gainDb: Double) {
        val box = _spectral.value.box ?: return
        val s = session
        _ui.value = _ui.value.copy(selection = box.fromSample until box.toSample)
        applyOffline(if (gainDb <= -59) "Remove from spectrogram" else "Turn down in spectrogram", com.layerbit.stave.processing.OfflineTools.Transform { chs, rate ->
            // The transform sees the selection's samples starting at the box's own start, in the source's rate.
            val scaled = com.layerbit.stave.engine.dsp.SpectralEdit.Box(0, (box.toSample - box.fromSample) * rate / s.sampleRate, box.lowHz, box.highHz)
            chs.map { com.layerbit.stave.engine.dsp.SpectralEdit.attenuate(it, rate, scaled, gainDb) }
        }) { refreshSpectrogram() }
    }

    fun spectralFill() {
        val box = _spectral.value.box ?: return
        val s = session
        // Fill wants a margin either side of the box to read from: the selection is the box grown by half a second.
        val pad = s.sampleRate / 2L
        _ui.value = _ui.value.copy(selection = (box.fromSample - pad).coerceAtLeast(0) until (box.toSample + pad).coerceAtMost(s.length))
        val from = _ui.value.selection!!.first
        applyOffline("Fill from around", com.layerbit.stave.processing.OfflineTools.Transform { chs, rate ->
            val local = com.layerbit.stave.engine.dsp.SpectralEdit.Box((box.fromSample - from) * rate / s.sampleRate, (box.toSample - from) * rate / s.sampleRate, box.lowHz, box.highHz)
            chs.map { com.layerbit.stave.engine.dsp.SpectralEdit.interpolate(it, rate, local) }
        }) { refreshSpectrogram() }
    }

    // ---- carrying a clip -------------------------------------------------------------------

    /** A clip under a long press follows the finger; [done] on release lands it with a snap and closes the step. */
    fun carryClip(clipId: String, position: Long, trackId: String?, done: Boolean) {
        val s = session
        val clip = s.clip(clipId) ?: return
        val target = trackId?.takeIf { id -> s.track(id)?.kind == clip.let { c -> s.track(c.trackId)?.kind } } ?: clip.trackId
        var p = position.coerceAtLeast(0)
        if (done) p = SessionEdits.snap(s, p, (s.sampleRate / 20L), beats = true, excludeClipId = clipId).let { snapped -> if (kotlin.math.abs(snapped - p) <= s.sampleRate / 20L) snapped else p }
        commit(SessionEdits.move(s, clipId, p, target), "Move clip", gesture = "carry-$clipId")
        if (done) endGesture()
    }

    // ---- tune and effects --------------------------------------------------------------------

    fun tune(scale: com.layerbit.stave.engine.dsp.PitchCorrect.Scale, strength: Float, retuneMs: Double) {
        val settings = com.layerbit.stave.engine.dsp.PitchCorrect.Settings(scale, strength, retuneMs, toleranceCents = 5.0)
        applyOffline("Tune", com.layerbit.stave.processing.OfflineTools.Transform { chs, rate ->
            // A voice is one line: tuned on the channels' sum and the result carried to every channel, so a stereo take stays in phase.
            val n = chs[0].size
            val mono = FloatArray(n); for (a in chs) for (i in 0 until n) mono[i] += a[i] / chs.size
            val tuned = com.layerbit.stave.engine.dsp.PitchCorrect.correct(mono, rate, settings)
            chs.map { a -> FloatArray(n) { i -> if (kotlin.math.abs(mono[i]) > 1e-6f) tuned[i] * (a[i] / mono[i]).coerceIn(-2f, 2f) else tuned[i] } }
        })
    }

    fun applyEffect(presetId: String) {
        val preset = com.layerbit.stave.engine.dsp.VocalPresets.byId(presetId) ?: return
        applyOffline(preset.label, com.layerbit.stave.processing.OfflineTools.Transform { chs, rate -> com.layerbit.stave.engine.dsp.VocalPresets.apply(preset, chs, rate) })
    }

    // ---- musician: stems, practice, analysis ------------------------------------------------

    /** A chord as the card shows it; filled by the analysis (engine/music) once it is wired. */
    data class ChordShown(val name: String, val startMs: Long, val endMs: Long, val pitches: List<Int> = emptyList())
    data class Analysis(val bpm: Double, val confidence: Float, val beatsMs: List<Long>, val downbeatOffset: Int, val key: String?, val chords: List<ChordShown>)
    data class MusicState(val separating: Float? = null, val stemsDone: Boolean = false, val analysis: Analysis? = null, val clickFollows: Boolean = false)
    private val _music = MutableStateFlow(MusicState())
    val music: StateFlow<MusicState> = _music.asStateFlow()
    val stemsAvailable: Boolean get() = app.stems.available

    /** The mix pulled into four stems, each a source on its own track; the original tracks are muted so the stems play alone. */
    fun separateStems() {
        val s = session
        if (s.length <= 0) return
        _music.value = _music.value.copy(separating = 0f, stemsDone = false)
        viewModelScope.launch {
            val result = runCatching {
                val chs = mixdown(0, s.length) ?: throw IllegalStateException("Nothing to separate.")
                app.stems.separate(chs, s.sampleRate) { p -> _music.value = _music.value.copy(separating = p) }
            }
            _music.value = _music.value.copy(separating = null)
            result.onSuccess { r ->
                if (r == null) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.music_stems_none)); return@onSuccess }
                val sources = ArrayList<Source>(); val tracks = ArrayList<Track>(); val clips = ArrayList<Clip>()
                withContext(Dispatchers.IO) {
                    for ((name, chs) in r.stems) {
                        val parent = s.sources.firstOrNull() ?: Source("mix", "Mix", "", s.sampleRate, 2, s.length)
                        val source = com.layerbit.stave.processing.OfflineTools.writeSource(files, chs, r.sampleRate, parent, name).copy(kind = Source.Kind.STEM, name = name.replaceFirstChar { it.uppercase() })
                        peaksFor(source)
                        val track = Track(Ids.next("t"), source.name)
                        sources += source; tracks += track
                        clips += SessionEdits.clipFor(source, track.id, 0, s.sampleRate)
                    }
                }
                history.recordLate("Separate stems") { cur ->
                    var next = cur.copy(sources = cur.sources + sources, tracks = cur.tracks.map { t -> if (t.kind == Track.Kind.AUDIO) t.copy(muted = true) else t } + tracks)
                    for (c in clips) next = SessionEdits.add(next, c)
                    next
                }
                after()
                _music.value = _music.value.copy(stemsDone = true)
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Separation failed") }
        }
    }

    /** The selection (or everything) slowed and transposed as a new source on a new track: the original stays. */
    fun practiceCopy(speed: Double, semitones: Double) {
        val s = session
        val range = _ui.value.selection ?: (0L until s.length)
        if (range.last <= range.first) return
        _ui.value = _ui.value.copy(busy = Busy("Making a practice copy", 0f))
        viewModelScope.launch {
            val chs = mixdown(range.first, range.last + 1)
            if (chs == null) { _ui.value = _ui.value.copy(busy = null); return@launch }
            val source = withContext(Dispatchers.Default) {
                val stretched = com.layerbit.stave.processing.OfflineTools.stretch(chs, s.sampleRate, 1.0 / speed, semitones, true)
                val parent = s.sources.firstOrNull() ?: Source("mix", "Mix", "", s.sampleRate, 2, s.length)
                com.layerbit.stave.processing.OfflineTools.writeSource(files, stretched, s.sampleRate, parent, "Practice ${Formats.times(speed.toFloat())} ${if (semitones != 0.0) String.format(java.util.Locale.ROOT, "%+d st", semitones.toInt()) else ""}".trim()).also { withContext(Dispatchers.IO) { peaksFor(it) } }
            }
            _ui.value = _ui.value.copy(busy = null)
            val track = Track(Ids.next("t"), source.name)
            history.recordLate("Practice copy") { cur -> SessionEdits.add(SessionEdits.addTrack(cur.copy(sources = cur.sources + source), track), SessionEdits.clipFor(source, track.id, range.first, cur.sampleRate)) }
            after()
            selectTrack(track.id)
        }
    }

    /** The selection's melody as notes, handed to the Beats tab as a pattern. */
    fun notesToMidi() {
        val s = session
        val range = _ui.value.selection ?: return
        _ui.value = _ui.value.copy(busy = Busy("Hearing the notes", 0f))
        viewModelScope.launch {
            val chs = mixdown(range.first, range.last + 1)
            _ui.value = _ui.value.copy(busy = null)
            if (chs == null) return@launch
            val mono = FloatArray(chs[0].size) { (chs[0][it] + chs[1][it]) * 0.5f }
            val notes = withContext(Dispatchers.Default) { com.layerbit.stave.engine.dsp.MelodyNotes.extract(mono, s.sampleRate) }
            if (notes.isEmpty()) { _ui.value = _ui.value.copy(message = "No clear notes heard in the selection."); return@launch }
            pendingNotes = notes.map { it.copy(start = it.start + range.first, end = it.end + range.first) }
            _ui.value = _ui.value.copy(message = "${notes.size} notes heard - open the Beats tab to see them.", tab = EditorTab.BEATS)
        }
    }

    /** Notes the Beats tab picks up as a pattern (sample positions on the timeline). */
    var pendingNotes: List<com.layerbit.stave.engine.dsp.MelodyNotes.Note>? = null

    fun setClickFollowsBeats(on: Boolean) {
        _music.value = _music.value.copy(clickFollows = on)
        val a = _music.value.analysis ?: return
        if (on && a.bpm > 0) { setTempo(a.bpm); transport.setMetronome(true, a.bpm, 4, 0.5f) } else transport.setMetronome(false, session.tempo.first().bpm, 4, 0.5f)
    }

    fun markBars() {
        val a = _music.value.analysis ?: return
        if (a.beatsMs.isEmpty()) return
        var s = session
        val rate = s.sampleRate
        var bar = 1
        for ((i, ms) in a.beatsMs.withIndex()) if ((i - a.downbeatOffset).mod(4) == 0) { s = SessionEdits.addMarker(s, ms * rate / 1000, "Bar ${bar++}"); if (bar > 400) break }
        commit(s, "Mark bars")
    }

    /**
     * Tempo, beats and bar from the beat tracker, key and chords from the chroma, all off the
     * mix (or the selection, when there is one) on the Default dispatcher; the card fills in
     * when they land. Everything here is engine arithmetic executed on the JVM (BeatsChecks,
     * ChordsChecks, KeyChecks); what a phone adds is only the time it takes.
     */
    fun analyseSong() {
        val s = session
        if (s.length <= 0 || _ui.value.busy != null) return
        val range = _ui.value.selection?.takeIf { it.last - it.first > s.sampleRate } ?: (0L until s.length)
        _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.music_analysing), 0f))
        viewModelScope.launch {
            val chs = mixdown(range.first, range.last + 1)
            if (chs == null) { _ui.value = _ui.value.copy(busy = null); return@launch }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val n = chs[0].size
                    val mono = FloatArray(n)
                    for (a in chs) for (i in 0 until n) mono[i] += a[i] / chs.size
                    val rate = s.sampleRate
                    val offsetMs = range.first * 1000 / rate
                    val beats = com.layerbit.stave.engine.music.BeatDetector.detect(mono, rate)
                    val chroma = com.layerbit.stave.engine.music.Chroma.frames(mono, rate, hopMs = CHORD_HOP_MS)
                    val key = if (chroma.isEmpty()) null else com.layerbit.stave.engine.music.Key.detect(chroma)
                    val chords = com.layerbit.stave.engine.music.Chords.detect(chroma).map { span ->
                        // Voiced from the root in the octave above middle C's bass: the pitches the Beats tab's "Chords to pattern" plays.
                        val pitches = span.chord.pitchClasses().sorted().let { pcs -> pcs.map { pc -> 48 + ((pc - span.root).mod(12)) + span.root } }
                        ChordShown(span.chord.name(), offsetMs + span.startFrame.toLong() * CHORD_HOP_MS, offsetMs + span.endFrame.toLong() * CHORD_HOP_MS, if (span.quality == com.layerbit.stave.engine.music.Quality.NONE) emptyList() else pitches)
                    }
                    Analysis(
                        bpm = beats.bpm.toDouble(),
                        confidence = beats.confidence,
                        beatsMs = beats.beatsMs.map { it + offsetMs },
                        downbeatOffset = beats.downbeatOffset,
                        key = key?.takeIf { it.confidence > 0.2f }?.name(),
                        chords = chords
                    )
                }
            }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { a -> _music.value = _music.value.copy(analysis = a) }
                .onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Analysis failed") }
        }
    }

    /** Half the tempo or twice it: the question a slow track with busy hi-hats leaves open, answered by the person. */
    fun tempoOctave(up: Boolean) {
        val a = _music.value.analysis ?: return
        val map = com.layerbit.stave.engine.music.BeatMap(a.beatsMs, a.bpm.toFloat(), a.confidence, a.downbeatOffset)
        val moved = if (up) map.doubled() else map.halved()
        _music.value = _music.value.copy(analysis = a.copy(bpm = moved.bpm.toDouble(), beatsMs = moved.beatsMs, downbeatOffset = moved.downbeatOffset))
        if (_music.value.clickFollows) setClickFollowsBeats(true)
    }

    /** The bar starts one beat later: for the song whose loudest hit is not its downbeat. */
    fun shiftDownbeat() {
        val a = _music.value.analysis ?: return
        _music.value = _music.value.copy(analysis = a.copy(downbeatOffset = (a.downbeatOffset + 1).mod(4)))
    }

    // ---- read aloud ---------------------------------------------------------------------

    /**
     * A typed line spoken by the phone's own engine (voice/ReadAloud) and landed as a clip at the
     * play-head on the selected track's free lane - the podcast intro you did not want to record,
     * or a line the take missed. The language follows the Voice tab's chip when it is not "auto".
     */
    fun readAloud(text: String) {
        val line = text.trim()
        if (line.isEmpty() || _ui.value.busy != null) return
        val s = session
        val language = _voice.value.language.takeIf { it != "auto" }
        _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.voice_reading), 0f))
        viewModelScope.launch {
            val wav = File(files.takes, "speech-${System.currentTimeMillis()}.wav")
            val result = runCatching {
                val spoken = com.layerbit.stave.voice.ReadAloud(app).speak(line, language, wav)
                withContext(Dispatchers.Default) {
                    val mono = com.layerbit.stave.voice.ReadAloud(app).atRate(spoken, s.sampleRate)
                    val parent = s.sources.firstOrNull() ?: Source("mix", "Mix", "", s.sampleRate, 1, 0)
                    OfflineTools.writeSource(files, listOf(mono), s.sampleRate, parent, line.take(24)).copy(kind = Source.Kind.TAKE, name = line.take(24))
                }
            }
            wav.delete()
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { source ->
                withContext(Dispatchers.IO) { peaksFor(source) }
                val at = _ui.value.window.playhead
                history.recordLate("Read aloud") { cur ->
                    var next = cur.copy(sources = cur.sources + source)
                    val trackId = _ui.value.selectedTrackId?.takeIf { id -> next.track(id)?.kind == Track.Kind.AUDIO } ?: run { val t = Track(Ids.next("t"), "Voice"); next = SessionEdits.addTrack(next, t); t.id }
                    val lane = if (next.clipsIn(at, at + source.frames, trackId).isEmpty()) 0 else (next.clipsOn(trackId).maxOfOrNull { it.lane } ?: 0) + 1
                    next = SessionEdits.add(next, SessionEdits.clipFor(source, trackId, at, next.sampleRate).copy(lane = lane))
                    if (lane > 0) next = SessionEdits.updateTrack(next, trackId) { it.copy(lanes = maxOf(it.lanes, lane + 1)) }
                    next
                }
                after()
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Could not read that aloud") }
        }
    }

    // ---- the looper -------------------------------------------------------------------------

    /** A loop of so many bars from the play-head; each take over it is folded into layers, one lane each. */
    data class LooperState(val bars: Int = 2, val active: Boolean = false, val start: Long = 0, val length: Long = 0, val layers: Int = 0, val recordingLayer: Boolean = false)
    private val _looper = MutableStateFlow(LooperState())
    val looper: StateFlow<LooperState> = _looper.asStateFlow()

    /** Sets the loop at the play-head: [bars] bars at the session's tempo, selected and looped on the transport. */
    fun looperSet(bars: Int) {
        val s = session
        val tempo = s.tempo.first()
        val length = (bars.coerceIn(1, 16) * tempo.beatsPerBar * 60.0 / tempo.bpm * s.sampleRate).toLong().coerceAtLeast(1)
        val start = _ui.value.window.playhead
        _looper.value = LooperState(bars.coerceIn(1, 16), true, start, length, _looper.value.layers)
        select(start until start + length)
        transport.setLoop(start until start + length)
    }

    fun looperOff() { _looper.value = _looper.value.copy(active = false, recordingLayer = false); transport.setLoop(null) }

    /** A finished take over the loop cut into passes, each a layer on its own lane of the Loop track, all starting at the loop's start. */
    private fun landLayers(source: Source, lp: LooperState) {
        var s = session.copy(sources = session.sources + source)
        val trackId = s.tracks.firstOrNull { it.name == "Loop" && it.kind == Track.Kind.AUDIO }?.id ?: run { val t = Track(Ids.next("t"), "Loop"); s = SessionEdits.addTrack(s, t); t.id }
        var lane = (s.clipsOn(trackId).maxOfOrNull { it.lane + 1 } ?: 0)
        var offset = 0L
        var layers = 0
        while (offset < source.frames) {
            val length = minOf(lp.length, source.frames - offset)
            // A last pass under a quarter of the loop is the finger lifting late, not a layer.
            if (length < lp.length / 4 && layers > 0) break
            val clip = SessionEdits.clipFor(source, trackId, lp.start, s.sampleRate).copy(offset = offset, length = length, lane = lane, name = "Layer ${lp.layers + layers + 1}")
            s = SessionEdits.add(s, clip)
            lane++; layers++; offset += lp.length
        }
        s = SessionEdits.updateTrack(s, trackId) { it.copy(lanes = maxOf(it.lanes, lane)) }
        _ui.value = _ui.value.copy(selectedTrackId = trackId)
        _looper.value = lp.copy(layers = lp.layers + layers, recordingLayer = false)
        commit(s, if (layers == 1) "Loop layer" else "$layers loop layers")
        // Keep looping so the next layer goes straight over these.
        transport.setLoop(lp.start until lp.start + lp.length)
        seek(lp.start)
    }

    // ---- karaoke ----------------------------------------------------------------------------

    /** The stem the separator named Vocals, if the song has been pulled apart. */
    fun vocalsTrackId(): String? = session.tracks.firstOrNull { it.kind == Track.Kind.AUDIO && it.name.equals("Vocals", ignoreCase = true) }?.id
    fun muteVocals(on: Boolean) { val id = vocalsTrackId() ?: return; updateTrack(id, if (on) "Mute vocals" else "Unmute vocals") { it.copy(muted = on) } }

    // ---- freeze -----------------------------------------------------------------------------

    /**
     * Freeze: the track alone, through its inserts, rendered to one FREEZE source that replaces
     * its clips, with the inserts switched off - the sound stays, the CPU it cost goes. The fader,
     * pan, sends and automation are left live (the render runs them flat), so the frozen track
     * still mixes as a track. Undo is unfreeze.
     */
    fun freezeTrack(trackId: String) {
        val s = session
        val track = s.track(trackId) ?: return
        if (s.length <= 0 || s.clipsOn(trackId).isEmpty() || !transport.available) return
        _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.studio_freezing), 0f))
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                // The graph plays a copy with this track solo, flat and dry, and the master as a wire.
                val solo = s.copy(
                    tracks = s.tracks.map { t -> if (t.id == trackId) t.copy(solo = true, muted = false, volumeDb = 0.0, pan = 0.0, sends = emptyList(), automation = emptyMap(), outputId = null) else t.copy(solo = false) },
                    masterVolumeDb = 0.0, masterInserts = emptyList()
                )
                transport.setSession(solo, files.dir)
                try {
                    val id = Ids.next("s")
                    val out = files.newSourceFile(id)
                    val n = transport.renderOffline(0, s.length, out)
                    if (n <= 0) { out.delete(); null } else Source(id, "${track.name} (frozen)", "media/${out.name}", s.sampleRate, 2, n, Source.Kind.FREEZE).also { peaksFor(it) }
                } finally { transport.setSession(session, files.dir) }
            }
            _ui.value = _ui.value.copy(busy = null)
            if (source == null) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.studio_freeze_failed)); return@launch }
            history.recordLate("Freeze ${track.name}") { cur ->
                var next = cur.copy(sources = cur.sources + source, clips = cur.clips.filter { it.trackId != trackId })
                next = SessionEdits.add(next, SessionEdits.clipFor(source, trackId, 0, next.sampleRate))
                SessionEdits.updateTrack(next, trackId) { t -> t.copy(inserts = t.inserts.map { it.copy(enabled = false) }, lanes = 1) }
            }
            after()
            _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.studio_frozen, track.name))
        }
    }

    // ---- beats: patterns, the live player, rendering, MIDI --------------------------------

    /**
     * What the Beats tab shows beyond the session's own patterns: which pattern is open, the
     * grid's division, the length a tapped note gets, whether the loop runs and where its
     * play-head is, whether pad hits are being written in, and the keyboard's octave.
     */
    data class BeatsState(
        val patternId: String? = null,
        val division: Int = 16,
        val noteDivision: Int = 16,
        val velocity: Int = 100,
        val octave: Int = 4,
        val looping: Boolean = false,
        val recording: Boolean = false,
        val tick: Long = 0,
        val soundFonts: List<String> = emptyList(),
        /** The piano roll's scale lock: rows outside the scale are hidden. "chromatic" shows every pitch. */
        val scaleKind: String = "chromatic",
        val scaleRoot: Int = 0,
        /** A MIDI controller listened to, and the devices open. */
        val midiIn: Boolean = false,
        val midiDevices: List<String> = emptyList()
    )
    private val _beats = MutableStateFlow(BeatsState(soundFonts = app.soundFonts.list()))
    val beats: StateFlow<BeatsState> = _beats.asStateFlow()
    private var player: com.layerbit.stave.beats.PatternPlayer? = null
    private var playerInstrumentId: String? = null
    private var tickJob: Job? = null

    val currentPattern: com.layerbit.stave.engine.sequencer.Pattern? get() = _beats.value.patternId?.let { session.pattern(it) } ?: session.patterns.firstOrNull()
    val bpm: Double get() = session.tempo.first().bpm
    fun instrumentOf(pattern: com.layerbit.stave.engine.sequencer.Pattern): String = session.instruments[pattern.id] ?: com.layerbit.stave.beats.Instruments.DRUMS
    fun instrumentLabel(id: String): String = com.layerbit.stave.beats.Instruments.label(id, app.soundFonts) { kid -> session.kit(kid)?.name }

    /**
     * A made kit as a DrumKit: each pad's source read whole (summed to mono, resampled to the
     * session's rate, capped at [MAX_PAD_SECONDS]) into a Pad with the pad's settings. Reads disk,
     * so it runs where the instrument is made - off the main thread in a render, and on the
     * player's first use.
     */
    private fun loadKit(kitId: String): com.layerbit.stave.engine.synth.DrumKit? {
        val s = session
        val kit = s.kit(kitId) ?: return null
        val rate = s.sampleRate
        val pads = kit.pads.map { spec ->
            val source = spec.sourceId?.let { s.source(it) }
            val file = source?.let { files.sourceFile(it.file) }
            val sample = if (source == null || file == null || !file.isFile) null else runCatching {
                val frames = minOf(source.frames, (MAX_PAD_SECONDS * source.sampleRate).toLong()).toInt()
                val inter = RawAudio.read(file, source.channels, 0, frames)
                val mono = FloatArray(frames) { i -> var m = 0f; for (c in 0 until source.channels) m += inter[i * source.channels + c]; m / source.channels }
                if (source.sampleRate == rate) mono else com.layerbit.stave.engine.dsp.Resampler(source.sampleRate, rate).convert(mono)
            }.getOrNull()
            com.layerbit.stave.engine.synth.Pad(spec.name, sample, rate, chokeGroup = spec.chokeGroup, gainDb = spec.gainDb, pan = spec.pan, pitchSemitones = spec.pitchSemitones,
                mode = if (spec.oneShot) com.layerbit.stave.engine.synth.PadMode.ONE_SHOT else com.layerbit.stave.engine.synth.PadMode.GATE)
        }
        return com.layerbit.stave.engine.synth.DrumKit(kit.name, pads)
    }

    /** The pad names the grid and the pads card show for the current instrument. */
    fun padLabels(instrumentId: String): List<String> =
        com.layerbit.stave.beats.Instruments.kitId(instrumentId)?.let { kid -> session.kit(kid)?.pads?.map { it.name } }
            ?: com.layerbit.stave.engine.synth.KitSynth.Sound.entries.map { it.label }

    /** The current pattern's kit, when it plays one. */
    val currentKit: com.layerbit.stave.engine.synth.PadKit? get() = currentPattern?.let { com.layerbit.stave.beats.Instruments.kitId(instrumentOf(it)) }?.let { session.kit(it) }

    private fun kitEdited(next: Session, label: String) { commit(next, label); playerInstrumentId = null; refreshInstrument(); refreshLoop() }

    /** The selection cut at its onsets into slices, each a source, laid on the pads of a new kit the current pattern then plays. */
    fun sliceSelectionToPads() {
        val s = session
        val range = _ui.value.selection ?: return
        if (range.last - range.first < s.sampleRate / 20) return
        _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.beats_slicing), 0f))
        viewModelScope.launch {
            val chs = mixdown(range.first, range.last + 1)
            if (chs == null) { _ui.value = _ui.value.copy(busy = null); return@launch }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val n = chs[0].size
                    val mono = FloatArray(n) { i -> var m = 0f; for (a in chs) m += a[i]; m / chs.size }
                    val onsets = com.layerbit.stave.engine.music.Onsets.detect(mono, s.sampleRate).filter { it > 0 && it < n }
                    // No onsets heard (a pad, a drone): sixteen even slices are still a kit worth having.
                    val slices = if (onsets.size >= 2) com.layerbit.stave.engine.synth.Slicer.slice(mono, s.sampleRate, onsets).take(com.layerbit.stave.engine.synth.DrumKit.PADS)
                                 else com.layerbit.stave.engine.synth.Slicer.evenSlices(mono, s.sampleRate, com.layerbit.stave.engine.synth.DrumKit.PADS)
                    val parent = s.sources.firstOrNull() ?: Source("mix", "Mix", "", s.sampleRate, 1, 0)
                    slices.mapIndexed { i, slice -> OfflineTools.writeSource(files, listOf(slice), s.sampleRate, parent, "Slice ${i + 1}").copy(name = "Slice ${i + 1}") }
                }
            }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { sources ->
                withContext(Dispatchers.IO) { sources.forEach { peaksFor(it) } }
                val kit = com.layerbit.stave.engine.synth.PadKit.fromSlices(Ids.next("k"), "Sliced ${session.kits.size + 1}", sources.map { it.id })
                var next = session.copy(sources = session.sources + sources, kits = session.kits + kit)
                val p = currentPattern ?: com.layerbit.stave.engine.sequencer.Pattern(Ids.next("p"), "Pattern ${session.patterns.size + 1}", stepDivision = _beats.value.division).also { next = next.copy(patterns = next.patterns + it); _beats.value = _beats.value.copy(patternId = it.id) }
                next = next.copy(instruments = next.instruments + (p.id to "kit:${kit.id}"))
                kitEdited(next, "Slice to pads")
                _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_sliced, sources.size))
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Could not slice") }
        }
    }

    /** A file from the phone onto one pad of the current kit (a new kit when the pattern plays none). */
    fun loadSampleOnPad(pad: Int, uri: Uri) {
        val s = session
        val name = app.media.displayName(uri)
        _ui.value = _ui.value.copy(busy = Busy("Opening $name", 0f))
        viewModelScope.launch {
            val result = runCatching { app.media.import(uri, files, s.sampleRate) { p -> _ui.value = _ui.value.copy(busy = Busy("Opening $name", p)) } }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { source ->
                withContext(Dispatchers.IO) { peaksFor(source) }
                var next = session.copy(sources = session.sources + source)
                var p = currentPattern
                if (p == null) { p = com.layerbit.stave.engine.sequencer.Pattern(Ids.next("p"), "Pattern ${next.patterns.size + 1}", stepDivision = _beats.value.division); next = next.copy(patterns = next.patterns + p); _beats.value = _beats.value.copy(patternId = p.id) }
                val existing = com.layerbit.stave.beats.Instruments.kitId(next.instruments[p.id] ?: "")?.let { next.kit(it) }
                val kit = existing ?: com.layerbit.stave.engine.synth.PadKit.empty(Ids.next("k"), "Kit ${next.kits.size + 1}")
                val edited = kit.with(pad.coerceIn(0, com.layerbit.stave.engine.synth.DrumKit.PADS - 1), com.layerbit.stave.engine.synth.PadSpec(source.id, source.name.take(14)))
                next = next.copy(kits = if (existing == null) next.kits + edited else next.kits.map { if (it.id == edited.id) edited else it }, instruments = next.instruments + (p.id to "kit:${edited.id}"))
                kitEdited(next, "Sample on pad ${pad + 1}")
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Could not open $name") }
        }
    }

    fun clearPad(pad: Int) {
        val kit = currentKit ?: return
        kitEdited(session.copy(kits = session.kits.map { if (it.id == kit.id) it.with(pad, com.layerbit.stave.engine.synth.PadSpec(name = "Pad ${pad + 1}")) else it }), "Clear pad ${pad + 1}")
    }
    fun soundFontPresets(file: String): List<String> = app.soundFonts.presetNames(file)

    private fun patterns(next: List<com.layerbit.stave.engine.sequencer.Pattern>, label: String, gesture: String? = null, instruments: Map<String, String> = session.instruments) {
        commit(session.copy(patterns = next, instruments = instruments), label, gesture)
        refreshLoop()
    }
    private fun editPattern(label: String, gesture: String? = null, edit: (com.layerbit.stave.engine.sequencer.Pattern) -> com.layerbit.stave.engine.sequencer.Pattern) {
        val p = currentPattern ?: return
        val next = edit(p)
        if (next == p) return
        patterns(session.patterns.map { if (it.id == p.id) next else it }, label, gesture)
    }

    fun selectPattern(id: String) { _beats.value = _beats.value.copy(patternId = id); refreshLoop(); refreshInstrument() }

    fun newPattern(instrument: String = currentPattern?.let { instrumentOf(it) } ?: com.layerbit.stave.beats.Instruments.DRUMS) {
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val taken = session.patterns.map { it.name }.toSet()
        val name = letters.map { "Pattern $it" }.firstOrNull { it !in taken } ?: "Pattern ${session.patterns.size + 1}"
        val p = com.layerbit.stave.engine.sequencer.Pattern(Ids.next("p"), name, stepDivision = _beats.value.division)
        patterns(session.patterns + p, "New pattern", instruments = session.instruments + (p.id to instrument))
        selectPattern(p.id)
    }

    fun deletePattern() {
        val p = currentPattern ?: return
        val s = session
        commit(s.copy(patterns = s.patterns - p, instruments = s.instruments - p.id, arrangement = s.arrangement.copy(placements = s.arrangement.placements.filter { it.patternId != p.id })), "Delete pattern")
        _beats.value = _beats.value.copy(patternId = session.patterns.firstOrNull()?.id)
        refreshLoop(); refreshInstrument()
    }

    fun renamePattern(name: String) { editPattern("Rename pattern") { it.copy(name = name.trim().ifBlank { it.name }) } }

    fun setInstrument(id: String) {
        val p = currentPattern ?: run { newPattern(id); return }
        commit(session.copy(instruments = session.instruments + (p.id to id)), "Instrument")
        refreshInstrument()
    }

    fun setBeatsTempo(bpm: Double) { setTempo(bpm); refreshLoop() }
    fun displayName(uri: Uri): String = app.media.displayName(uri)

    private var midiInput: com.layerbit.stave.beats.MidiInput? = null

    /** A MIDI keyboard plays the pattern's instrument and records into the loop as the pads do; events arrive on the main thread. */
    fun setMidiIn(on: Boolean) {
        if (on) {
            val m = midiInput ?: com.layerbit.stave.beats.MidiInput(app, { p, v -> padDown(p, v) }, { p -> padUp(p) }).also { midiInput = it; it.onDevicesChanged = { d -> _beats.value = _beats.value.copy(midiDevices = d) } }
            if (!m.available) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_midi_in_none)); return }
            m.start()
            _beats.value = _beats.value.copy(midiIn = true, midiDevices = m.connected)
        } else {
            midiInput?.stop()
            _beats.value = _beats.value.copy(midiIn = false, midiDevices = emptyList())
        }
    }

    fun setScale(kind: String, root: Int) { _beats.value = _beats.value.copy(scaleKind = kind, scaleRoot = root.mod(12)) }

    /** Whether a pitch is in the locked scale (every pitch when the lock is chromatic). The scales are the pitch corrector's. */
    fun inScale(pitch: Int): Boolean {
        val b = _beats.value
        val scale = when (b.scaleKind) {
            "major" -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.major(b.scaleRoot)
            "minor" -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.minor(b.scaleRoot)
            "pentatonic" -> com.layerbit.stave.engine.dsp.PitchCorrect.Scale.pentatonic(b.scaleRoot)
            else -> return true
        }
        return scale.contains(pitch.mod(12))
    }

    fun setDivision(d: Int) { _beats.value = _beats.value.copy(division = d, noteDivision = if (_beats.value.noteDivision > d) d else _beats.value.noteDivision); editPattern("Grid") { it.copy(stepDivision = d) } }
    fun setNoteDivision(d: Int) { _beats.value = _beats.value.copy(noteDivision = d) }
    fun setVelocity(v: Int) { _beats.value = _beats.value.copy(velocity = v.coerceIn(1, 127)) }
    fun setOctave(o: Int) { _beats.value = _beats.value.copy(octave = o.coerceIn(1, 7)) }

    /** The pattern's length in bars; notes past the new end are kept in the file and silent until it grows again. */
    fun setBars(bars: Int) { editPattern("Pattern length") { it.copy(lengthTicks = com.layerbit.stave.engine.sequencer.Ticks.PER_BAR * bars.coerceIn(1, 16)) } }
    fun setSwing(amount: Double) { editPattern("Swing", gesture = "swing") { it.copy(swing = amount.coerceIn(0.0, 1.0)) } }

    /** A tap on the grid: off if a note starts in that step, else a note of the chosen length at the chosen velocity. */
    fun toggleStep(pitch: Int, step: Int) {
        val b = _beats.value
        editPattern("Step") { p ->
            val inStep = p.notesInStep(pitch, step, b.division)
            if (inStep.isNotEmpty()) p.copy(notes = p.notes - inStep.toSet())
            else {
                val grid = com.layerbit.stave.engine.sequencer.Ticks.perStep(b.division)
                val length = com.layerbit.stave.engine.sequencer.Ticks.perStep(b.noteDivision).coerceAtMost(p.lengthTicks - step * grid).coerceAtLeast(1)
                p.adding(listOf(com.layerbit.stave.engine.sequencer.Note("", pitch, b.velocity, step * grid, length)))
            }
        }
    }

    fun setStepVelocity(pitch: Int, step: Int, velocity: Int) {
        editPattern("Velocity", gesture = "vel-$pitch-$step") { p ->
            val inStep = p.notesInStep(pitch, step, _beats.value.division).map { it.id }.toSet()
            p.copy(notes = p.notes.map { if (it.id in inStep) it.copy(velocity = velocity.coerceIn(1, 127)) else it })
        }
    }

    fun removeNote(id: String) { editPattern("Remove note") { p -> p.copy(notes = p.notes.filter { it.id != id }) } }
    fun quantiseNotes(strength: Double) { editPattern("Quantise") { p -> p.copy(notes = com.layerbit.stave.engine.sequencer.NoteEdits.quantise(p.notes, _beats.value.division, strength)) } }
    fun humaniseNotes() { editPattern("Humanise") { p -> p.copy(notes = com.layerbit.stave.engine.sequencer.NoteEdits.humanise(p.notes, com.layerbit.stave.engine.sequencer.Ticks.PER_QUARTER / 24, 12, System.nanoTime())) } }
    fun transposeNotes(semitones: Int) { editPattern("Transpose") { p -> p.copy(notes = com.layerbit.stave.engine.sequencer.NoteEdits.transpose(p.notes, semitones)) } }
    fun legatoNotes() { editPattern("Legato") { p -> p.copy(notes = com.layerbit.stave.engine.sequencer.NoteEdits.legato(p.notes, p.lengthTicks)) } }
    fun duplicatePattern() { editPattern("Double the pattern") { p -> if (p.lengthTicks * 2 > com.layerbit.stave.engine.sequencer.Ticks.PER_BAR * 16) p else p.duplicate() } }
    fun clearPattern() { editPattern("Clear pattern") { p -> p.copy(notes = emptyList()) } }

    /** An arpeggio of the held or given pitches over the whole pattern, replacing its notes. */
    fun arpeggiate(pitches: List<Int>, mode: com.layerbit.stave.engine.sequencer.Arpeggiator.Mode, octaves: Int) {
        if (pitches.isEmpty()) return
        editPattern("Arpeggio") { p -> p.copy(notes = com.layerbit.stave.engine.sequencer.Arpeggiator.arpeggiate(pitches, mode, _beats.value.division, octaves, p.lengthTicks, _beats.value.velocity, 0.9)) }
    }

    /** The song's chords (Musician tab) as held chords in a new pattern, a bar each in the file's own time. */
    fun chordsToPattern() {
        val a = _music.value.analysis ?: return
        val chords = a.chords.filter { it.pitches.isNotEmpty() }
        if (chords.isEmpty()) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_no_chords)); return }
        val bpm = if (a.bpm > 0) a.bpm else bpm
        val origin = chords.first().startMs
        val notes = ArrayList<com.layerbit.stave.engine.sequencer.Note>()
        var n = 0
        for (c in chords.take(64)) {
            val start = com.layerbit.stave.engine.sequencer.TicksTime.secondsToTicks((c.startMs - origin) / 1000.0, bpm)
            val end = com.layerbit.stave.engine.sequencer.TicksTime.secondsToTicks((c.endMs - origin) / 1000.0, bpm)
            for (p in c.pitches) notes += com.layerbit.stave.engine.sequencer.Note("c${++n}", p, 90, start, (end - start).coerceAtLeast(com.layerbit.stave.engine.sequencer.Ticks.perStep(16)))
        }
        val reach = notes.maxOf { it.endTick }
        val bars = ((reach + com.layerbit.stave.engine.sequencer.Ticks.PER_BAR - 1) / com.layerbit.stave.engine.sequencer.Ticks.PER_BAR).coerceIn(1, 64)
        val p = com.layerbit.stave.engine.sequencer.Pattern(Ids.next("p"), "Chords", bars * com.layerbit.stave.engine.sequencer.Ticks.PER_BAR, notes)
        patterns(session.patterns + p, "Chords to pattern", instruments = session.instruments + (p.id to "synth:pad"))
        selectPattern(p.id)
    }

    /** The notes heard on the Musician tab become a pattern on a synth, starting from the first note. */
    fun takePendingNotes() {
        val heard = pendingNotes ?: return
        pendingNotes = null
        if (heard.isEmpty()) return
        val rate = session.sampleRate
        val origin = heard.minOf { it.start }
        val notes = heard.mapIndexed { i, n ->
            val start = com.layerbit.stave.engine.sequencer.TicksTime.secondsToTicks((n.start - origin).toDouble() / rate, bpm)
            val end = com.layerbit.stave.engine.sequencer.TicksTime.secondsToTicks((n.end - origin).toDouble() / rate, bpm)
            com.layerbit.stave.engine.sequencer.Note("m${i + 1}", n.midi.coerceIn(0, 127), n.velocity.coerceIn(1, 127), start, (end - start).coerceAtLeast(com.layerbit.stave.engine.sequencer.Ticks.perStep(32)))
        }
        val reach = notes.maxOf { it.endTick }
        val bars = ((reach + com.layerbit.stave.engine.sequencer.Ticks.PER_BAR - 1) / com.layerbit.stave.engine.sequencer.Ticks.PER_BAR).coerceIn(1, 64)
        val p = com.layerbit.stave.engine.sequencer.Pattern(Ids.next("p"), "Melody", bars * com.layerbit.stave.engine.sequencer.Ticks.PER_BAR, notes)
        patterns(session.patterns + p, "Notes to MIDI", instruments = session.instruments + (p.id to "synth:lead"))
        selectPattern(p.id)
    }
    val hasPendingNotes: Boolean get() = !pendingNotes.isNullOrEmpty()

    // -- the live player

    private fun ensurePlayer(): com.layerbit.stave.beats.PatternPlayer {
        val existing = player
        if (existing != null) return existing
        val p = com.layerbit.stave.beats.PatternPlayer(session.sampleRate)
        player = p
        refreshInstrument()
        return p
    }

    private fun refreshInstrument() {
        val pl = player ?: return
        val id = currentPattern?.let { instrumentOf(it) } ?: com.layerbit.stave.beats.Instruments.DRUMS
        if (id == playerInstrumentId && pl.instrument != null) return
        val made = com.layerbit.stave.beats.Instruments.create(id, session.sampleRate, app.soundFonts) { kid -> loadKit(kid) }
        if (made == null && com.layerbit.stave.beats.Instruments.isSoundFont(id)) _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_soundfont_missing))
        pl.instrument = made ?: com.layerbit.stave.beats.Instruments.create(com.layerbit.stave.beats.Instruments.DRUMS, session.sampleRate, null)
        playerInstrumentId = id
    }

    private fun refreshLoop() {
        val pl = player ?: return
        val p = currentPattern
        if (p == null) { pl.setLoop(null); return }
        val tempo = bpm
        pl.setLoop(com.layerbit.stave.beats.PatternPlayer.Loop(p.lengthTicks, tempo) { i -> p.realise(i.toLong()) })
    }

    fun toggleLoop() { if (_beats.value.looping) stopLoop() else startLoop() }

    fun startLoop() {
        if (currentPattern == null) newPattern()
        if (transport.state.value.playing) transport.pause()
        val pl = ensurePlayer()
        refreshInstrument(); refreshLoop()
        pl.startLoop()
        _beats.value = _beats.value.copy(looping = true)
        tickJob?.cancel()
        tickJob = viewModelScope.launch { while (true) { _beats.value = _beats.value.copy(tick = pl.tick); delay(33) } }
    }

    fun stopLoop() {
        tickJob?.cancel(); tickJob = null
        player?.stopLoop()
        _beats.value = _beats.value.copy(looping = false, tick = 0, recording = false)
    }

    fun setRecording(on: Boolean) { _beats.value = _beats.value.copy(recording = on); if (on && !_beats.value.looping) startLoop() }

    /** A pad or key pressed: heard at once, and written at the play-head when recording (snapped to the grid). */
    fun padDown(pitch: Int, velocity: Int = _beats.value.velocity) {
        val pl = ensurePlayer()
        refreshInstrument()
        pl.hit(pitch, velocity)
        val b = _beats.value
        if (b.recording && b.looping) {
            val p = currentPattern ?: return
            val grid = com.layerbit.stave.engine.sequencer.Ticks.perStep(b.division)
            val at = (Math.round(pl.tick.toDouble() / grid) * grid).mod(p.lengthTicks)
            val step = (at / grid).toInt()
            if (p.notesInStep(pitch, step, b.division).isEmpty()) {
                editPattern("Record step", gesture = "record") { it.adding(listOf(com.layerbit.stave.engine.sequencer.Note("", pitch, velocity, at, com.layerbit.stave.engine.sequencer.Ticks.perStep(b.noteDivision).coerceAtMost(p.lengthTicks - at)))) }
            }
        }
    }
    fun padUp(pitch: Int) { player?.release(pitch) }

    // -- rendering to the timeline

    /** The pattern, played [repeats] times through its instrument, as a clip on a new track at the play-head. */
    fun renderPatternToTrack(repeats: Int = 1) {
        val p = currentPattern ?: return
        val notes = com.layerbit.stave.engine.sequencer.Arrangement(listOf(com.layerbit.stave.engine.sequencer.Placement(p.id, 0, repeats.coerceIn(1, 64)))).flatten(listOf(p), 0L)
        renderNotes(notes, p.lengthTicks * repeats, instrumentOf(p), p.name)
    }

    /** The whole song (the arrangement) rendered pattern by pattern onto one new track each. */
    fun renderSongToTrack() {
        val s = session
        if (s.arrangement.placements.isEmpty()) return
        val byInstrument = s.arrangement.placements.groupBy { pl -> s.pattern(pl.patternId)?.let { instrumentOf(it) } ?: com.layerbit.stave.beats.Instruments.DRUMS }
        val length = s.arrangement.lengthTicks(s.patterns.associateBy { it.id })
        for ((instrument, placements) in byInstrument) {
            val notes = com.layerbit.stave.engine.sequencer.Arrangement(placements).flatten(s.patterns, 0L)
            renderNotes(notes, length, instrument, "Song · ${instrumentLabel(instrument)}")
        }
    }

    private fun renderNotes(notes: List<com.layerbit.stave.engine.sequencer.Note>, lengthTicks: Long, instrumentId: String, label: String) {
        val s = session
        val rate = s.sampleRate
        val tempo = bpm
        val at = _ui.value.window.playhead
        _ui.value = _ui.value.copy(busy = Busy(app.getString(com.layerbit.stave.R.string.beats_rendering), 0f))
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val instrument = com.layerbit.stave.beats.Instruments.create(instrumentId, rate, app.soundFonts) { kid -> loadKit(kid) } ?: com.layerbit.stave.beats.Instruments.create(com.layerbit.stave.beats.Instruments.DRUMS, rate, null)!!
                    try {
                        val stereo = com.layerbit.stave.engine.sequencer.PatternRenderer.render(notes, lengthTicks, tempo, rate, instrument)
                        val chs = OfflineTools.deinterleave(stereo, 2)
                        val parent = s.sources.firstOrNull() ?: Source("mix", "Mix", "", rate, 2, s.length)
                        OfflineTools.writeSource(files, chs, rate, parent, label).copy(kind = Source.Kind.RENDER, name = label)
                    } finally { (instrument as? AutoCloseable)?.close() }
                }
            }
            _ui.value = _ui.value.copy(busy = null)
            result.onSuccess { source ->
                withContext(Dispatchers.IO) { peaksFor(source) }
                val track = Track(Ids.next("t"), label, Track.Kind.MIDI)
                history.recordLate("Render $label") { cur -> SessionEdits.add(SessionEdits.addTrack(cur.copy(sources = cur.sources + source), track), SessionEdits.clipFor(source, track.id, at, cur.sampleRate)) }
                after()
                selectTrack(track.id)
                _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_rendered, label))
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Render failed") }
        }
    }

    // -- the song

    fun addToSong(repeats: Int = 1) {
        val p = currentPattern ?: return
        val s = session
        val end = s.arrangement.lengthTicks(s.patterns.associateBy { it.id })
        commit(s.copy(arrangement = s.arrangement.copy(placements = s.arrangement.placements + com.layerbit.stave.engine.sequencer.Placement(p.id, end, repeats.coerceIn(1, 64)))), "Add to song")
    }
    fun removeFromSong(index: Int) {
        val s = session
        val kept = s.arrangement.placements.filterIndexed { i, _ -> i != index }
        // Close the gap: the placements that followed move up by what was taken out.
        val removed = s.arrangement.placements.getOrNull(index) ?: return
        val length = s.pattern(removed.patternId)?.lengthTicks?.times(removed.repeats) ?: 0L
        val moved = kept.map { if (it.startTick > removed.startTick) it.copy(startTick = (it.startTick - length).coerceAtLeast(0)) else it }
        commit(s.copy(arrangement = s.arrangement.copy(placements = moved)), "Remove from song")
    }
    fun setSongRepeats(index: Int, repeats: Int) {
        val s = session
        val old = s.arrangement.placements.getOrNull(index) ?: return
        val length = s.pattern(old.patternId)?.lengthTicks ?: return
        val delta = (repeats.coerceIn(1, 64) - old.repeats) * length
        val next = s.arrangement.placements.mapIndexed { i, pl -> when { i == index -> pl.copy(repeats = repeats.coerceIn(1, 64)); pl.startTick > old.startTick -> pl.copy(startTick = (pl.startTick + delta).coerceAtLeast(0)); else -> pl } }
        commit(s.copy(arrangement = s.arrangement.copy(placements = next)), "Song repeats")
    }

    // -- MIDI files and SoundFonts

    /** A Standard MIDI File's tracks as patterns (the file's tempo becomes the session's when it has one). Returns how many patterns landed. */
    fun importMidi(bytes: ByteArray): Int {
        val file = runCatching { com.layerbit.stave.engine.sequencer.MidiFile.read(bytes) }.getOrElse { e -> _ui.value = _ui.value.copy(message = e.message ?: "Not a MIDI file"); return 0 }
        val made = file.toPatterns().map { it.copy(id = Ids.next("p")) }
        if (made.isEmpty()) { _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_midi_empty)); return 0 }
        val s = session
        val tempo = file.tempoBpm.takeIf { it > 20 && it < 300 }
        val instruments = made.associate { p -> p.id to (if (p.notes.all { it.channel == 9 }) com.layerbit.stave.beats.Instruments.DRUMS else "synth:keys") }
        commit(s.copy(patterns = s.patterns + made, instruments = s.instruments + instruments, tempo = if (tempo != null) listOf(com.layerbit.stave.engine.project.TempoEntry(0, tempo, s.tempo.first().beatsPerBar)) else s.tempo), "Import MIDI")
        selectPattern(made.first().id)
        return made.size
    }

    /** The current pattern as a one-track MIDI file at the session's tempo, or the whole song when [song]. */
    fun exportMidiBytes(song: Boolean): ByteArray? {
        val s = session
        return if (song) {
            if (s.arrangement.placements.isEmpty()) return null
            val notes = s.arrangement.flatten(s.patterns, 0L)
            com.layerbit.stave.engine.sequencer.Pattern("song", s.name.ifBlank { "Song" }, s.arrangement.lengthTicks(s.patterns.associateBy { it.id }), notes).toMidiFile(bpm).write()
        } else currentPattern?.toMidiFile(bpm)?.write()
    }

    fun importSoundFont(name: String, copy: (java.io.File) -> Unit) {
        viewModelScope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { app.soundFonts.import(name, copy) } }
            saved.onSuccess { file ->
                val presets = app.soundFonts.presetNames(file)
                _beats.value = _beats.value.copy(soundFonts = app.soundFonts.list())
                if (presets.isEmpty()) { app.soundFonts.remove(file); _beats.value = _beats.value.copy(soundFonts = app.soundFonts.list()); _ui.value = _ui.value.copy(message = app.getString(com.layerbit.stave.R.string.beats_soundfont_bad)) }
                else setInstrument(com.layerbit.stave.beats.Instruments.soundFontId(file, 0))
            }.onFailure { e -> _ui.value = _ui.value.copy(message = e.message ?: "Could not add the SoundFont") }
        }
    }

    fun removeSoundFont(name: String) { app.soundFonts.remove(name); _beats.value = _beats.value.copy(soundFonts = app.soundFonts.list()) }

    private companion object { const val CHORD_HOP_MS = 100; const val MAX_PAD_SECONDS = 20.0 }
}
