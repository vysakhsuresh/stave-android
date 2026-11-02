package com.layerbit.stave.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.layerbit.stave.files.ProjectStore
import com.layerbit.stave.ui.home.HomeScreen
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.StaveTheme
import com.layerbit.stave.prefs.ThemeChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens drawn on the laptop at the moto's size (1080 x 2400, 400 dpi), in both themes,
 * into app/build/outputs/roborazzi/. Not a check: an eye for a session without a phone. Run
 * `./gradlew :app:testFullDebugUnitTest --tests '*ScreensRender*' -Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h913dp-xxhdpi")
class ScreensRender {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, theme: ThemeChoice, content: @Composable () -> Unit) {
        compose.setContent {
            StaveTheme(theme) { Box(Modifier.fillMaxSize().background(StaveColors.Background)) { content() } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${theme.name.lowercase()}.png")
    }

    private val projects = listOf(
        ProjectStore.Entry("p1", "Ramayana Ravana Tandavam (Song) - AR Rahman", 1_793_600_000_000, 115_548, 1, "edit"),
        ProjectStore.Entry("p2", "Night drive", 1_793_590_000_000, 8_000, 3, "studio"),
        ProjectStore.Entry("p3", "Episode 12 - Home studio", 1_793_580_000_000, 1_850_000, 2, "voice"),
        ProjectStore.Entry("p4", "Kalimba", 1_793_570_000_000, 348_000, 2, "edit")
    )

    @Test fun homeDark() = shot("home", ThemeChoice.DARK) { home() }
    @Test fun homeLight() = shot("home", ThemeChoice.LIGHT) { home() }
    // At the font size a person with weaker sight sets: nothing may clip or overlap.
    @Test @Config(fontScale = 1.3f) fun homeLargeText() = shot("home-large-text", ThemeChoice.DARK) { home() }
    @Test @Config(fontScale = 1.3f) fun contactLargeText() = shot("contact-large-text", ThemeChoice.DARK) { com.layerbit.stave.ui.pages.ContactScreen() }

    // The long pages on a tall canvas, so the whole page is seen at once.
    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi") fun settingsDark() = shot("settings", ThemeChoice.DARK) { settings() }
    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi") fun settingsLight() = shot("settings", ThemeChoice.LIGHT) { settings() }
    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi") fun guideDark() = shot("guide", ThemeChoice.DARK) { com.layerbit.stave.ui.pages.GuideScreen() }
    @Test fun aboutDark() = shot("about", ThemeChoice.DARK) { com.layerbit.stave.ui.about.AboutScreen() }
    @Test fun contactDark() = shot("contact", ThemeChoice.DARK) { com.layerbit.stave.ui.pages.ContactScreen() }
    @Test fun aboutLight() = shot("about", ThemeChoice.LIGHT) { com.layerbit.stave.ui.about.AboutScreen() }
    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi") fun libraryDark() = shot("library", ThemeChoice.DARK) { com.layerbit.stave.ui.library.LibraryScreen() }

    @Composable private fun settings() = com.layerbit.stave.ui.settings.SettingsScreen(
        settings = com.layerbit.stave.prefs.Settings(), projectBytes = 312_000_000, scratchBytes = 48_000_000, projectCount = 4,
        onChange = {}, onClearScratch = {}
    )

    @Composable private fun home() = HomeScreen(
        projects = projects, onOpenSounds = {}, onRecord = {}, onNewSession = {}, onOpenProject = {},
        onRemoveProject = {}, onRenameProject = { _, _ -> }, onDuplicateProject = {}, onTool = {},
        onGuide = {}, onContact = {}, onSettings = {}, onAbout = {}
    )
}
