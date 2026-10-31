package com.layerbit.stave.files

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crash log: written to private storage and offered from Settings. Nothing sends it; the
 * person shares the file, or does not. Installed before anything else so a crash anywhere after
 * is written down.
 */
object CrashLog {
    private const val FILE = "crashes/last.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { write(context, thread, error) } catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun read(context: Context): String? = file(context).takeIf { it.isFile }?.readText()

    fun clear(context: Context) { file(context).delete() }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        f.writeText("Stave crash at $stamp on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\nThread: ${thread.name}\n\n$trace")
    }
}
