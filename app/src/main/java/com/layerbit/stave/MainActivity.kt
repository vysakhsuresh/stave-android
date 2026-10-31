package com.layerbit.stave

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.layerbit.stave.ui.Arrival
import com.layerbit.stave.ui.StaveApp
import com.layerbit.stave.ui.components.ProvideLivelyClock
import com.layerbit.stave.ui.theme.StaveColors
import com.layerbit.stave.ui.theme.StaveTheme

class MainActivity : ComponentActivity() {

    private val stave: StaveApplication get() = application as StaveApplication

    /** Bumped on every arrival from outside, which is what tells the UI to navigate. */
    private var ticket by mutableIntStateOf(0)
    private var arrival by mutableStateOf<Arrival?>(null)

    /** The front door: any audio file, or a video for its sound. Persistable, so the project can re-read it later. */
    private val pickSounds = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            arrival = Arrival.Sounds(uris, pendingTool); pendingTool = null; ticket += 1
        }
    }
    private var pendingTool: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by stave.settings.state.collectAsState()
            StaveTheme(theme = settings.theme) {
                LaunchedEffect(StaveColors.isDark) {
                    val style = if (StaveColors.isDark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                ProvideLivelyClock(enabled = settings.lively) {
                    StaveApp(
                        settings = settings,
                        onSettingsChange = { transform -> stave.settings.update(transform) },
                        arrivalTicket = ticket,
                        arrival = arrival,
                        onPickSounds = { tool -> pendingTool = tool; pickSounds.launch(arrayOf("audio/*", "video/*")) }
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Share, "Open with", and the launcher shortcuts. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtraCompat(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> if (intent.data?.scheme == "stave") emptyList() else listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            arrival = Arrival.Sounds(uris, null); ticket += 1
            return
        }
        val tool = intent.data?.takeIf { it.scheme == "stave" && it.host == "tool" }?.lastPathSegment
        if (tool != null) { arrival = Arrival.Shortcut(tool); ticket += 1 }
    }

    private fun Intent.getParcelableExtraCompat(name: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, Uri::class.java) else @Suppress("DEPRECATION") getParcelableExtra(name)

    private fun Intent.getParcelableArrayListExtraCompat(name: String): List<Uri> =
        (if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, Uri::class.java) else @Suppress("DEPRECATION") getParcelableArrayListExtra(name)) ?: emptyList()
}
