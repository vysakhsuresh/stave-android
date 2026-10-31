package com.layerbit.stave.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the person has told the app to remember about itself. Nothing about a sound is stored here. */
data class Settings(
    val theme: ThemeChoice = ThemeChoice.DARK,
    val lively: Boolean = true,
    val haptics: Boolean = true,
    /** 0 means the phone's own rate. */
    val sampleRate: Int = 0,
    /** 32 (float) or 24. */
    val recordBits: Int = 32,
    val lowLatency: Boolean = true,
    val loudnessTargetId: String = "podcast",
    val keepScreenOn: Boolean = true,
    val introSeen: Boolean = false,
    val recentTools: List<String> = emptyList()
)

/**
 * SharedPreferences, because the theme decides every colour on screen and must be in memory
 * before the first frame.
 */
class StaveSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("stave.settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        prefs.edit()
            .putString(KEY_THEME, s.theme.name)
            .putBoolean(KEY_LIVELY, s.lively)
            .putBoolean(KEY_HAPTICS, s.haptics)
            .putInt(KEY_RATE, s.sampleRate)
            .putInt(KEY_BITS, s.recordBits)
            .putBoolean(KEY_LOW_LATENCY, s.lowLatency)
            .putString(KEY_TARGET, s.loudnessTargetId)
            .putBoolean(KEY_KEEP_SCREEN, s.keepScreenOn)
            .putBoolean(KEY_INTRO, s.introSeen)
            .putString(KEY_RECENT, s.recentTools.joinToString(","))
            .apply()
        _state.value = s
    }

    fun rememberTool(tool: String) = update { s -> s.copy(recentTools = (listOf(tool) + s.recentTools.filter { it != tool }).take(6)) }

    private fun read(): Settings = Settings(
        theme = prefs.getString(KEY_THEME, null)?.let { n -> ThemeChoice.entries.firstOrNull { it.name == n } } ?: ThemeChoice.DARK,
        lively = prefs.getBoolean(KEY_LIVELY, true),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        sampleRate = prefs.getInt(KEY_RATE, 0),
        recordBits = prefs.getInt(KEY_BITS, 32),
        lowLatency = prefs.getBoolean(KEY_LOW_LATENCY, true),
        loudnessTargetId = prefs.getString(KEY_TARGET, null) ?: "podcast",
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, true),
        introSeen = prefs.getBoolean(KEY_INTRO, false),
        recentTools = prefs.getString(KEY_RECENT, null).orEmpty().split(",").filter { it.matches(Regex("[A-Z_]+")) }
    )

    private companion object {
        const val KEY_THEME = "theme"; const val KEY_LIVELY = "lively"; const val KEY_HAPTICS = "haptics"
        const val KEY_RATE = "sampleRate"; const val KEY_BITS = "recordBits"; const val KEY_LOW_LATENCY = "lowLatency"
        const val KEY_TARGET = "loudnessTarget"; const val KEY_KEEP_SCREEN = "keepScreenOn"; const val KEY_INTRO = "introSeen"
        const val KEY_RECENT = "recentTools"
    }
}
