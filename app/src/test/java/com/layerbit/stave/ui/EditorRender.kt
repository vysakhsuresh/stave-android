package com.layerbit.stave.ui

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.engine.project.Clip
import com.layerbit.stave.engine.project.Session
import com.layerbit.stave.engine.project.Source
import com.layerbit.stave.engine.project.Track
import com.layerbit.stave.files.ProjectFiles
import com.layerbit.stave.prefs.ThemeChoice
import com.layerbit.stave.ui.editor.EditSheet
import com.layerbit.stave.ui.editor.EditorScreen
import com.layerbit.stave.ui.editor.EditorTab
import com.layerbit.stave.ui.editor.EditorViewModel
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.StaveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The editor drawn on the laptop with a real project: thirty seconds of a song-like signal,
 * every tab and every Edit sheet, in both themes, into app/build/outputs/roborazzi/editor-*.
 * The audio engine is absent here (its library is the phone's), so nothing plays; the screens
 * are the real ones. Run with -Pscreens, as ScreensRender says.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h913dp-xxhdpi")
class EditorRender {
    @get:Rule val compose = createComposeRule()

    private fun project(app: StaveApplication): String {
        val entry = app.projects.create("Night drive", "edit")
        val files = ProjectFiles(app.projects.dir(entry.id))
        val rate = 48_000; val frames = rate * 30
        val media = File(files.dir, "media/s1.f32").apply { parentFile?.mkdirs() }
        val bb = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 7L
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val beat = (t * 2).rem(1.0)
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val noise = ((seed ushr 33).toInt() / 2147483648.0) * 2 - 1
            val swell = 0.35 + 0.3 * sin(2 * PI * t / 12)
            val v = swell * (0.5 * sin(2 * PI * 110 * t) * exp(-beat * 6) + 0.25 * noise * exp(-beat * 18) + 0.15 * sin(2 * PI * 440 * t))
            bb.putFloat(v.toFloat()); bb.putFloat((v * 0.9).toFloat())
        }
        media.writeBytes(bb.array())
        val session = Session(
            name = "Night drive", sampleRate = rate,
            sources = listOf(Source("s1", "Night drive", "media/s1.f32", rate, 2, frames.toLong(), Source.Kind.AUDIO, origin = "Night drive.wav")),
            tracks = listOf(Track("t1", "Track 1")),
            clips = listOf(Clip("c1", "s1", "t1", 0, 0, frames.toLong(), name = "Night drive"))
        )
        files.save(session)
        return entry.id
    }

    private fun waitUntil(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!what() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun run(theme: ThemeChoice, suffix: String = "") {
        val app = ApplicationProvider.getApplicationContext<StaveApplication>()
        val vm = EditorViewModel(app, project(app))
        waitUntil { vm.ui.value.loaded }
        compose.setContent {
            StaveTheme(theme) { Box(Modifier.fillMaxSize().background(StaveColors.Background)) { EditorScreen(vm, onBack = {}, onAddSounds = {}) } }
        }
        val t = theme.name.lowercase() + suffix
        fun shot(name: String) { compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle(); compose.onRoot().captureRoboImage("build/outputs/roborazzi/editor-$name-$t.png") }
        shot("open")
        for (sheet in EditSheet.entries) { compose.runOnIdle { vm.setSheet(sheet) }; shot("sheet-${sheet.id}") }
        compose.runOnIdle { vm.setSheet(null) }
        for (tab in EditorTab.entries.drop(1)) { compose.runOnIdle { vm.setTab(tab) }; shot("tab-${tab.id}") }
    }

    /** A project with something in it: three tracks and a bus, markers and a chapter, a selection, a drum pattern. */
    @Test fun busy() {
        val app = ApplicationProvider.getApplicationContext<StaveApplication>()
        val id = project(app)
        val files = ProjectFiles(app.projects.dir(id))
        val r = 48_000L
        val base = files.load()!!
        files.save(base.copy(
            tracks = listOf(Track("t1", "Vocals"), Track("t2", "Guitar", volumeDb = -4.0, pan = -0.3), Track("t3", "Drums", muted = true), Track("b1", "Reverb bus", kind = Track.Kind.BUS)),
            clips = listOf(Clip("c1", "s1", "t1", 0, 0, 30 * r, name = "Lead take"), Clip("c2", "s1", "t2", 2 * r, 0, 20 * r, name = "Rhythm"), Clip("c3", "s1", "t3", 0, 0, 28 * r, name = "Kit")),
            markers = listOf(com.layerbit.stave.engine.project.Marker("m1", 3 * r, "Verse"), com.layerbit.stave.engine.project.Marker("m2", 11 * r, "Chorus", com.layerbit.stave.engine.project.Marker.Kind.CHAPTER), com.layerbit.stave.engine.project.Marker("m3", 19 * r, "Bridge"))
        ))
        val vm = EditorViewModel(app, id)
        waitUntil { vm.ui.value.loaded }
        compose.setContent { StaveTheme(ThemeChoice.DARK) { Box(Modifier.fillMaxSize().background(StaveColors.Background)) { EditorScreen(vm, onBack = {}, onAddSounds = {}) } } }
        fun shot(name: String) { compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle(); compose.onRoot().captureRoboImage("build/outputs/roborazzi/busy-$name.png") }
        compose.runOnIdle { vm.select(4 * r until 9 * r) }
        shot("open")
        compose.runOnIdle { vm.setSheet(EditSheet.SELECT) }; shot("select")
        compose.runOnIdle { vm.setSheet(EditSheet.MARKERS) }; shot("markers")
        compose.runOnIdle { vm.setSheet(null); vm.setTab(EditorTab.STUDIO) }; shot("studio")
        compose.runOnIdle { vm.setTab(EditorTab.BEATS); vm.newPattern() }
        compose.runOnIdle { for (s in listOf(0, 4, 8, 12)) vm.toggleStep(36, s); for (s in listOf(4, 12)) vm.toggleStep(38, s); for (s in 0 until 16 step 2) vm.toggleStep(42, s) }
        shot("beats")
    }

    /** The Voice tab as a podcaster sees it: a transcript of a spoken minute, with fillers. */
    @Test fun voiceTranscript() {
        val app = ApplicationProvider.getApplicationContext<StaveApplication>()
        val id = project(app)
        val words = "Welcome back to the show. Um, today we are talking about, uh, how to record a podcast at home with nothing but a phone. So, you know, the first thing is the room. A small room with soft things in it, a sofa, curtains, a bed, sounds much better than a big empty kitchen.".split(" ")
        var t = 400L
        val arr = words.joinToString(",") { w -> val d = 180L + w.length * 45L; val s = "[$t,${t + d},\"${w.replace("\"", "")}\"]"; t += d + 60; s }
        java.io.File(app.projects.dir(id), "transcript.json").writeText("{\"language\":\"en\",\"words\":[$arr]}")
        val vm = EditorViewModel(app, id)
        waitUntil { vm.ui.value.loaded }
        compose.setContent { StaveTheme(ThemeChoice.DARK) { Box(Modifier.fillMaxSize().background(StaveColors.Background)) { EditorScreen(vm, onBack = {}, onAddSounds = {}) } } }
        compose.runOnIdle { vm.setTab(EditorTab.VOICE) }
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/voice-transcript.png")
        compose.runOnIdle { vm.toggleWord(13); vm.toggleWord(14) }
        runCatching { compose.onAllNodes(androidx.compose.ui.test.hasText("kitchen.", substring = true))[0].performScrollTo() }
        compose.waitForIdle(); Thread.sleep(300); compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/voice-transcript-words.png")
    }

    @Test fun dark() = run(ThemeChoice.DARK)
    @Test fun light() = run(ThemeChoice.LIGHT)
    @Test @Config(fontScale = 1.3f) fun largeText() = run(ThemeChoice.DARK, "-large-text")
}
