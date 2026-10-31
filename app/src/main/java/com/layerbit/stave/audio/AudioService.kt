package com.layerbit.stave.audio

import android.app.Notification
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
 * Keeps recording and playback alive while the person looks away: a foreground service with a
 * notification that says what is happening. The graph itself lives in the application
 * (StaveApplication.transport); this service only holds the process's foreground standing.
 */
class AudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recording = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        ensureChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(getString(if (recording) R.string.notification_recording else R.string.notification_playing))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, type) else startForeground(ID, notification)
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        }
    }

    companion object {
        const val CHANNEL = "stave.audio"
        const val ID = 41
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, recording: Boolean, title: String) {
            val i = Intent(context, AudioService::class.java).putExtra(EXTRA_RECORDING, recording).putExtra(EXTRA_TITLE, title)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) { context.stopService(Intent(context, AudioService::class.java)) }
    }
}
