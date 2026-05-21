package site.sexyminup.p2pfileshare

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
import androidx.core.content.ContextCompat
import site.sexyminup.p2pfileshare.transfer.formatBytes

class P2PTransferForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "전송 준비 중"
        val progressBytes = intent?.getLongExtra(EXTRA_PROGRESS_BYTES, 0L) ?: 0L
        val totalBytes = intent?.getLongExtra(EXTRA_TOTAL_BYTES, 0L) ?: 0L
        val notification = buildNotification(status, progressBytes, totalBytes)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(status: String, progressBytes: Long, totalBytes: Long): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progressPercent = if (totalBytes > 0L) {
            ((progressBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val detail = if (totalBytes > 0L) {
            "${formatBytes(progressBytes)} / ${formatBytes(totalBytes)}"
        } else {
            "파일 전송을 준비하고 있습니다."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("send honey where")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$detail"))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (totalBytes > 0L) {
                    setProgress(100, progressPercent, false)
                    setSubText(detail)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "파일 전송",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "백그라운드 파일 전송 상태"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "p2p_transfer"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "site.sexyminup.p2pfileshare.action.STOP_TRANSFER_SERVICE"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_PROGRESS_BYTES = "progress_bytes"
        private const val EXTRA_TOTAL_BYTES = "total_bytes"

        fun start(context: Context, status: String, progressBytes: Long = 0L, totalBytes: Long = 0L) {
            ContextCompat.startForegroundService(context, statusIntent(context, status, progressBytes, totalBytes))
        }

        fun update(context: Context, status: String, progressBytes: Long, totalBytes: Long) {
            context.startService(statusIntent(context, status, progressBytes, totalBytes))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, P2PTransferForegroundService::class.java))
        }

        private fun statusIntent(
            context: Context,
            status: String,
            progressBytes: Long,
            totalBytes: Long,
        ): Intent = Intent(context, P2PTransferForegroundService::class.java)
            .putExtra(EXTRA_STATUS, status)
            .putExtra(EXTRA_PROGRESS_BYTES, progressBytes)
            .putExtra(EXTRA_TOTAL_BYTES, totalBytes)
    }
}
