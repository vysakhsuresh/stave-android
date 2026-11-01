package com.layerbit.stave.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.layerbit.stave.MainActivity
import com.layerbit.stave.R

/**
 * A home-screen button that starts a recording: one tap opens the app on the Voice tab with the
 * mic armed, the same `stave://tool/record` the launcher shortcut sends. RemoteViews, not Glance:
 * one button needs no framework, and the widget stays out of the Compose dependency graph.
 */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        fun views(context: Context): RemoteViews {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("stave://tool/record"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return RemoteViews(context.packageName, R.layout.widget_record).apply { setOnClickPendingIntent(R.id.widget_record_root, pending) }
        }
    }
}
