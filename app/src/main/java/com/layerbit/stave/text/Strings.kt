package com.layerbit.stave.text

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Translated text for code that has no Context of its own: the engine's doors, a job's state,
 * a notification. Composables use stringResource() as usual; this is for sentences made far from
 * any screen and shown on one later. Installed first thing in StaveApplication.onCreate.
 */
object Strings {
    private lateinit var app: Context

    fun install(context: Context) { app = context.applicationContext }

    val installed: Boolean get() = ::app.isInitialized

    fun get(@StringRes id: Int, vararg args: Any): String = when {
        !installed -> "#$id" + args.joinToString(prefix = "(", postfix = ")")
        args.isEmpty() -> app.getString(id)
        else -> app.getString(id, *args)
    }

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String {
        val values = if (args.isEmpty()) arrayOf<Any>(count) else args
        return if (!installed) "#$id" + values.joinToString(prefix = "(", postfix = ")")
        else app.resources.getQuantityString(id, count, *values)
    }
}
