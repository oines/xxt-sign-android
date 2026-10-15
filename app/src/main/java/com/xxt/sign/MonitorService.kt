package com.xxt.sign

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
import androidx.core.content.ContextCompat
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.sign.SignPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 后台巡检。
 *
 * 定位不是"默默替你签完所有东西"，而是**自动处理能自动的、剩下的弹通知叫人**：
 *
 *  - 零门槛的活动（普通签到、已配好坐标的位置签到、已选图的拍照签到）
 *    → 直接签掉，通知里报结果
 *  - 需要人工的活动（二维码要扫码、手势/签到码要码、人脸）→ 弹通知提醒，
 *    点通知直接回到 App 处理
 *
 * 这样既保住"有签到就弹出来"的价值，又不会悄悄签掉不该签的课。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 已经处理/提醒过的活动，避免反复弹 */
    private val handled = LinkedHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ONGOING, buildOngoing("正在巡检…"))
        if (job == null) {
            running = true
            job = scope.launch { loop() }
            LogStore.add("后台巡检已启动")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        scope.cancel()
        LogStore.add("后台巡检已停止")
        super.onDestroy()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            runCatching { round() }
                .onFailure { LogStore.add("[巡检] 异常：${it.message}", false) }
            delay(Settings.intervalSeconds * 1000L)
        }
    }

    private suspend fun round() {
        val accounts = AccountStore.accounts.value
        if (accounts.isEmpty()) {
            updateOngoing("等待添加账号")
            return
        }
        gcHandled()

        val detected = SignPlanner.detect(accounts, Settings.courseFilter)
        if (detected.isEmpty()) {
            updateOngoing("巡检中 · ${accounts.size} 个账号 · 暂无签到")
            return
        }

        var autoSigned = 0
        val needHuman = mutableListOf<SignPlanner.Detected>()

        for (d in detected) {
            if (d.aid in handled) continue

            if (d.requirement == SignPlanner.Requirement.NONE) {
                // 零门槛：直接签，不需要打扰用户
                val rs = SignPlanner.execute(
                    target = d,
                    accounts = accounts,
                    signCode = d.serverSignCode.takeIf { it.isNotBlank() },
                    photoBytes = photoBytes()
                )
                val ok = rs.count { it.ok }
                handled[d.aid] = System.currentTimeMillis()
                if (ok > 0) autoSigned++
                notifyAlert(
                    title = if (ok > 0) "✅ 已自动签" else "⚠️ 自动签未成功",
                    text = "${d.title}　成功 $ok/${rs.size}",
                    high = true
                )
                LogStore.add("[巡检] ${d.title} 自动签 $ok/${rs.size}", ok > 0)
            } else {
                needHuman.add(d)
            }
        }

        if (needHuman.isNotEmpty()) {
            notifyAlert(
                title = "有 ${needHuman.size} 个签到需要你处理",
                text = needHuman.joinToString("、") { "${it.title}（${whyHuman(it)}）" },
                high = true
            )
            needHuman.forEach { handled[it.aid] = System.currentTimeMillis() }
        }

        updateOngoing(
            when {
                autoSigned > 0 -> "刚刚自动签了 $autoSigned 个活动"
                needHuman.isNotEmpty() -> "有 ${needHuman.size} 个待人工处理"
                else -> "巡检中 · ${detected.size} 个活动"
            }
        )
    }

    private fun whyHuman(d: SignPlanner.Detected): String = when (d.requirement) {
        SignPlanner.Requirement.QR_CODE -> "需扫码"
        SignPlanner.Requirement.SIGN_CODE -> "需签到码"
        SignPlanner.Requirement.LOCATION -> "需位置"
        SignPlanner.Requirement.PHOTO -> "需选图"
        SignPlanner.Requirement.NONE -> "需人工"
    }

    /** handled 表别无限涨：超过 2 小时或超过 200 条就清 */
    private fun gcHandled() {
        val now = System.currentTimeMillis()
        handled.entries.removeAll { now - it.value > 2 * 60 * 60_000L }
        while (handled.size > 200) {
            val k = handled.keys.firstOrNull() ?: break
            handled.remove(k)
        }
    }

    private fun photoBytes(): ByteArray? =
        Settings.photoPath.takeIf { it.isNotBlank() }
            ?.let { p -> runCatching { File(p).readBytes() }.getOrNull() }

    // ------------------------------------------------------------- 通知

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "后台巡检", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻巡检状态"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "签到提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "检测到签到、或自动签到完成"
                enableVibration(true)
            }
        )
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopIntent(): PendingIntent {
        val i = Intent(this, MonitorService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, 1, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildOngoing(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_tab_sign)
            .setContentTitle("学习通签到助手")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, "停止", stopIntent())
            .build()

    private fun updateOngoing(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ONGOING, buildOngoing(text))
    }

    private fun notifyAlert(title: String, text: String, high: Boolean) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_tab_sign)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n)
    }

    companion object {
        const val ACTION_STOP = "com.xxt.sign.STOP"
        private const val CHANNEL_ONGOING = "xxt_monitor"
        private const val CHANNEL_ALERT = "xxt_alert"
        private const val NOTIFY_ONGOING = 1001

        @Volatile
        var running: Boolean = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
            running = false
        }
    }
}
