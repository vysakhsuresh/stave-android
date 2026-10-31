import com.layerbit.stave.engine.export.AudioFormat
import com.layerbit.stave.engine.export.ExportSettings

object ExportChecks : Suite {
    override val name = "export"

    override fun run() {
        section("the format table is consistent")
        check(AudioFormat.all.map { it.id }.toSet().size == AudioFormat.all.size) { "ids unique" }
        for (f in AudioFormat.all) {
            check(f.lossless == f.bitrates.isEmpty()) { "${f.id}: lossless formats have no bitrates and lossy ones do" }
            check(!f.lossless || f.depths.isNotEmpty()) { "${f.id}: lossless formats offer depths" }
            check(f.bitrates.isEmpty() || f.defaultBitrate in f.bitrates) { "${f.id}: default bitrate is one of its bitrates" }
            check(f.extension.isNotEmpty() && f.mime.contains('/') && f.muxer.isNotEmpty()) { "${f.id}: names" }
        }
        check(AudioFormat.byId("nonsense") === AudioFormat.MP3) { "unknown falls back to MP3" }

        section("size estimates")
        val mp3 = AudioFormat.MP3.estimateBytes(60.0, 192, 44_100, 2, 16)
        near(mp3.toDouble(), 192_000.0 / 8 * 60 + 4096, 1.0, "a minute of 192 kbps")
        val wav = AudioFormat.WAV.estimateBytes(60.0, 0, 48_000, 2, 24)
        near(wav.toDouble(), 48_000.0 * 2 * 3 * 60 + 4096, 1.0, "a minute of 24-bit stereo WAV")
        val flac = AudioFormat.FLAC.estimateBytes(60.0, 0, 48_000, 2, 16)
        check(flac < AudioFormat.WAV.estimateBytes(60.0, 0, 48_000, 2, 16)) { "FLAC estimates smaller than WAV" }

        section("settings resolve encoder, rate and channels")
        val wav24 = ExportSettings(formatId = "wav", depth = 24)
        check(wav24.encoderName() == "pcm_s24le") { wav24.encoderName() ?: "null" }
        check(ExportSettings(formatId = "aiff", depth = 32).encoderName() == "pcm_f32be") { "aiff float" }
        check(ExportSettings(formatId = "mp3").encoderName() == "libmp3lame") { "mp3 encoder" }
        check(ExportSettings(formatId = "opus").outputRate(44_100) == 48_000) { "Opus only takes 48 kHz" }
        check(ExportSettings(formatId = "mp3").outputRate(96_000) == 48_000) { "MP3 falls to its nearest rate" }
        check(ExportSettings(formatId = "flac").outputRate(96_000) == 96_000) { "FLAC takes any rate" }
        check(ExportSettings(channels = 1).outputChannels(2) == 1 && ExportSettings().outputChannels(2) == 2) { "channels" }

        section("file names keep every script and lose only what a file system cannot hold")
        check(ExportSettings(title = "മലയാളം പാട്ട്").fileName("x") == "മലയാളം പാട്ട്.mp3") { "Malayalam name kept" }
        check(ExportSettings(title = "a/b:c*d?e\"f<g>h|i").fileName("x") == "a_b_c_d_e_f_g_h_i.mp3") { ExportSettings(title = "a/b:c*d?e\"f<g>h|i").fileName("x") }
        check(ExportSettings(formatId = "flac").fileName("Take 1") == "Take 1.flac") { "fallback name" }
        check(ExportSettings(title = "   ").fileName("").endsWith("stave.mp3")) { "blank everything" }
        check(ExportSettings(title = "x".repeat(200)).fileName("").length <= 84) { "capped" }
    }
}
