package com.layerbit.stave.files

import android.net.Uri

/**
 * Sounds picked or shared before the editor that will take them exists. The activity puts them
 * here against the project id it made; the editor collects them once when it opens. Kept out of
 * the navigation route, which has a length limit a dozen content URIs would pass.
 */
class Handoff {
    private val pending = HashMap<String, List<Uri>>()

    @Synchronized fun offer(projectId: String, uris: List<Uri>) { pending[projectId] = (pending[projectId] ?: emptyList()) + uris }

    @Synchronized fun take(projectId: String): List<Uri> = pending.remove(projectId) ?: emptyList()
}
