package com.layerbit.stave.engine.project

/**
 * What a sound is called in the app, from the file it came in as. Downloaded songs arrive as
 * "Ramayana_ Ravana Tandavam (Song) _ Ranbir_ Yash ...(MP3_320K).mp3": the site turned a colon,
 * commas and bars into underscores and put its bitrate on the end. The file's own name is kept
 * as the source's origin; this is only the label.
 */
object Names {
    // "(MP3_320K)", "[128kbps]", "(320 kbps)", "(FLAC)": a bracket holding only a format or a rate.
    private val qualityTag = Regex("""\s*[(\[]\s*(?:(?:mp3|m4a|aac|flac|wav|ogg|opus|hq|hd)[\s_-]*)?(?:\d{2,4}\s*k(?:bps)?)?\s*[)\]]""", RegexOption.IGNORE_CASE)

    fun fromFile(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0 && fileName.length - dot in 2..5) fileName.substring(0, dot) else fileName
        val tidy = base
            .replace(qualityTag) { m -> if (m.value.any { it.isLetterOrDigit() }) "" else m.value }
            .replace(Regex("""\s+_\s+"""), " - ")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(\s-)+\s*$"""), "")
            .trim()
        return tidy.ifBlank { base.ifBlank { fileName } }
    }

    /**
     * Projects imported before [fromFile] existed carry the raw file name. A name still exactly
     * that is tidied; anything the person changed is theirs and left alone. Returns the same
     * object when nothing changes.
     */
    fun tidied(session: Session): Session {
        val renames = HashMap<String, String>()
        for (s in session.sources) {
            val origin = s.origin ?: continue
            val raw = origin.substringBeforeLast('.').ifBlank { origin }
            val tidy = fromFile(origin)
            if (s.name == raw && tidy != raw) renames[raw] = tidy
        }
        if (renames.isEmpty()) return session
        return session.copy(
            name = renames[session.name] ?: session.name,
            sources = session.sources.map { s -> renames[s.name]?.let { s.copy(name = it) } ?: s },
            clips = session.clips.map { c -> renames[c.name]?.let { c.copy(name = it) } ?: c }
        )
    }
}
