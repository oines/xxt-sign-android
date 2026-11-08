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
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.sign.SignPlanner
import com.xxt.sign.study.StudyApi
import com.xxt.sign.work.AndroidGlyphSource
import com.xxt.sign.work.AndroidReferenceFonts
import com.xxt.sign.work.AnswerProvider
import com.xxt.sign.work.FontBytes
import com.xxt.sign.work.FontDecoder
import com.xxt.sign.work.LlmClient
import com.xxt.sign.study.CourseAuditStore
import com.xxt.sign.study.CourseAuditor
import com.xxt.sign.study.CourseSweepCursor
import com.xxt.sign.study.StudyManager
import com.xxt.sign.study.StudyPlan
import com.xxt.sign.work.QuizAutoRunner
import com.xxt.sign.work.QuizConfig
import com.xxt.sign.work.QuizEngine
import com.xxt.sign.work.WorkApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** 挂机答题单独跑，别把签到巡检堵住 */
    private var quizJob: Job? = null
    private val quizLock = Mutex()

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
        // 挂机答题是独立任务：按自己的频率触发，不阻塞签到巡检
        maybeStartQuizPatrol()
        // 「全绿」流水线：先体检这门课还差什么，视频缺口排给刷课，测验缺口留给巡检
        maybeSweepCourse()

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
                record(
                    title = d.title,
                    ok = ok > 0,
                    reason = if (ok > 0) "" else (rs.firstOrNull { !it.ok }?.detail ?: "服务端没接受")
                )
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
            needHuman.forEach {
                handled[it.aid] = System.currentTimeMillis()
                record(title = it.title, ok = false, reason = whyHuman(it))
            }
            // 首页那行高亮要倒计时：能算出来就给个截止时间
            val first = needHuman.first()
            publishSignCard(
                title = "要你处理 · ${whyHuman(first)}",
                detail = first.label.ifBlank { first.courseName },
                deadline = deadlineOf(first)
            )
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

    // ------------------------------------------------------ 挂机答题

    /** LLM 只在开关打开且填了 key 时启用 */
    private fun buildLlm(): LlmClient? {
        if (!Settings.quizLlmEnabled) return null
        val key = Settings.quizLlmKey.trim()
        if (key.isEmpty()) return null
        return LlmClient(
            apiKey = key,
            baseUrl = Settings.quizLlmBaseUrl,
            model = Settings.quizLlmModel
        )
    }

    private fun maybeStartQuizPatrol() {
        // 不再有全局开关：只有「有课程在跑」时才做测验巡检
        if (!com.xxt.sign.study.CourseRunQueue.anyActive()) return
        val acc = AccountStore.get(Settings.studyAccountId) ?: return
        if (System.currentTimeMillis() - Settings.quizLastPatrolAt < Settings.quizPatrolMinutes * 60_000L) return
        if (quizJob?.isActive == true) return
        Settings.quizLastPatrolAt = System.currentTimeMillis()
        quizJob = scope.launch {
            quizLock.withLock {
                runCatching { quizPatrol(acc) }
                    .onFailure { LogStore.add("[测验] 巡检异常：${it.message}", false) }
            }
        }
    }

    /** 每轮体检一门课（轮转），把视频缺口排进刷课流水线 */
    private suspend fun maybeSweepCourse() {
        if (!com.xxt.sign.study.CourseRunQueue.anyActive()) return
        if (quizLock.isLocked) return
        if (StudyManager.isRunning()) return
        if (System.currentTimeMillis() - Settings.quizLastPatrolAt < Settings.quizPatrolMinutes * 60_000L) return
        val acc = AccountStore.get(Settings.studyAccountId) ?: return

        val (session, err) = SignEngine.sessionFor(acc)
        if (session == null) {
            LogStore.add("[体检] 登录失败：$err", false)
            return
        }
        val studyApi = StudyApi(session)
        val courses = runCatching { studyApi.getCourses() }.getOrDefault(emptyList())
        if (courses.isEmpty()) return

        val index = CourseSweepCursor.next(acc.id, courses.size)
        val course = courses[index]
        val chapters = runCatching { studyApi.getChapters(course) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) return

        val result = CourseAuditor(studyApi).audit(course, chapters) { updateOngoing("体检：$it") }

        // 写回缓存，首页据此显示"还差几个任务点"
        val merged = CourseAuditStore.load(acc.id).filterNot { it.courseId == course.courseId } + result.audit
        CourseAuditStore.save(acc.id, merged)

        val a = result.audit
        updateOngoing("体检 ${index + 1}/${courses.size} · ${a.headline()}")

        if (result.videoChapters.isEmpty()) {
            if (a.onlyHumanLeft || a.allGreen) notifyAlert(a.headline(), "自动能做的都做完了，剩下要你本人处理", high = false)
            return
        }
        if (Settings.quizStoreOnly) {
            LogStore.add("[体检] ${a.headline()}；「只暂存」开着，先不动视频", true)
            return
        }
        // 视频缺口 → 排给刷课流水线（刷完下一轮体检会重新数，测验由巡检接着做）
        StudyManager.plan = StudyPlan(
            accountId = acc.id,
            courses = listOf(course),
            chapterSelection = mapOf(course.key to result.videoChapters.map { it.id }.toSet()),
            concurrency = Settings.studyConcurrency,
            speed = Settings.studySpeed
        )
        LogStore.add("[体检] ${a.headline()}；开始刷 ${result.videoChapters.size} 章视频", true)
        StudyManager.start()
    }

    private suspend fun quizPatrol(acc: com.xxt.sign.core.Account) {
        val (session, err) = SignEngine.sessionFor(acc)
        if (session == null) {
            LogStore.add("[测验] 登录失败：$err", false)
            return
        }
        val studyApi = StudyApi(session)
        val courses = runCatching { studyApi.getCourses() }.getOrDefault(emptyList())
        if (courses.isEmpty()) return

        val config = QuizConfig(
            storeOnly = Settings.quizStoreOnly,
            perQuestionMinSec = Settings.quizPerQuestionMin,
            perQuestionMaxSec = Settings.quizPerQuestionMax,
            submitWaitMinSec = Settings.quizSubmitWaitMin,
            submitWaitMaxSec = Settings.quizSubmitWaitMax,
            gapSeconds = Settings.quizGapSeconds,
            fontThreshold = Settings.quizFontThreshold,
            hitThreshold = Settings.quizHitThreshold
        )
        val engine = QuizEngine(
            api = WorkApi(session),
            answers = AnswerProvider(
                provider = Settings.quizProvider,
                endpoint = Settings.quizEndpoint,
                token = Settings.quizToken,
                llm = buildLlm()
            ),
            config = config,
            decoderFactory = { b64 ->
                FontBytes.decode(b64)?.let { bytes ->
                    FontDecoder(
                        source = AndroidGlyphSource(this, bytes),
                        references = AndroidReferenceFonts.list(this),
                        size = 16,
                        fixedThreshold = Settings.quizFontThreshold
                    )
                }
            },
            log = { msg, ok -> LogStore.add(msg, ok) }
        )
        val runner = QuizAutoRunner(acc.id, studyApi, WorkApi(session), engine)
        val pass = runner.patrol(courses)
        if (pass.empty) return

        val lines = pass.results.map { r ->
            val head = r.courseTitle.ifBlank { "课程" } + " · " + r.title
            when {
                r.needsHuman -> "$head：需人工（${r.message}）"
                r.submitted -> {
                    val score = if (r.score.isBlank()) "" else "${r.score} 分｜"
                    val low = if (r.isLowConfidence(Settings.quizHitThreshold)) "（低置信，建议回看）" else ""
                    "$head：$score 答上 ${r.answered}/${r.total} 题$low"
                }
                else -> "$head：${r.message}"
            }
        }
        val submitted = pass.results.count { it.submitted }
        val bank = pass.results.sumOf { it.bankHits }
        val byLlm = pass.results.sumOf { it.llmHits }
        val blank = pass.results.sumOf { it.blanks }
        val title = when {
            pass.pendingHuman > 0 -> "章节测验：$submitted 份已交，${pass.pendingHuman} 份需人工"
            submitted > 0 -> "章节测验：已自动交 $submitted 份"
            else -> "章节测验：${pass.results.size} 份没交上"
        }
        val summary = "答上 ${bank + byLlm} 题 · $blank 题没把握"
        notifyAlert(title, (listOf(summary) + lines).joinToString("\n"), high = pass.pendingHuman > 0)
    }

    private fun buildOngoing(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_tab_sign)
            .setContentTitle("学习通助手")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, "停止", stopIntent())
            .build()

    private fun updateOngoing(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ONGOING, buildOngoing(text))
    }

    /** 签到记录：只写事实，页面只读 */
    private fun record(title: String, ok: Boolean, reason: String) {
        runCatching {
            com.xxt.sign.core.SignRecordStore.add(
                this,
                com.xxt.sign.core.SignRecord(
                    title = title,
                    at = System.currentTimeMillis(),
                    ok = ok,
                    reason = reason
                )
            )
        }
    }

    /** 签到动态：能自动签的只留一行结果，签不了的才升级成「要你本人」 */
    private fun publishSignCard(title: String, detail: String, deadline: Long = 0L) {
        runCatching { com.xxt.sign.core.SignEvents.publish(this, title, detail, deadline) }
    }

    /** 活动结束时间：字段名不确定，几个常见的都试一下 */
    private fun deadlineOf(d: SignPlanner.Detected): Long {
        val detail = d.detail
        val keys = listOf("endTime", "endtime", "signEndTime", "endTimeStr")
        for (k in keys) {
            val raw = detail.optString(k)
            if (raw.isBlank()) continue
            raw.toLongOrNull()?.let { ms ->
                val norm = if (ms < 10_000_000_000L) ms * 1000 else ms
                if (norm > System.currentTimeMillis()) return norm
            }
            runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                fmt.parse(raw)?.time?.takeIf { it > System.currentTimeMillis() }?.let { return it }
            }
        }
        return System.currentTimeMillis() + 30 * 60_000L
    }

    private fun notifyAlert(title: String, text: String, high: Boolean) {
        if (high) publishSignCard(title, text.lineSequence().firstOrNull().orEmpty())
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
