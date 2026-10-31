/** The music-analysis suites (engine/music): beats, onsets, chroma, chords, key, tempo. */
object MusicSuites {
    val all: List<Suite> = listOf(BeatsChecks, OnsetsChecks, ChromaChecks, ChordsChecks, KeyChecks, TempoChecks)
}
