/**
 * Every suite, in the order they run. RunnerChecks holds this list honest against the files.
 * The lists are split by area so work on one area adds to its own file and never collides with
 * another's: MusicSuites (analysis: beats, chroma, chords, key), SequencerSuites (patterns,
 * MIDI, synth), VoiceSuites (transcript, segmenter).
 */
object Registry {
    val all: List<Suite> = listOf(
        FftChecks,
        BiquadChecks,
        DynamicsChecks,
        LoudnessChecks,
        ResamplerChecks,
        FadesChecks,
        RepairChecks,
        DenoiseChecks,
        TimePitchChecks,
        PitchChecks,
        PeaksChecks,
        TimecodeChecks,
        JsonChecks,
        ProjectChecks,
        SessionEditsChecks,
        ExportChecks,
        ExportChainChecks,
        SpectralEditChecks,
        EffectsChecks,
        PitchCorrectChecks,
        MelodyChecks,
        MasteringChecks,
        LiveTuneChecks,
        AutomationChecks,
        CompingChecks,
        ResourceChecks
    ) + MusicSuites.all + SequencerSuites.all + VoiceSuites.all + listOf(RunnerChecks)
}
