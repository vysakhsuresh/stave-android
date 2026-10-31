/** Every suite, in the order they run. RunnerChecks holds this list honest against the files. */
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
        RunnerChecks
    )
}
