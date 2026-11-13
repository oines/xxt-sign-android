package com.xxt.sign.work

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
 * 做题前台服务：让章节测验在 App 退到后台 / 锁屏后继续跑。
 *
 * 和 [com.xxt.sign.study.StudyService]（刷课）同一套做法：
 * 通知栏显示当前课程/章节、题目进度和进度条，并提供「停止」按钮。
 *
 * 注意：本服务只负责「保活 + 通知」，真正的做题由 [QuizRunManager] 执行。
 * 因此必须先调用 [QuizRunManager.start]，再启动本服务。
 */
class QuizService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            QuizRunManager.stop("用户停止做题")
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // 先占前台，避免 startForegroundService 后 5 秒内没调 startForeground 被系统杀
        startForeground(NOTIFY_ID, buildNotification(QuizRunManager.state.value))
        acquireWakeLock()

        if (!observing) {
            observing = true
            scope.launch {
                QuizRunManager.state.collect { state ->
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "做题", NotificationManager.IMPORTANCE_LOW).apply {
                description = "章节测验进度"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(state: QuizRunManager.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, QuizService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = buildString {
            append(state.status)
            if (state.running && state.questionTotal > 0) {
                append("（第 ").append(state.questionIndex).append("/").append(state.questionTotal).append(" 题）")
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_quiz)
            .setContentTitle("学习通做题")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(state.running)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)

        if (state.running && state.totalCourses > 0) {
            builder.setProgress(100, state.percent.coerceIn(0, 100), false)
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
                "XXTSign:Quiz"
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
        const val ACTION_STOP = "com.xxt.sign.work.QUIZ_STOP"
        private const val CHANNEL = "xxt_quiz"
        private const val NOTIFY_ID = 1003

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, QuizService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, QuizService::class.java))
        }
    }
}
