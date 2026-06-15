package com.remote.dipgonc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class GoncForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            P2PManager.stopByUser()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(this, P2PManager.getP2PStatus()))
        if (!P2PManager.isInitialized()) {
            P2PManager.init(applicationContext, object : P2PManager.CallBack() {})
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "gonc_connection"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.remote.dipgonc.action.START_GONC_KEEPALIVE"
        private const val ACTION_STOP = "com.remote.dipgonc.action.STOP_GONC"

        fun intent(context: Context): Intent {
            return Intent(context, GoncForegroundService::class.java)
        }

        fun startIntent(context: Context): Intent {
            return intent(context).setAction(ACTION_START)
        }

        fun updateNotification(context: Context, status: P2PManager.P2PStatus) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(context, status))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gonc连接",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持Gonc后台连接"
            manager.createNotificationChannel(channel)
        }

        private fun buildNotification(
            context: Context,
            status: P2PManager.P2PStatus
        ): Notification {
            ensureChannel(context)
            val openIntent = Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val openPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                pendingIntentFlags()
            )
            val stopPendingIntent = PendingIntent.getService(
                context,
                1,
                intent(context).setAction(ACTION_STOP),
                pendingIntentFlags()
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Gonc后台连接")
                .setContentText(statusText(status))
                .setOngoing(status == P2PManager.P2PStatus.CONNECTING || status == P2PManager.P2PStatus.CONNECTED)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPendingIntent)
                .addAction(0, "停止连接", stopPendingIntent)
                .build()
        }

        private fun statusText(status: P2PManager.P2PStatus): String {
            return when (status) {
                P2PManager.P2PStatus.CONNECTED -> "已连接，返回桌面后仍会保持"
                P2PManager.P2PStatus.CONNECTING -> "连接中，正在后台保持"
                P2PManager.P2PStatus.DISCONNECTED -> "未连接"
                P2PManager.P2PStatus.ERROR -> "连接异常"
            }
        }

        private fun pendingIntentFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            return flags
        }
    }
}
