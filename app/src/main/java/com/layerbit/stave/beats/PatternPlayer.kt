package com.layerbit.stave.beats

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.layerbit.stave.engine.sequencer.Instrument
import com.layerbit.stave.engine.sequencer.Note
import com.layerbit.stave.engine.sequencer.PatternRenderer
import com.layerbit.stave.engine.sequencer.TicksTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays a pattern round and round, and the pads live, through an [Instrument] on its own
 * AudioTrack - not through the graph, because the graph plays sources from files and a pattern
 * being edited changes under the finger; a note toggled is heard on the next pass of the
 * play-head with nothing rendered to disk. "Render to track" goes through [PatternRenderer]
 * with the same arithmetic, so what lands in the project is what was heard here.
 *
 * One thread renders [BLOCK]-frame blocks: the live hits queued from the UI thread are applied
 * at the block's start (within 5 ms at 48 kHz, which a finger does not feel), the scheduled
 * notes at their own sample as the renderer places them, and the loop's notes are asked for
 * again at every wrap so a probability rolls afresh each bar. The current tick is published
 * for the grid's play-head.
 */
class PatternPlayer(private val sampleRate: Int) : AutoCloseable {

    class Loop(val lengthTicks: Long, val bpm: Double, val notes: (loopIndex: Int) -> List<Note>)

    private val loop = AtomicReference<Loop?>(null)
    private val instrumentRef = AtomicReference<Instrument?>(null)
    private val live = ConcurrentLinkedQueue<Pair<Int, Int>>() // pitch, velocity (0 = off)
    private val running = AtomicBoolean(false)
    private val looping = AtomicBoolean(false)
    private val tickNow = AtomicLong(0)
    private var thread: Thread? = null

    val isLooping: Boolean get() = looping.get()
    /** The loop position in ticks, for the play-head; 0 when stopped. */
    val tick: Long get() = tickNow.get()

    var instrument: Instrument?
        get() = instrumentRef.get()
        set(value) { val old = instrumentRef.getAndSet(value); old?.allNotesOff(); if (old is AutoCloseable && old !== value) runCatching { old.close() } }

    /** Replaces the loop; the position wraps into the new length so a bar added mid-play does not jump. */
    fun setLoop(l: Loop?) { loop.set(l); if (l == null) looping.set(false) }

    fun startLoop() { looping.set(true); ensureThread() }
    fun stopLoop() { looping.set(false); tickNow.set(0); instrumentRef.get()?.let { live.add(ALL_OFF) } }

    /** A pad or key pressed: sounds within a block. */
    fun hit(pitch: Int, velocity: Int) { live.add(pitch to velocity.coerceIn(1, 127)); ensureThread() }
    fun release(pitch: Int) { live.add(pitch to 0) }

    private fun ensureThread() {
        if (running.compareAndSet(false, true)) {
            thread = Thread({ run() }, "stave-pattern").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        }
    }

    private fun run() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(BLOCK * 2 * 4 * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val block = FloatArray(BLOCK * 2)
        var frameInLoop = 0L
        var loopIndex = 0
        var events: List<PatternRenderer.Event> = emptyList()
        var eventAt = 0
        var current: Loop? = null
        var idleBlocks = 0
        try {
            track.play()
            while (running.get()) {
                val inst = instrumentRef.get()
                // Live hits first: a finger does not wait for the grid.
                while (true) {
                    val (pitch, velocity) = live.poll() ?: break
                    if (inst == null) continue
                    when {
                        pitch == ALL_OFF.first -> inst.allNotesOff()
                        velocity == 0 -> inst.noteOff(pitch)
                        else -> inst.noteOn(pitch, velocity)
                    }
                }
                val l = loop.get()
                if (looping.get() && l != null && inst != null) {
                    if (l !== current) {
                        // A new loop: keep the phase within the new length, roll its notes.
                        val lengthFrames = TicksTime.ticksToSamples(l.lengthTicks, l.bpm, sampleRate = sampleRate).coerceAtLeast(1)
                        frameInLoop %= lengthFrames
                        events = PatternRenderer.events(l.notes(loopIndex), l.bpm, sampleRate)
                        eventAt = events.indexOfFirst { it.frame >= frameInLoop }.let { if (it < 0) events.size else it }
                        current = l
                    }
                    val lengthFrames = TicksTime.ticksToSamples(l.lengthTicks, l.bpm, sampleRate = sampleRate).coerceAtLeast(1)
                    var done = 0
                    while (done < BLOCK) {
                        val toWrap = lengthFrames - frameInLoop
                        val nextEvent = if (eventAt < events.size) events[eventAt].frame - frameInLoop else Long.MAX_VALUE
                        val run = minOf((BLOCK - done).toLong(), toWrap, if (nextEvent <= 0) Long.MAX_VALUE else nextEvent).toInt()
                        if (run > 0) {
                            val part = FloatArray(run * 2)
                            inst.render(part, run)
                            System.arraycopy(part, 0, block, done * 2, run * 2)
                            done += run
                            frameInLoop += run
                        }
                        while (eventAt < events.size && events[eventAt].frame <= frameInLoop) {
                            val ev = events[eventAt++]
                            if (ev.on) inst.noteOn(ev.pitch, ev.velocity) else inst.noteOff(ev.pitch)
                        }
                        if (frameInLoop >= lengthFrames) {
                            frameInLoop = 0
                            loopIndex++
                            events = PatternRenderer.events(l.notes(loopIndex), l.bpm, sampleRate)
                            eventAt = 0
                        }
                    }
                    tickNow.set(TicksTime.secondsToTicks(frameInLoop.toDouble() / sampleRate, l.bpm))
                    idleBlocks = 0
                } else {
                    current = null
                    frameInLoop = 0
                    if (inst != null) inst.render(block, BLOCK) else block.fill(0f)
                    if (inst == null || !inst.isSounding) idleBlocks++ else idleBlocks = 0
                    // Nothing to play for a second: let the thread go rather than feed silence for ever.
                    if (idleBlocks * BLOCK > sampleRate && live.isEmpty()) break
                }
                track.write(block, 0, BLOCK * 2, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: Throwable) {
            // A failed AudioTrack (another app holding the output) ends the pass; the next hit starts another.
        } finally {
            runCatching { track.stop() }; track.release()
            running.set(false)
            tickNow.set(0)
            // A hit that arrived while we were shutting down starts the thread again.
            if (live.isNotEmpty() || looping.get()) ensureThread()
        }
    }

    override fun close() {
        looping.set(false)
        running.set(false)
        thread?.join(500)
        instrument = null
    }

    companion object {
        const val BLOCK = 256
        private val ALL_OFF = Int.MIN_VALUE to 0
    }
}
