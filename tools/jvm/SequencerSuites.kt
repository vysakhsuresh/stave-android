/** The sequencer suites (engine/sequencer, engine/synth): patterns, quantise, MIDI files, oscillators, envelopes, drums. */
object SequencerSuites {
    val all: List<Suite> = listOf(PatternChecks, MidiChecks, SynthChecks, DrumsChecks, KitSynthChecks, RenderChecks, WavetableChecks)
}
