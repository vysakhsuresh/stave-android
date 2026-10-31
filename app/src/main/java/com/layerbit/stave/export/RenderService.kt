package com.layerbit.stave.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.layerbit.stave.MainActivity
import com.layerbit.stave.R

/**
 * A long export keeps going with the screen locked: a foreground service of the media
 * processing type (Android 15 asks for it by name; Squish found androidx.core's ServiceCompat
 * masked that type to NONE, so the framework call is made directly). The export itself runs in
 * Exporter under the application scope; the service shows its percentage.
 */
class RenderService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val percent = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_export_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
            .setContentText(getString(R.string.notification_exporting, percent)).setProgress(100, percent, false)
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 35) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Exception) {
            android.util.Log.w("StaveExport", "could not go foreground", e)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "stave.export"
        const val ID = 42
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_TITLE = "title"

        fun update(context: Context, title: String, percent: Int) {
            val i = Intent(context, RenderService::class.java).putExtra(EXTRA_PERCENT, percent).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) { context.stopService(Intent(context, RenderService::class.java)) }
    }
}
