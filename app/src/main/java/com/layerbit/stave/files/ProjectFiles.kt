package com.layerbit.stave.files

import com.layerbit.stave.engine.project.ProjectJson
import com.layerbit.stave.engine.project.Session
import java.io.File

/**
 * A project folder's files (ARCHITECTURE.md): the session, its snapshots, and where its media,
 * peaks, takes and renders go. Saving is atomic - written beside, then renamed over - so a kill
 * mid-write leaves the last good file, never half of one.
 */
class ProjectFiles(val dir: File) {
    val id: String get() = dir.name
    val sessionFile: File get() = File(dir, "project.json")
    val media: File get() = File(dir, "media").apply { mkdirs() }
    val peaks: File get() = File(dir, "peaks").apply { mkdirs() }
    val takes: File get() = File(dir, "takes").apply { mkdirs() }
    val renders: File get() = File(dir, "renders").apply { mkdirs() }
    val snapshots: File get() = File(dir, "snapshots").apply { mkdirs() }
    val exports: File get() = File(dir, "exports").apply { mkdirs() }

    fun load(): Session? = sessionFile.takeIf { it.isFile }?.let { f -> runCatching { ProjectJson.read(f.readText()) }.getOrNull() }

    fun save(session: Session) {
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(ProjectJson.write(session))
        if (!tmp.renameTo(sessionFile)) { sessionFile.delete(); tmp.renameTo(sessionFile) }
    }

    /** A copy of the session every ten minutes, five kept: the "Earlier version" a grid offers. */
    fun snapshotIfDue(session: Session, now: Long = System.currentTimeMillis()) {
        val latest = snapshots.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        if (now - latest < SNAPSHOT_EVERY_MS) return
        File(snapshots, "$now.json").writeText(ProjectJson.write(session))
        snapshots.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_SNAPSHOTS)?.forEach { it.delete() }
    }

    fun earlierVersions(): List<File> = snapshots.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** A new source file name under media/, unique. */
    fun newSourceFile(id: String): File = File(media, "$id.f32")

    fun sourceFile(relative: String): File = File(dir, relative)

    /** Media no clip and no source names: the tidy-up a project runs when it closes. */
    fun sweepUnreferenced(session: Session) {
        val named = session.sources.map { File(dir, it.file).canonicalPath }.toSet()
        media.listFiles()?.forEach { if (it.canonicalPath !in named) it.delete() }
        val peakNames = session.sources.map { "${it.id}.peaks" }.toSet()
        peaks.listFiles()?.forEach { if (it.name !in peakNames) it.delete() }
    }

    companion object {
        const val SNAPSHOT_EVERY_MS = 10L * 60 * 1000
        const val KEEP_SNAPSHOTS = 5
    }
}
