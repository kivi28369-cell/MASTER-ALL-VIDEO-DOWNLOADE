package com.master.allvideodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps downloads alive while the app is in the background. */
class DownloadService : Service() {
    private val cb: () -> Unit = { refresh() }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("dl", getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Engine.serviceUp = true
        Engine.listeners.add(cb)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        Engine.listeners.remove(cb)
        Engine.serviceUp = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() { getSystemService(NotificationManager::class.java).notify(1, build()) }

    private fun build(): Notification {
        val busy = Store.tasks.count { it.status == St.DOWNLOADING || it.status == St.FETCHING || it.status == St.MERGING }
        val wait = Store.tasks.count { it.status == St.WAITING }
        val cur = Store.tasks.firstOrNull { it.status == St.DOWNLOADING }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, "dl")
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text, busy, wait))
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true)
        if (cur != null) b.setProgress(100, cur.progress, false).setSubText(cur.title)
        return b.build()
    }
}
