package com.layerbit.stave.ui

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.stave.R
import com.layerbit.stave.StaveApplication
import com.layerbit.stave.files.ProjectStore
import com.layerbit.stave.prefs.Settings
import com.layerbit.stave.ui.about.AboutScreen
import com.layerbit.stave.ui.components.StaveTopBar
import com.layerbit.stave.ui.editor.EditorRoute
import com.layerbit.stave.ui.home.HomeScreen
import com.layerbit.stave.ui.pages.ContactScreen
import com.layerbit.stave.ui.pages.GuideScreen
import com.layerbit.stave.ui.settings.SettingsScreen
import com.layerbit.stave.ui.theme.StaveColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val GUIDE = "guide"
    const val CONTACT = "contact"
    const val LIBRARY = "library"
    const val CONVERT = "convert"
    const val EDITOR = "editor?project={project}&tool={tool}"

    fun editor(project: String, tool: String? = null): String =
        "editor?project=" + Uri.encode(project) + (if (tool != null) "&tool=$tool" else "")
}

/** What arrived from outside and where it goes. */
sealed interface Arrival {
    /** Sounds from the picker, a share or "open with": a new project opens on them, on [tool]'s screen when a tool asked. */
    data class Sounds(val uris: List<Uri>, val tool: String?) : Arrival
    data class Shortcut(val tool: String) : Arrival
}

/**
 * Navigation. One project per editor session; the editor's own tools (Edit, Voice, Studio,
 * Musician, Beats, Finish) are tabs inside it, so a sound opened for one job can be carried to
 * the next without leaving.
 */
@Composable
fun StaveApp(
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    arrivalTicket: Int,
    arrival: Arrival?,
    onPickSounds: (tool: String?) -> Unit,
    onPickModel: () -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val stave = context.applicationContext as StaveApplication
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<ProjectStore.Entry>>(emptyList()) }
    var projectsStamp by remember { mutableLongStateOf(0L) }
    LaunchedEffect(projectsStamp) { projects = withContext(Dispatchers.IO) { stave.projects.list(withEmpty = false) } }
    var removing by remember { mutableStateOf<ProjectStore.Entry?>(null) }

    LaunchedEffect(arrivalTicket) {
        if (arrivalTicket == 0) return@LaunchedEffect
        when (val a = arrival) {
            is Arrival.Sounds -> {
                val entry = withContext(Dispatchers.IO) { stave.projects.create("", a.tool ?: "edit") }
                stave.handoff.offer(entry.id, a.uris)
                navController.navigate(Routes.editor(entry.id, a.tool)) { launchSingleTop = true }
            }
            is Arrival.Shortcut -> when (a.tool) {
                "record" -> {
                    val entry = withContext(Dispatchers.IO) { stave.projects.create("", "voice") }
                    navController.navigate(Routes.editor(entry.id, "record")) { launchSingleTop = true }
                }
                else -> onPickSounds(a.tool)
            }
            null -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(StaveColors.Background).windowInsetsPadding(WindowInsets.systemBars)) {
        NavHost(
            navController = navController, startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(240)) + slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it / 12 } + scaleIn(tween(280), initialScale = 0.965f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 1.02f) },
            popEnterTransition = { fadeIn(tween(240)) + scaleIn(tween(280), initialScale = 1.03f) },
            popExitTransition = { fadeOut(tween(180)) + slideOutVertically(tween(240)) { it / 12 } + scaleOut(tween(220), targetScale = 0.965f) }
        ) {
            composable(Routes.HOME) {
                LaunchedEffect(Unit) { projectsStamp = System.nanoTime() }
                HomeScreen(
                    projects = projects,
                    onOpenSounds = { onPickSounds(null) },
                    onRecord = {
                        scope.launch {
                            val entry = withContext(Dispatchers.IO) { stave.projects.create("", "voice") }
                            navController.navigate(Routes.editor(entry.id, "record"))
                        }
                    },
                    onNewSession = {
                        scope.launch {
                            val entry = withContext(Dispatchers.IO) { stave.projects.create("", "studio") }
                            navController.navigate(Routes.editor(entry.id, "studio"))
                        }
                    },
                    onOpenProject = { p -> navController.navigate(Routes.editor(p.id)) },
                    onRemoveProject = { removing = it },
                    onRenameProject = { p, name -> scope.launch(Dispatchers.IO) { stave.projects.rename(p.id, name); projectsStamp = System.nanoTime() } },
                    onDuplicateProject = { p -> scope.launch(Dispatchers.IO) { stave.projects.duplicate(p.id); projectsStamp = System.nanoTime() } },
                    onTool = { tool -> stave.settings.rememberTool(tool.uppercase()); onPickSounds(tool) },
                    onGuide = { navController.navigate(Routes.GUIDE) },
                    onContact = { navController.navigate(Routes.CONTACT) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onLibrary = { navController.navigate(Routes.LIBRARY) },
                    onConvert = { navController.navigate(Routes.CONVERT) }
                )
            }
            composable(Routes.CONVERT) {
                com.layerbit.stave.ui.convert.ConvertScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LIBRARY) {
                Column { StaveTopBar(title = stringResource(R.string.library_title), subtitle = stringResource(R.string.library_subtitle), onBack = { navController.popBackStack() }); com.layerbit.stave.ui.library.LibraryScreen() }
            }
            composable(Routes.GUIDE) {
                Column { StaveTopBar(title = stringResource(R.string.guide_title), onBack = { navController.popBackStack() }); GuideScreen() }
            }
            composable(Routes.CONTACT) {
                Column { StaveTopBar(title = stringResource(R.string.contact_title), onBack = { navController.popBackStack() }); ContactScreen() }
            }
            composable(Routes.ABOUT) {
                Column {
                    StaveTopBar(title = stringResource(R.string.about_title), onBack = { navController.popBackStack() })
                    AboutScreen(onGuide = { navController.navigate(Routes.GUIDE) }, onContact = { navController.navigate(Routes.CONTACT) })
                }
            }
            composable(Routes.SETTINGS) {
                var projectBytes by remember { mutableLongStateOf(0L) }
                var scratchBytes by remember { mutableLongStateOf(0L) }
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { projectBytes = stave.projects.bytes(); scratchBytes = stave.projects.scratchBytes() } }
                Column {
                    StaveTopBar(title = stringResource(R.string.app_settings), onBack = { navController.popBackStack() })
                    SettingsScreen(
                        settings = settings, projectBytes = projectBytes, scratchBytes = scratchBytes, projectCount = projects.size, onChange = onSettingsChange,
                        onClearScratch = { scope.launch(Dispatchers.IO) { stave.projects.clearScratch(); scratchBytes = 0 } },
                        onPickModel = onPickModel
                    )
                }
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("project") { type = NavType.StringType },
                    navArgument("tool") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { entry ->
                EditorRoute(
                    projectId = entry.arguments?.getString("project") ?: return@composable,
                    startTool = entry.arguments?.getString("tool"),
                    onBack = { navController.popBackStack() },
                    onAddSounds = { onPickSounds(null) }
                )
            }
        }

        removing?.let { p ->
            AlertDialog(
                onDismissRequest = { removing = null }, containerColor = StaveColors.Surface,
                title = { Text(stringResource(R.string.home_remove_title), color = StaveColors.Text) },
                text = { Text(stringResource(R.string.home_remove_body), color = StaveColors.Muted) },
                confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { stave.projects.remove(p.id); projectsStamp = System.nanoTime() }; removing = null }) { Text(stringResource(R.string.app_delete), color = StaveColors.Failed) } },
                dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.app_cancel), color = StaveColors.Muted) } }
            )
        }
    }
}
