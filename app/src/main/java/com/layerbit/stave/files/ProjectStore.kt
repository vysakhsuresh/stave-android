package com.layerbit.stave.files

import android.content.Context
import com.layerbit.stave.engine.json.Json
import com.layerbit.stave.engine.json.JsonObject
import java.io.File

/**
 * Where projects live: `files/projects/<id>/`, each a folder with `project.json`, its media,
 * peaks, takes and renders (ARCHITECTURE.md). The store lists, makes, renames and bins them; the
 * project's own contents are the editor's business (`project/ProjectFiles`).
 *
 * Deleting moves to `files/projects/trash/<id>/` for thirty days, so Undo is a move back.
 */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val trash = File(root, "trash").apply { mkdirs() }

    data class Entry(val id: String, val name: String, val modifiedAt: Long, val durationMs: Long, val tracks: Int, val kind: String) {
        val dir: File get() = File(dirOf(id))
        companion object {
            internal var dirOf: (String) -> String = { it }
        }
    }

    init { Entry.dirOf = { File(root, it).path } }

    fun dir(id: String): File = File(root, id)

    /** Every project; [withEmpty] false leaves out the ones nothing was ever put in (the home list). */
    fun list(withEmpty: Boolean = true): List<Entry> = root.listFiles { f -> f.isDirectory && f.name != "trash" }.orEmpty()
        .filter { withEmpty || !isEmpty(it) }
        .mapNotNull { entryOf(it) }
        .sortedByDescending { it.modifiedAt }

    /**
     * A project nothing was put in: no sound, no track, no pattern, no take, no marker. Record and
     * New session make one each time, and the home list filled with "Untitled" rows that opened
     * onto nothing. Any doubt (a file that will not parse) counts as not empty.
     */
    private fun isEmpty(dir: File): Boolean {
        val json = runCatching { Json.parse(File(dir, "project.json").readText()) as? JsonObject }.getOrNull() ?: return false
        if (listOf("sources", "tracks", "clips", "patterns", "markers", "regions").any { (json.arr(it)?.size ?: 0) > 0 }) return false
        return listOf("media", "takes").none { File(dir, it).listFiles()?.isNotEmpty() == true }
    }

    /** Bins the empty projects; called at startup only, when none can be open. The bin keeps them thirty days. */
    fun sweepEmpty() {
        root.listFiles { f -> f.isDirectory && f.name != "trash" }.orEmpty().filter { isEmpty(it) }.forEach { remove(it.name) }
    }

    private fun entryOf(dir: File): Entry? {
        val meta = File(dir, "project.json")
        val json = runCatching { Json.parse(meta.readText()) as? JsonObject }.getOrNull()
        return Entry(
            id = dir.name,
            name = json?.string("name") ?: "",
            modifiedAt = meta.lastModified().takeIf { it > 0 } ?: dir.lastModified(),
            durationMs = json?.long("durationMs") ?: 0L,
            tracks = json?.int("trackCount") ?: 0,
            kind = json?.string("kind") ?: "edit"
        )
    }

    /** A new, staged project folder with a minimal `project.json`; the editor fills the rest. */
    fun create(name: String, kind: String): Entry {
        val id = "p" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        val dir = File(root, id).apply { mkdirs(); File(this, "media").mkdirs(); File(this, "peaks").mkdirs(); File(this, "takes").mkdirs(); File(this, "renders").mkdirs(); File(this, "snapshots").mkdirs() }
        val json = JsonObject().put("name", name).put("kind", kind).put("version", 1).put("durationMs", 0L).put("trackCount", 0)
        File(dir, "project.json").writeText(Json.write(json))
        return entryOf(dir)!!
    }

    fun rename(id: String, name: String) {
        val meta = File(dir(id), "project.json")
        val json = runCatching { Json.parse(meta.readText()) as JsonObject }.getOrNull() ?: JsonObject()
        meta.writeText(Json.write(json.put("name", name)))
    }

    fun duplicate(id: String): Entry? {
        val from = dir(id)
        if (!from.isDirectory) return null
        val copy = create(entryOf(from)?.name?.let { "$it copy" } ?: "", entryOf(from)?.kind ?: "edit")
        from.copyRecursively(copy.dir, overwrite = true)
        return entryOf(copy.dir)
    }

    /** To the bin. */
    fun remove(id: String): Boolean {
        val from = dir(id)
        if (!from.isDirectory) return false
        val to = File(trash, id)
        if (to.exists()) to.deleteRecursively()
        return from.renameTo(to)
    }

    fun restore(id: String): Boolean = File(trash, id).let { it.isDirectory && it.renameTo(dir(id)) }

    fun binned(): List<Entry> = trash.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { entryOf(it) }.sortedByDescending { it.modifiedAt }

    /** Empties the bin of anything older than thirty days; called at startup. */
    fun sweepBin(now: Long = System.currentTimeMillis()) {
        trash.listFiles()?.forEach { if (now - it.lastModified() > 30L * 24 * 3600 * 1000) it.deleteRecursively() }
    }

    fun bytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun scratchBytes(): Long = root.walkBottomUp().filter { it.isFile && (it.parentFile?.name == "peaks" || it.parentFile?.name == "renders") }.sumOf { it.length() }

    fun clearScratch() {
        root.listFiles()?.forEach { p -> listOf("peaks", "renders").forEach { File(p, it).listFiles()?.forEach { f -> f.delete() } } }
    }
}
