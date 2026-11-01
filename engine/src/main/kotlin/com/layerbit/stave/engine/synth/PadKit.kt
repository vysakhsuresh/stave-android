package com.layerbit.stave.engine.synth

/**
 * A kit the person made, as the project file keeps it: sixteen pads, each naming the source
 * (a slice of a loop, a sample imported) it plays, with the settings a pad has. The samples
 * themselves are the project's sources; the app turns a PadKit into a [DrumKit] by reading them,
 * so a kit costs the file nothing but sixteen ids.
 */
data class PadSpec(
    val sourceId: String? = null,
    val name: String = "",
    val gainDb: Double = 0.0,
    val pan: Double = 0.0,
    val pitchSemitones: Double = 0.0,
    val chokeGroup: Int = 0,
    val oneShot: Boolean = true
) {
    val isEmpty: Boolean get() = sourceId == null
}

data class PadKit(val id: String, val name: String, val pads: List<PadSpec>) {
    init { require(pads.size == DrumKit.PADS) { "a kit has ${DrumKit.PADS} pads, not ${pads.size}" } }

    fun with(index: Int, pad: PadSpec): PadKit = copy(pads = pads.mapIndexed { i, p -> if (i == index) pad else p })

    /** The first pad with nothing on it, or null when every pad is taken. */
    val firstEmpty: Int? get() = pads.indexOfFirst { it.isEmpty }.takeIf { it >= 0 }

    companion object {
        fun empty(id: String, name: String = "My kit"): PadKit = PadKit(id, name, List(DrumKit.PADS) { PadSpec(name = "Pad ${it + 1}") })

        /** Slices laid onto the pads in order, named Slice 1…; past sixteen they are left off. */
        fun fromSlices(id: String, name: String, sliceSourceIds: List<String>): PadKit =
            PadKit(id, name, List(DrumKit.PADS) { i -> sliceSourceIds.getOrNull(i)?.let { PadSpec(it, "Slice ${i + 1}") } ?: PadSpec(name = "Pad ${i + 1}") })
    }
}
