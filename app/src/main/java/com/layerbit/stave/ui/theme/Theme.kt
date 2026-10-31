package com.layerbit.stave.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.stave.R
import com.layerbit.stave.prefs.ThemeChoice

/**
 * Ink and paper, with one lime band.
 *
 * The family reads as a set: the cool blue-black base Sheaf, Still, LayerLink and Deja share,
 * and one saturated colour per app spent on the primary action alone. Sheaf's is magenta,
 * Still's the amber of a darkroom safelight; Stave's is Signal, the lime of a meter's healthy
 * band - the colour that means "sound is here". It goes on Record, the play-head, the one
 * primary button and the icon's wave. Meters wear the semantic trio. Everything else is ink,
 * paper and the greys between, so the waveform is the brightest thing in the window.
 *
 * Observable state rather than constants, as Still and Sheaf do it: the palette is a holder
 * [StaveTheme] sets, and every screen recomposes into the new colours.
 */
object StaveColors {
    var Background by mutableStateOf(Dark.background); private set
    var Surface by mutableStateOf(Dark.surface); private set
    var SurfaceDim by mutableStateOf(Dark.surfaceDim); private set
    var Border by mutableStateOf(Dark.border); private set
    var BorderStrong by mutableStateOf(Dark.borderStrong); private set
    var Text by mutableStateOf(Dark.text); private set
    var Muted by mutableStateOf(Dark.muted); private set
    var Dim by mutableStateOf(Dark.dim); private set
    var Band by mutableStateOf(Dark.band); private set
    var BandBright by mutableStateOf(Dark.bandBright); private set
    var BandDim by mutableStateOf(Dark.bandDim); private set
    var OnBand by mutableStateOf(Dark.onBand); private set
    /** The canvas behind a waveform: a shade under the surface, so the wave stands forward. */
    var Canvas by mutableStateOf(Dark.canvas); private set
    /** The waveform's own ink: pale, not white, so a clipped peak in [Failed] still reads. */
    var Wave by mutableStateOf(Dark.wave); private set
    var WaveRms by mutableStateOf(Dark.waveRms); private set
    var Record by mutableStateOf(Dark.record); private set
    var Good by mutableStateOf(Dark.good); private set
    var Hot by mutableStateOf(Dark.hot); private set
    var Failed by mutableStateOf(Dark.failed); private set
    var Running by mutableStateOf(Dark.running); private set
    var isDark by mutableStateOf(true); private set

    /** A card's fill: the surface with the page showing through, so the colour behind it is felt. */
    val Glass: Color get() = Surface.copy(alpha = if (isDark) 0.74f else 0.82f)
    /** The light catching a card's top edge. */
    val Edge: Color get() = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White
    /** A selection over the waveform. */
    val Selection: Color get() = Band.copy(alpha = if (isDark) 0.22f else 0.18f)

    internal fun apply(p: Palette) {
        if (Background == p.background && isDark == p.dark) return
        Background = p.background; Surface = p.surface; SurfaceDim = p.surfaceDim; Border = p.border
        BorderStrong = p.borderStrong; Text = p.text; Muted = p.muted; Dim = p.dim; Band = p.band
        BandBright = p.bandBright; BandDim = p.bandDim; OnBand = p.onBand; Canvas = p.canvas
        Wave = p.wave; WaveRms = p.waveRms; Record = p.record; Good = p.good; Hot = p.hot
        Failed = p.failed; Running = p.running; isDark = p.dark
    }
}

internal data class Palette(
    val dark: Boolean, val background: Color, val surface: Color, val surfaceDim: Color, val border: Color,
    val borderStrong: Color, val text: Color, val muted: Color, val dim: Color, val band: Color, val bandBright: Color,
    val bandDim: Color, val onBand: Color, val canvas: Color, val wave: Color, val waveRms: Color,
    val record: Color, val good: Color, val hot: Color, val failed: Color, val running: Color
)

internal val Dark = Palette(
    dark = true,
    background = Color(0xFF070A12), surface = Color(0xFF121828), surfaceDim = Color(0xFF0C1120),
    border = Color(0xFF1F2940), borderStrong = Color(0xFF35425F),
    text = Color(0xFFF6F7FB), muted = Color(0xFFA8B3C9), dim = Color(0xFF6F7B95),
    band = Color(0xFFC8FF4D), bandBright = Color(0xFFE2FF8A), bandDim = Color(0xFF26330D), onBand = Color(0xFF0E1400),
    canvas = Color(0xFF0A0F1B), wave = Color(0xFFD6DDF0), waveRms = Color(0xFF8A96B5),
    record = Color(0xFFE8705F), good = Color(0xFF4CCB8F), hot = Color(0xFFF0A93D), failed = Color(0xFFE8705F), running = Color(0xFF5BA9E8)
)

internal val Light = Palette(
    dark = false,
    background = Color(0xFFF5F4EF), surface = Color(0xFFFFFFFF), surfaceDim = Color(0xFFECEAE3),
    border = Color(0xFFE2DED4), borderStrong = Color(0xFFC4BDAE),
    text = Color(0xFF121A26), muted = Color(0xFF4C5869), dim = Color(0xFF78849B),
    band = Color(0xFF5E9A00), bandBright = Color(0xFF487700), bandDim = Color(0xFFE6F5C7), onBand = Color(0xFFF9FFEE),
    canvas = Color(0xFFE9E7DF), wave = Color(0xFF2B3550), waveRms = Color(0xFF6B7690),
    record = Color(0xFFC53D2B), good = Color(0xFF17784E), hot = Color(0xFF8A5C0B), failed = Color(0xFFA83C27), running = Color(0xFF1C6FB8)
)

/** Space Grotesk, the family face. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

/**
 * Fira Code, for every number the app measured: timecode, dB, Hz, BPM, LUFS, sample counts.
 * Fixed-width digits keep a changing value from jittering as it updates.
 */
@OptIn(ExperimentalTextApi::class)
val FiraCode = FontFamily(
    // The file is variable; without the axis set, Android draws its lightest instance.
    Font(R.font.fira_code_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.fira_code_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
)

/** A measured number in a pill or beside a label. */
val Numerals = TextStyle(fontFamily = FiraCode, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
/** The big clock over the waveform. */
val Clock = TextStyle(fontFamily = FiraCode, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
/** A meter's scale. */
val Scale = TextStyle(fontFamily = FiraCode, fontSize = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)

private val StaveTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2.0).sp, lineHeight = 54.sp),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/** One quiet colour per family of tools, for the icon tiles and the dots beside headings. */
object ToolAccents {
    val Edit: Color get() = if (StaveColors.isDark) Color(0xFF5B9DFF) else Color(0xFF2360C8)
    val Voice: Color get() = if (StaveColors.isDark) Color(0xFFB08CFF) else Color(0xFF6B45D6)
    val Studio: Color get() = if (StaveColors.isDark) Color(0xFF3ED3C0) else Color(0xFF0E8C7D)
    val Music: Color get() = if (StaveColors.isDark) Color(0xFFFF6BA8) else Color(0xFFC81E6C)
    val Beats: Color get() = if (StaveColors.isDark) Color(0xFFFFB454) else Color(0xFFB8690A)
    val Finish: Color get() = if (StaveColors.isDark) Color(0xFF5DD48F) else Color(0xFF1E8A4D)
}

@Composable
fun StaveTheme(theme: ThemeChoice = ThemeChoice.DARK, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val palette = if (dark) Dark else Light
    StaveColors.apply(palette)
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.band, onPrimary = palette.onBand, background = palette.background, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text, surfaceVariant = palette.surfaceDim, onSurfaceVariant = palette.muted,
            outline = palette.border, error = palette.failed
        )
    } else {
        lightColorScheme(
            primary = palette.band, onPrimary = palette.onBand, background = palette.background, onBackground = palette.text,
            surface = palette.surface, onSurface = palette.text, surfaceVariant = palette.surfaceDim, onSurfaceVariant = palette.muted,
            outline = palette.border, error = palette.failed
        )
    }
    MaterialTheme(colorScheme = scheme, typography = StaveTypography) {
        CompositionLocalProvider(LocalTextStyle provides StaveTypography.bodyLarge, content = content)
    }
}
