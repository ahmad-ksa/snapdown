package com.ahmad.snapdown

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the app alive while downloads run and shows progress in the notification bar. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        ServiceCompat.startForeground(
            this, ONGOING_ID, buildOngoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val notified = mutableSetOf<String>()
        // tasks already finished before this service started should not trigger notifications
        Engine.tasks.value.filter { !it.active }.forEach { notified += it.id }
        while (true) {
            val tasks = Engine.tasks.value
            val nm = NotificationManagerCompat.from(this)
            tasks.filter { !it.active && it.id !in notified }.forEach { t ->
                notified += t.id
                if (t.status == Status.DONE || t.status == Status.FAILED) {
                    runCatching { nm.notify(t.id.hashCode(), buildFinished(t)) }
                }
            }
            if (tasks.none { it.active }) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            runCatching { nm.notify(ONGOING_ID, buildOngoing()) }
            delay(1000)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildOngoing() = run {
        val active = Engine.tasks.value.filter { it.active }
        val current = active.firstOrNull { it.status == Status.RUNNING } ?: active.firstOrNull()
        val text = if (active.size > 1) "${active.size} تحميلات جارية" else (current?.title ?: "جاري التحضير…")
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text)
            .setContentText(current?.let { "${it.progress.toInt()}٪ • ${it.label}" } ?: "")
            .setProgress(100, current?.progress?.toInt() ?: 0, current == null || current.progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun buildFinished(t: Task) = NotificationCompat.Builder(this, CH_DONE)
        .setSmallIcon(if (t.status == Status.DONE) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
        .setContentTitle(if (t.status == Status.DONE) "اكتمل التحميل" else "فشل التحميل")
        .setContentText(t.title)
        .setAutoCancel(true)
        .setContentIntent(openAppIntent())
        .build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "التحميلات الجارية", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "التحميلات المكتملة", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val ONGOING_ID = 1001
    }
}
