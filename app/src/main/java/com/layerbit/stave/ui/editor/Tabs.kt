package com.layerbit.stave.ui.editor

import com.layerbit.stave.R

/** The six pillars as the editor's tabs; the id is a handle in routes and prefs. */
enum class EditorTab(val id: String, val titleRes: Int, val iconRes: Int) {
    EDIT("edit", R.string.home_tool_edit, R.drawable.ic_wave),
    VOICE("voice", R.string.home_tool_voice, R.drawable.ic_mic),
    STUDIO("studio", R.string.home_tool_studio, R.drawable.ic_studio),
    MUSIC("music", R.string.home_tool_music, R.drawable.ic_stems),
    BEATS("beats", R.string.home_tool_beats, R.drawable.ic_beats),
    FINISH("finish", R.string.home_tool_finish, R.drawable.ic_finish);

    companion object {
        fun byId(id: String?): EditorTab = entries.firstOrNull { it.id == id } ?: EDIT
    }
}

/** The sheets of the Edit tab's rail. */
enum class EditSheet(val id: String, val titleRes: Int) {
    SELECT("select", R.string.sheet_select),
    GAIN("gain", R.string.sheet_gain),
    FADES("fades", R.string.sheet_fades),
    REPAIR("repair", R.string.sheet_repair),
    SPECTRAL("spectral", R.string.sheet_spectral),
    EQ("eq", R.string.sheet_eq),
    DYNAMICS("dynamics", R.string.sheet_dynamics),
    TIME("time", R.string.sheet_time),
    EFFECTS("effects", R.string.sheet_effects),
    MARKERS("markers", R.string.sheet_markers),
    EXPORT("export", R.string.sheet_export)
}
