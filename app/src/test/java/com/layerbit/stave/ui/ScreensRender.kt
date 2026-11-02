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
@Config(sdk = [35], qualifiers = "w411dp-h913dp-xxhdpi", application = android.app.Application::class)
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

    @Composable private fun home() = HomeScreen(
        projects = projects, onOpenSounds = {}, onRecord = {}, onNewSession = {}, onOpenProject = {},
        onRemoveProject = {}, onRenameProject = { _, _ -> }, onDuplicateProject = {}, onTool = {},
        onGuide = {}, onContact = {}, onSettings = {}, onAbout = {}
    )
}
