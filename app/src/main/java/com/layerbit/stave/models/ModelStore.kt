package com.layerbit.stave.models

import android.content.Context
import java.io.File

/**
 * Where a model is found, in this order: a file the person imported (files/models), the Play
 * asset pack's directory (the `play` flavour), the APK's own assets (the `full` flavour, copied
 * out once because whisper.cpp wants a path). Nothing downloads: the manifest has no INTERNET.
 */
class ModelStore(private val context: Context) {
    data class Model(val id: String, val label: String, val fileName: String, val approxMb: Int, val note: String)

    val whisperBase = Model("whisper-base", "Standard speech model", "ggml-base-q5_1.bin", 57, "Good for clear English and the major languages; quick.")
    val whisperSmall = Model("whisper-small", "Better speech model", "ggml-small-q5_1.bin", 181, "Noticeably better for Hindi, Malayalam and other Indian languages; slower.")
    val whisperModels = listOf(whisperSmall, whisperBase)

    private val imported = File(context.filesDir, "models").apply { mkdirs() }

    /** The file for [model] if it is on this phone, else null. */
    fun file(model: Model): File? {
        File(imported, model.fileName).takeIf { it.isFile && it.length() > 1_000_000 }?.let { return it }
        // Asset pack or bundled assets: copied out to the imported folder on first use.
        val fromAssets = runCatching {
            context.assets.open("models/${model.fileName}").use { input ->
                val out = File(imported, model.fileName)
                val tmp = File(imported, model.fileName + ".part")
                tmp.outputStream().use { input.copyTo(it) }
                tmp.renameTo(out)
                out
            }
        }.getOrNull()
        return fromAssets
    }

    /** The best speech model present: the small one when it is, else the base. */
    fun bestWhisper(): Pair<Model, File>? = whisperModels.firstNotNullOfOrNull { m -> file(m)?.let { m to it } }

    /** A model file picked from the document picker, copied in under its canonical name when it is one we know. */
    fun import(name: String, copy: (File) -> Unit): Model? {
        val model = whisperModels.firstOrNull { it.fileName.equals(name, ignoreCase = true) } ?: return null
        val tmp = File(imported, model.fileName + ".part")
        copy(tmp)
        tmp.renameTo(File(imported, model.fileName))
        return model
    }

    fun present(): List<Model> = whisperModels.filter { File(imported, it.fileName).isFile }
    fun bytes(): Long = imported.listFiles()?.sumOf { it.length() } ?: 0L
}
