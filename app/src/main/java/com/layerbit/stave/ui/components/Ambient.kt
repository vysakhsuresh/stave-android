package com.layerbit.stave.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.layerbit.stave.ui.theme.StaveColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What makes a screen feel alive without getting in the way: soft colour drifting behind the
 * page and things arriving rather than appearing. Every one of these reads the lively clock at
 * draw time only, so nothing recomposes, and every one is still when motion is off - Android's
 * animations off, or Lively off in Settings.
 */
val LocalLivelyClock = staticCompositionLocalOf<State<Float>?> { null }

private const val LAP_MS = 12_000
private const val STEP_MS = 100L
private const val TAU = (2 * PI).toFloat()

@Composable
fun ProvideLivelyClock(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemAllows = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
    if (!enabled || !systemAllows) {
        CompositionLocalProvider(LocalLivelyClock provides null, content = content)
        return
    }
    // Ten steps a second, not sixty: the pools drift about 11 px a second, so a frame per vsync
    // redrew four full-screen gradients to move them a fifth of a pixel, for as long as Home was
    // open. withFrameMillis waits for a frame, so the clock stops when nothing is on screen.
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = withFrameMillis { it }
            clock.floatValue = (now % LAP_MS).toFloat() / LAP_MS
            delay(STEP_MS)
        }
    }
    CompositionLocalProvider(LocalLivelyClock provides clock, content = content)
}

/** Four soft pools of the brand's colours, drifting slowly behind the content. */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier, strength: Float = 1f, colours: List<Color> = emptyList()) {
    val clock = LocalLivelyClock.current
    val dark = StaveColors.isDark
    val band = colours.getOrNull(0) ?: StaveColors.Band
    val teal = colours.getOrNull(1) ?: Color(0xFF3ED3C0)
    val pink = colours.getOrNull(2) ?: Color(0xFFFF6BA8)
    val blue = colours.getOrNull(3) ?: Color(0xFF5B9DFF)
    Canvas(modifier.fillMaxSize()) {
        val t = (clock?.value ?: 0.37f) * TAU
        val w = size.width; val h = size.height
        val k = (if (dark) 1f else 0.8f) * strength
        fun pool(c: Color, cx: Float, cy: Float, r: Float, a: Float) {
            val centre = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * k), c.copy(alpha = 0f)), center = centre, radius = r), r, centre)
        }
        pool(band, w * (0.88f + 0.06f * sin(t)), h * (0.06f + 0.04f * cos(t + 1.2f)), w * 0.6f, 0.22f)
        pool(teal, w * (0.08f + 0.05f * cos(t + 2.4f)), h * (0.42f + 0.05f * sin(t + 0.6f)), w * 0.55f, 0.16f)
        pool(pink, w * (0.72f + 0.05f * sin(t + 4.1f)), h * (0.78f + 0.04f * cos(t + 3.3f)), w * 0.5f, 0.12f)
        pool(blue, w * (0.18f + 0.04f * sin(t + 5.2f)), h * (0.98f + 0.03f * cos(t + 1.9f)), w * 0.45f, 0.10f)
    }
}

/** Arrives from a little below, after its neighbours: the [index]th thing on a screen. */
@Composable
fun rise(index: Int, stepMs: Long = 45, distancePx: Float = 28f): Modifier {
    if (LocalLivelyClock.current == null) return Modifier
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index * stepMs); p.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
    return Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * distancePx }
}
