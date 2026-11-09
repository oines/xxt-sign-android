package com.xxt.sign.study

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xxt.sign.MainActivity
import com.xxt.sign.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 刷课前台服务：让任务在 App 退到后台/锁屏后继续运行。
 * 通知里会显示进度，并提供停止按钮。
 */
class StudyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            StudyManager.stop("用户停止")
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFY_ID, buildNotification(StudyManager.state.value))
        acquireWakeLock()

        if (!observing) {
            observing = true
            StudyManager.start()
            scope.launch {
                StudyManager.state.collect { state ->
                    if (state.running) {
                        acquireWakeLock()
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFY_ID, buildNotification(state))
                    } else {
                        releaseWakeLock()
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        if (StudyManager.state.value.running) {
            StudyManager.stop("服务已停止，刷课中断")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "刷课", NotificationManager.IMPORTANCE_LOW).apply {
                description = "视频刷课进度"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(state: StudyState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StudyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_study)
            .setContentTitle("学习通刷课")
            .setContentText(state.status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(state.status))
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)

        if (state.totalChapters > 0) {
            builder.setProgress(100, state.progress, false)
        } else {
            builder.setProgress(0, 0, state.running)
        }
        return builder.build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "XXTSign:Study"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.xxt.sign.study.STOP"
        private const val CHANNEL = "xxt_study"
        private const val NOTIFY_ID = 1002

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, StudyService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StudyService::class.java))
        }
    }
}
