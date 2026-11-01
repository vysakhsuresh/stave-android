package com.layerbit.stave.beats

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * A MIDI keyboard or pad controller over USB or Bluetooth, through the platform's MidiManager:
 * every device with an output port is opened and its note-ons and note-offs handed to the
 * Beats tab as pad presses, so a controller plays the instrument and records into the loop
 * exactly as the on-screen pads do. Running status and the real-time bytes interleaved in a
 * stream are handled, because cheap controllers send both.
 *
 * Output to a device and MIDI clock are not built: nothing in the app yet needs to drive a
 * synth outside it.
 */
class MidiInput(context: Context, private val onNoteOn: (pitch: Int, velocity: Int) -> Unit, private val onNoteOff: (pitch: Int) -> Unit) {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())
    private val open = HashMap<String, Pair<MidiDevice, MidiOutputPort>>()
    private var listening = false
    var onDevicesChanged: ((List<String>) -> Unit)? = null

    val available: Boolean get() = manager != null
    val connected: List<String> get() = open.keys.toList()

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) { if (listening) openDevice(device) }
        override fun onDeviceRemoved(device: MidiDeviceInfo) { closeDevice(nameOf(device)) }
    }

    fun start() {
        val m = manager ?: return
        if (listening) return
        listening = true
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(callback, handler)
        @Suppress("DEPRECATION")
        for (d in m.devices) openDevice(d)
    }

    fun stop() {
        listening = false
        manager?.unregisterDeviceCallback(callback)
        for (name in open.keys.toList()) closeDevice(name)
    }

    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"

    private fun openDevice(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0) return
        val name = nameOf(info)
        if (open.containsKey(name)) return
        manager?.openDevice(info, { device ->
            if (device == null) return@openDevice
            val port = device.openOutputPort(0)
            if (port == null) { device.close(); return@openDevice }
            port.connect(Receiver())
            open[name] = device to port
            onDevicesChanged?.invoke(connected)
        }, handler)
    }

    private fun closeDevice(name: String) {
        val (device, port) = open.remove(name) ?: return
        runCatching { port.close() }; runCatching { device.close() }
        onDevicesChanged?.invoke(connected)
    }

    /** Parses the stream: channel voice messages with running status, real-time bytes ignored wherever they land. */
    private inner class Receiver : MidiReceiver() {
        private var status = 0
        private var data = IntArray(2)
        private var needed = 0
        private var have = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) {
                val b = msg[i].toInt() and 0xFF
                when {
                    b >= 0xF8 -> {} // real-time: clock, start, stop, active sensing - never part of a message
                    b >= 0xF0 -> { status = 0; needed = 0; have = 0 } // system common or sysex: not ours
                    b >= 0x80 -> { status = b; have = 0; needed = if (b and 0xF0 == 0xC0 || b and 0xF0 == 0xD0) 1 else 2 }
                    status != 0 -> {
                        data[have++] = b
                        if (have == needed) { dispatch(); have = 0 } // running status: the next data bytes reuse the status
                    }
                }
            }
        }

        private fun dispatch() {
            when (status and 0xF0) {
                0x90 -> if (data[1] == 0) onNoteOff(data[0]) else onNoteOn(data[0], data[1])
                0x80 -> onNoteOff(data[0])
                0xB0 -> if (data[0] == 123 || data[0] == 120) for (p in 0..127) onNoteOff(p) // all notes off, all sound off
            }
        }
    }
}
