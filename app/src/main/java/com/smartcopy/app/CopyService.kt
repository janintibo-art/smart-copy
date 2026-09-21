package com.smartcopy.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Garde l'application en vie pendant la copie (écran éteint, appli en arrière-plan)
 *  et affiche la progression dans une notification. */
class CopyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Copie en cours", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(CopyEngine.state.value)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCopy:copie")?.apply {
                acquire(6L * 60 * 60 * 1000)
            }
        }
        scope.coroutineContext.cancelChildren()
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                val s = CopyEngine.state.value
                if (!s.running) break
                nm?.notify(NOTIFICATION_ID, buildNotification(s))
                delay(1000)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(s: UiState): Notification {
        val pct = if (s.totalBytes > 0) (s.copiedBytes * 100 / s.totalBytes).toInt().coerceIn(0, 100) else 0
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = if (s.mode == TransferMode.MOVE) "Déplacement" else "Copie"
        val title = if (s.paused) "$action en pause — $pct %" else "$action en cours — $pct %"
        val text = "${formatBytes(s.copiedBytes)} / ${formatBytes(s.totalBytes)} · ${formatSpeed(s.speed)} · reste ${formatDuration(s.etaMs)}"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "copie"
        private const val NOTIFICATION_ID = 42
    }
}
