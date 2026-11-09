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
import com.xxt.sign.App
import com.xxt.sign.MainActivity
import com.xxt.sign.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 刷课前台服务：课程运行期间把进程钉在前台，并持有一把 PARTIAL_WAKE_LOCK。
 *
 * 为什么必须有它（真机取证：`dumpsys power` 里 `Wake Locks: size=0`）：
 * 用户一锁屏，CPU 进休眠，协程和 WebView 一起停，视频就卡在那儿 ——
 * 「丢给它就不管」直接做不成。
 *
 * 约定：
 *  - **只在有课程在跑/排队时持锁**，暂停、跑完、队列空 → 立刻释放（`releaseWakeLock`）
 *  - 通知文案就一句人话：「正在刷《课名》· 第 N 章 / 状态」，锁屏也能一眼看到在干活
 *  - 用户点「停止」= 暂停整条队列（CourseRunQueue.pauseAll），不是只停服务
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
            CourseRunQueue.pauseAll()
            releaseWakeLock()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val state = activeState()
        startForeground(NOTIFY_ID, buildNotification(state))
        if (state != null) acquireWakeLock() else releaseWakeLock()

        if (!observing) {
            observing = true
            scope.launch {
                CourseRunQueue.states.collect { states ->
                    val active = states.values.firstOrNull { it.running }
                        ?: states.values.firstOrNull { it.phase == CourseRunQueue.Phase.QUEUED }
                    if (active == null) {
                        // 没有课程在跑：锁立刻放掉，别长期占着 CPU
                        releaseWakeLock()
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        acquireWakeLock()
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFY_ID, buildNotification(active))
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

    private fun activeState(): CourseRunQueue.State? =
        CourseRunQueue.states.value.values.firstOrNull { it.running }
            ?: CourseRunQueue.states.value.values.firstOrNull { it.phase == CourseRunQueue.Phase.QUEUED }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "刷课", NotificationManager.IMPORTANCE_LOW).apply {
                description = "课程运行进度（正在刷哪一章）"
                setShowBadge(false)
            }
        )
    }

    /** 「正在刷《课名》· 第 N 章」；章数还没数出来就不写第几章，绝不摆 0/0 */
    private fun titleOf(state: CourseRunQueue.State): String {
        val course = state.title.ifBlank { "课程" }
        val chapter = when {
            state.currentChapter.isNotBlank() -> " · ${state.currentChapter}"
            state.chapterIndex > 0 && state.chapterCount > 0 ->
                " · 第 ${state.chapterIndex}/${state.chapterCount} 章"
            else -> ""
        }
        return "正在刷《$course》$chapter"
    }

    private fun buildNotification(state: CourseRunQueue.State?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StudyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = state?.let { titleOf(it) } ?: "学习通刷课"
        val detail = state?.let { s ->
            listOf(
                s.message.takeIf { it.isNotBlank() && it != "没在跑" },
                if (s.totalPoints > 0) "任务点 ${s.donePoints}/${s.totalPoints}" else null
            ).filterNotNull().joinToString(" · ")
        }.orEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_study)
            .setContentTitle(title)
            .setContentText(detail.ifBlank { "准备开始" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.ifBlank { "准备开始" }))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)

        if (state != null && state.totalPoints > 0) {
            builder.setProgress(100, (state.ratio * 100).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
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

    /** PARTIAL_WAKE_LOCK：只保 CPU，不亮屏；标签带包名 + 用途，dumpsys power 里一眼能认出来 */
    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:course-run"
            ).apply {
                setReferenceCounted(false)
                // 上限 6 小时：就算异常没走到释放，也不会永久占着
                acquire(6 * 60 * 60 * 1000L)
            }
            com.xxt.sign.core.LogStore.add("[课程] 已获取唤醒锁（锁屏后继续刷）", true)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                com.xxt.sign.core.LogStore.add("[课程] 已释放唤醒锁", true)
            }
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "com.xxt.sign.study.STOP"
        private const val CHANNEL = "xxt_study"
        private const val NOTIFY_ID = 1002

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, StudyService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, StudyService::class.java)) }
        }
    }
}
