package com.layerbit.stave

import android.app.Application
import com.layerbit.stave.files.CrashLog
import com.layerbit.stave.files.Handoff
import com.layerbit.stave.files.MediaImport
import com.layerbit.stave.models.ModelStore
import com.layerbit.stave.audio.Transport
import com.layerbit.stave.files.ProjectStore
import com.layerbit.stave.prefs.StaveSettings
import com.layerbit.stave.text.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's object graph, assembled by hand. A handful of singletons, none with a complicated
 * lifetime; a dependency-injection framework would add a build step to solve a problem that has
 * not appeared.
 */
class StaveApplication : Application() {
    val settings: StaveSettings by lazy { StaveSettings(this) }
    val projects: ProjectStore by lazy { ProjectStore(this) }
    val handoff: Handoff by lazy { Handoff() }
    val media: MediaImport by lazy { MediaImport(this) }
    val models: ModelStore by lazy { ModelStore(this) }
    /** One graph for the process: opened by the first editor, kept while the app lives. */
    val transport: Transport by lazy { Transport(applicationScope) }

    /** For the handful of jobs that outlive any screen: a render, the startup sweep. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Strings.install(this)
        applicationScope.launch { projects.sweepBin() }
    }
}
