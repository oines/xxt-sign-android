package com.xxt.sign.study

import com.xxt.sign.App
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.work.AnswerProvider
import com.xxt.sign.work.FontBytes
import com.xxt.sign.work.FontDecoder
import com.xxt.sign.work.LlmClient
import com.xxt.sign.work.AndroidGlyphSource
import com.xxt.sign.work.AndroidReferenceFonts
import com.xxt.sign.work.QuizAutoRunner
import com.xxt.sign.work.QuizEngine
import com.xxt.sign.work.WorkApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按课程跑：一门课点了「开始」，就把这门课能自动的都做完。
 *
 * 为什么是队列：多门课同时跑等于同时打十几个请求，风控第一个抓的就是这个。
 * 所以**串行，并发 1**，后点的排队等前面的跑完。
 *
 * 顺序：体检（还差什么）→ 文档任务点（一次 GET 就完成）→ 视频 → 章节测验 → 再体检。
 *
 * 流水线两条铁律（真机踩出来的）：
 *  - **每个阶段都要能被超时打断**：单项 15s、整段有预算，超时就跳过并写原因，
 *    绝不能让用户看到「转圈四分钟、一条日志都没有」。
 *  - **阶段之间不互相等**：文档没活就直接过，视频跑完就进测验，测验完就重新体检。
 */
object CourseRunQueue {

    enum class Phase { IDLE, QUEUED, AUDIT, DOCUMENT, VIDEO, QUIZ, DONE, STUCK }

    data class State(
        val courseKey: String,
        val title: String,
        val phase: Phase = Phase.IDLE,
        /** 人话状态，界面直接用 */
        val message: String = "没在跑",
        /** 已完成/总任务点（按带 jobid 的 attachment 口径） */
        val donePoints: Int = 0,
        val totalPoints: Int = 0,
        /** 正在跑的那一章（课程页列表高亮用） */
        val currentChapter: String = "",
        /** 正在跑第几章 / 共几章（通知文案「第 N 章」用；0 表示没数出来） */
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        /** 需要你本人做的事，空 = 没有 */
        val needsYou: String = ""
    ) {
        val running: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.STUCK
        val ratio: Float get() = if (totalPoints <= 0) 0f else donePoints.toFloat() / totalPoints
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val queue = ArrayDeque<String>()
    private val courses = mutableMapOf<String, StudyCourse>()

    /** 用户点了暂停的课：跑完的收尾不允许再把「已暂停」覆写成「这一轮跑完了」 */
    private val pausedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 每门课当前的运行 Job，暂停时精准取消这一门，不影响队列里其它课 */
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private var scope: CoroutineScope? = null
    private var worker: kotlinx.coroutines.Job? = null
    private val busy = AtomicBoolean(false)

    /** 单项（一个文档任务点）最多等多久，超时跳过 */
    private const val PER_ITEM_TIMEOUT_MS = 15_000L

    /** 整段文档阶段的预算：到点就带着进度往下走，不阻塞视频和测验 */
    private const val DOC_STAGE_BUDGET_MS = 10 * 60 * 1000L

    /** 视频阶段心跳：每这么久刷一次状态行，让用户知道它没死 */
    private const val VIDEO_HEARTBEAT_MS = 5_000L

    fun stateOf(courseKey: String): State? = _states.value[courseKey]

    fun isRunning(courseKey: String): Boolean = stateOf(courseKey)?.running == true

    /** 有没有任何课程在跑/排队：签到巡检跟着这个决定要不要常驻 */
    fun anyActive(): Boolean = _states.value.values.any { it.running || it.phase == Phase.QUEUED }

    /** 开始 / 暂停。按钮只有这两种含义。 */
    fun toggle(course: StudyCourse) {
        if (isRunning(course.key) || queue.contains(course.key)) {
            pause(course.key)
        } else {
            start(course)
        }
    }

    fun start(course: StudyCourse) {
        val account = AccountStore.get(Settings.studyAccountId) ?: return
        courses[course.key] = course
        pausedKeys.remove(course.key)
        if (queue.contains(course.key)) return
        queue.addLast(course.key)
        update(course.key) {
            it.copy(
                courseKey = course.key,
                title = course.title,
                phase = Phase.QUEUED,
                message = if (busy.get()) "排在这门课后面" else "准备开始"
            )
        }
        // 开前台服务：拿 PARTIAL_WAKE_LOCK + 常驻通知（锁屏/退后台也能继续刷）
        runCatching { StudyService.start(App.ctx) }
        ensureRunner(account.id)
    }

    fun pause(courseKey: String) {
        queue.remove(courseKey)
        pausedKeys.add(courseKey)
        StudyManager.stop("已暂停")
        runningJobs.remove(courseKey)?.cancel()
        // 暂停只说暂停：还差多少算给人看，不写成「这一轮跑完了」
        val st = _states.value[courseKey]
        val left = if (st != null && st.totalPoints > 0) {
            (st.totalPoints - st.donePoints).coerceAtLeast(0)
        } else {
            -1
        }
        val msg = if (left >= 0) "已暂停，还差 $left 个任务点" else "已暂停"
        update(courseKey) {
            it.copy(
                phase = Phase.IDLE, message = msg,
                currentChapter = "", chapterIndex = 0, chapterCount = 0
            )
        }
        LogStore.add("[课程] ${_states.value[courseKey]?.title.orEmpty()}：$msg", true)
        if (queue.isEmpty()) {
            stopRunner()
            runCatching { StudyService.stop(App.ctx) }
        }
    }

    /** 全局停下（App 退出、用户清理时用） */
    fun pauseAll() {
        queue.clear()
        pausedKeys.addAll(_states.value.keys)
        StudyManager.stop("已暂停")
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        _states.value = _states.value.mapValues { (_, s) ->
            if (s.running || s.phase == Phase.QUEUED) {
                val left = if (s.totalPoints > 0) (s.totalPoints - s.donePoints).coerceAtLeast(0) else -1
                s.copy(
                    phase = Phase.IDLE,
                    message = if (left >= 0) "已暂停，还差 $left 个任务点" else "已暂停",
                    currentChapter = "", chapterIndex = 0, chapterCount = 0
                )
            } else {
                s
            }
        }
        stopRunner()
        runCatching { StudyService.stop(App.ctx) }
    }

    private fun ensureRunner(accountId: String) {
        if (worker?.isActive == true) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        worker = newScope.launch {
            while (queue.isNotEmpty()) {
                val key = queue.removeFirstOrNull() ?: break
                val course = courses[key] ?: continue
                if (pausedKeys.contains(key)) continue
                busy.set(true)
                // 每门课单独一个 Job：暂停时只取消这一门，不弄死整条队列
                val runJob = newScope.launch {
                    runCatching { runOne(accountId, course) }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException || pausedKeys.contains(key)) {
                                return@onFailure
                            }
                            LogStore.add("[课程] ${course.title} 出错：${e.message}", false)
                            update(key) { s -> s.copy(phase = Phase.STUCK, message = "出错了，点开始再跑一次") }
                        }
                }
                runningJobs[key] = runJob
                runJob.join()
                runningJobs.remove(key)
                busy.set(false)
                if (!pausedKeys.contains(key)) {
                    StudyManager.stop("这门的视频刷完了")
                }
            }
            stopRunner()
        }
    }

    private fun stopRunner() {
        scope?.cancel()
        scope = null
        worker = null
        busy.set(false)
        // 队列空了就结束前台服务，唤醒锁跟着释放，不要长期占着
        runCatching { StudyService.stop(App.ctx) }
    }

    private suspend fun runOne(accountId: String, course: StudyCourse) {
        val key = course.key
        val account = AccountStore.get(accountId) ?: return
        update(key) { it.copy(phase = Phase.AUDIT, message = "正在看看还差什么", currentChapter = "") }

        val (session, err) = SignEngine.sessionFor(account)
        if (session == null) {
            update(key) { it.copy(phase = Phase.STUCK, message = "登录失效了，去账号页重新登录", needsYou = err) }
            return
        }
        val api = StudyApi(session)
        val page = runCatching { api.getChapterPage(course) }.getOrNull()
        val chapters = page?.chapters.orEmpty()
        if (chapters.isEmpty()) {
            update(key) { it.copy(phase = Phase.STUCK, message = "没读到章节，稍后再点开始") }
            return
        }
        // 平台自报的任务点总数（真机上是 392）；体检分母优先用它
        val tpDone = page?.donePoints ?: -1
        val tpTotal = page?.totalPoints ?: -1

        // 1) 体检：知道这门课还差几个任务点（同时拿到待做文档、视频章、测验章）
        val audit = CourseAuditor(api).audit(course, chapters, tpDone, tpTotal) { msg ->
            update(key) { s -> s.copy(message = msg) }
        }
        update(key) {
            it.copy(
                donePoints = audit.audit.doneTaskPoints,
                totalPoints = audit.audit.taskPoints,
                needsYou = humanText(audit.audit)
            )
        }
        // 体检完立刻落盘：课程页/首页不等整轮跑完就能拿到这一轮的行和任务点数
        // （真机上出现过「页面 210/392、行却是上一轮的 v0」——只因为写到盘的时候已经晚了）
        runCatching {
            val mergedNow = CourseAuditStore.load(accountId)
                .filterNot { it.courseId == course.courseId } + audit.audit
            CourseAuditStore.save(accountId, mergedNow)
        }

        // 2) 文档任务点：目标带着 jtoken 直接从体检结果来，不再翻一遍 cards 页
        if (Settings.studyDocuments) {
            runDocuments(key, course, api, audit.documents)
        } else {
            LogStore.add("[文档] 设置里关了文档任务点，跳过", true)
        }

        // 3) 视频：交给现有刷课引擎（并发按设置，默认 1），带心跳
        // 只用体检结论：体检说没有视频缺口，就是真的没有，不要拿「未完成章节」再猜一遍
        val videoChapters = audit.videoChapters
        if (videoChapters.isNotEmpty()) {
            update(key) {
                it.copy(phase = Phase.VIDEO, message = "正在刷视频 0/${videoChapters.size} 章")
            }
            LogStore.add("[课程] ${course.title}：开始刷 ${videoChapters.size} 章视频", true)
            StudyManager.plan = StudyPlan(
                accountId = accountId,
                courses = listOf(course),
                chapterSelection = mapOf(course.key to videoChapters.map { it.id }.toSet()),
                concurrency = Settings.studyConcurrency,
                speed = Settings.studySpeed
            )
            StudyManager.start()
            waitForVideo(key, course)
        } else {
            LogStore.add("[课程] ${course.title}：没有待刷视频，跳过", true)
        }

        // 4) 章节测验（只有 workA；其它 worktype 不猜，留给体检报出来）
        update(key) { it.copy(phase = Phase.QUIZ, message = "正在做章节测验", currentChapter = "") }
        if (audit.quizChapters.isEmpty()) {
            LogStore.add("[测验] ${course.title}：没有待做测验，跳过", true)
        } else {
            LogStore.add(
                "[测验] ${course.title}：${audit.quizChapters.size} 章共 ${audit.audit.quizPending} 份 workA 测验要交",
                true
            )
            runCatching { runQuiz(account, course, api) }
                .onFailure { LogStore.add("[测验] ${course.title} 测验阶段出错：${it.message}", false) }
        }

        // 5) 收尾：再体检一次，把「还差什么」刷新到最新（用户验收就看这个数）
        suspend fun auditOnce(): CourseAudit? = runCatching {
            val fresh = api.getChapterPage(course)
            CourseAuditor(api).audit(course, fresh.chapters, fresh.donePoints, fresh.totalPoints) {}
        }.getOrNull()?.audit

        var latest = auditOnce() ?: audit.audit
        if (pausedKeys.contains(key)) return
        // 真机 197 vs 平台 203：刚跑完就体检，最后几个视频的完成可能还没回填。
        // 隔 5 秒再补一次，取更好的那个（只补一次，不轮询）。
        if (latest.doneTaskPoints < latest.taskPoints) {
            delay(5_000)
            if (pausedKeys.contains(key)) return
            val again = auditOnce()
            if (again != null && again.doneTaskPoints > latest.doneTaskPoints) {
                LogStore.add(
                    "[体检] 跑完补一次：已完成 ${latest.doneTaskPoints} → ${again.doneTaskPoints}" +
                        "/${again.taskPoints}（等 5 秒让服务端回填）",
                    true
                )
                latest = again
            }
        }
        val merged = CourseAuditStore.load(accountId).filterNot { it.courseId == course.courseId } + latest
        CourseAuditStore.save(accountId, merged)

        // 暂停后不允许再用「这一轮跑完了」覆盖「已暂停」
        if (pausedKeys.contains(key)) return

        update(key) {
            it.copy(
                phase = if (latest.allGreen) Phase.DONE else Phase.STUCK,
                donePoints = latest.doneTaskPoints,
                totalPoints = latest.taskPoints,
                needsYou = humanText(latest),
                currentChapter = "",
                message = when {
                    latest.allGreen && latest.humanPending == 0 -> "这门课全绿了（${latest.doneTaskPoints}/${latest.taskPoints} 任务点）"
                    latest.allGreen -> "它能做的都做完了，还有 ${latest.humanPending} 个任务点要你本人"
                    else -> "这一轮跑完了，还差 ${latest.remaining} 个任务点（视频 ${latest.videoPending} · 测验 ${latest.quizPending}）"
                }
            )
        }
        LogStore.add("[课程] ${course.title}：${_states.value[key]?.message.orEmpty()}", latest.allGreen)
    }

    /**
     * 文档阶段。
     *
     * 每个任务点：开始/结果都写日志，单项 15s 超时就跳过，整段最多 [DOC_STAGE_BUDGET_MS]。
     * 一条待办都没有时立刻往下走 —— 之前这里会对着空的卡片页翻 61 页 × 91 章，
     * 用户看到的就是「正在把文档任务点做掉」卡四分钟、零日志。
     */
    private suspend fun runDocuments(
        key: String,
        course: StudyCourse,
        api: StudyApi,
        targets: List<CourseAuditor.DocTarget>
    ) {
        if (targets.isEmpty()) {
            LogStore.add("[文档] ${course.title}：没有待办文档，直接进下一步", true)
            update(key) { it.copy(message = "没有待办文档，直接进下一步") }
            return
        }
        update(key) { it.copy(phase = Phase.DOCUMENT, message = "正在把文档任务点做掉 0/${targets.size}") }
        LogStore.add("[文档] ${course.title}：待办 ${targets.size} 个，开始", true)

        var done = 0
        var already = 0
        var failed = 0
        var timedOut = 0
        val startedAt = System.currentTimeMillis()

        for ((i, t) in targets.withIndex()) {
            if (System.currentTimeMillis() - startedAt > DOC_STAGE_BUDGET_MS) {
                val rest = targets.size - i
                LogStore.add("[文档] 整段超预算（${DOC_STAGE_BUDGET_MS / 60000} 分钟），跳过剩下 $rest 个，继续下一步", false)
                break
            }
            val tag = "${i + 1}/${targets.size}"
            update(key) {
                it.copy(
                    phase = Phase.DOCUMENT,
                    message = "正在把文档任务点做掉 $tag · ${t.job.name}",
                    currentChapter = t.chapter.title,
                    chapterIndex = i + 1,
                    chapterCount = targets.size
                )
            }
            LogStore.add("[文档] $tag 开始：${t.chapter.title} · ${t.job.name}", true)
            val r = withTimeoutOrNull(PER_ITEM_TIMEOUT_MS) { api.completeDocument(course, t.chapter, t.job) }
            when (r) {
                null -> {
                    timedOut++
                    LogStore.add("[文档] $tag 超时 ${PER_ITEM_TIMEOUT_MS / 1000}s，跳过：${t.job.name}", false)
                }
                DocumentResult.DONE -> {
                    done++
                    LogStore.add("[文档] $tag 完成：${t.job.name}", true)
                }
                DocumentResult.ALREADY -> {
                    already++
                    LogStore.add("[文档] $tag 早已完成：${t.job.name}", true)
                }
                DocumentResult.FAILED -> {
                    failed++
                    LogStore.add("[文档] $tag 失败：${t.job.name}", false)
                }
            }
            if ((i + 1) % 10 == 0 || i == targets.lastIndex) {
                LogStore.add(
                    "[文档] 进度 ${i + 1}/${targets.size}：完成 $done · 早已完成 $already · 失败 $failed · 超时 $timedOut",
                    failed == 0 && timedOut == 0
                )
            }
        }

        LogStore.add(
            "[文档] ${course.title} 阶段结束：完成 $done · 早已完成 $already · 失败 $failed · 超时 $timedOut" +
                "（用时 ${(System.currentTimeMillis() - startedAt) / 1000}s）",
            failed == 0 && timedOut == 0
        )
        update(key) { it.copy(message = "文档：完成 $done，早已完成 $already，失败 $failed，超时 $timedOut") }
    }

    /** 视频阶段：不轮询等待空转，只做心跳，同时保证编解码器停了能自己退出 */
    private suspend fun waitForVideo(key: String, course: StudyCourse) {
        var lastLog = 0L
        while (StudyManager.isRunning()) {
            val st = StudyManager.state.value
            // StudyState.current 是「课程 · 章名」，拆出章名给逐章列表高亮用
            val chapterTitle = st.current.removePrefix("${course.title} · ").takeIf { it != st.current }.orEmpty()
            // 章节数没算出来之前不显示 0/0（真机上先闪「0/0」再变「14/90」，看着像坏了）
            val head = if (st.totalChapters <= 0) {
                "正在刷视频 · 正在数章节"
            } else {
                "正在刷视频 ${st.doneChapters}/${st.totalChapters}"
            }
            val now = System.currentTimeMillis()
            update(key) {
                it.copy(
                    phase = Phase.VIDEO,
                    message = listOf(head, chapterTitle).filter { s -> s.isNotBlank() }.joinToString(" · "),
                    currentChapter = chapterTitle,
                    chapterIndex = st.doneChapters,
                    chapterCount = st.totalChapters
                )
            }
            if (now - lastLog > 120_000) {
                lastLog = now
                LogStore.add("[课程] $head${if (st.current.isBlank()) "" else " · ${st.current}"}", true)
            }
            delay(VIDEO_HEARTBEAT_MS)
        }
        val st = StudyManager.state.value
        LogStore.add("[课程] 视频阶段结束：${st.status}（${st.doneChapters}/${st.totalChapters} 章）", st.failedTasks == 0)
    }

    private suspend fun runQuiz(
        account: com.xxt.sign.core.Account,
        course: StudyCourse,
        studyApi: StudyApi
    ) {
        val session = studyApi.cx
        val engine = QuizEngine(
            api = WorkApi(session),
            answers = AnswerProvider(
                provider = Settings.quizProvider,
                endpoint = Settings.quizEndpoint,
                token = Settings.quizToken,
                llm = buildLlm()
            ),
            config = com.xxt.sign.work.QuizConfig(
                storeOnly = Settings.quizStoreOnly,
                perQuestionMinSec = Settings.quizPerQuestionMin,
                perQuestionMaxSec = Settings.quizPerQuestionMax,
                submitWaitMinSec = Settings.quizSubmitWaitMin,
                submitWaitMaxSec = Settings.quizSubmitWaitMax,
                gapSeconds = Settings.quizGapSeconds,
                fontThreshold = Settings.quizFontThreshold,
                hitThreshold = Settings.quizHitThreshold
            ),
            decoderFactory = { b64 ->
                FontBytes.decode(b64)?.let { bytes ->
                    FontDecoder(
                        source = AndroidGlyphSource(com.xxt.sign.App.ctx, bytes),
                        references = AndroidReferenceFonts.list(com.xxt.sign.App.ctx),
                        size = 16,
                        fixedThreshold = Settings.quizFontThreshold
                    )
                }
            },
            log = { msg, ok -> LogStore.add(msg, ok) }
        )
        val runner = QuizAutoRunner(account.id, studyApi, WorkApi(session), engine)
        val pass = runner.patrol(listOf(course), maxCourses = 1, maxQuizzes = 80, maxChaptersPerCourse = CourseAuditor.MAX_CHAPTERS)
        if (pass.results.isNotEmpty()) {
            val submitted = pass.results.count { it.submitted }
            val human = pass.pendingHuman
            LogStore.add(
                "[课程] ${course.title}：本轮提交 $submitted 份测验" + if (human > 0) "，$human 份需人工" else "",
                submitted > 0 && human == 0
            )
        }
    }

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

    /**
     * 「需要你本人」只用一句人话，别把任务点/接口名漏给用户。
     * 也不再断言「作业或考试」—— 类型是 worktype 决定的，没确认过的类型不猜。
     */
    private fun humanText(audit: CourseAudit): String = when {
        audit.humanPending <= 0 -> ""
        else -> {
            val gap = audit.gaps.firstOrNull { it.kind == CourseAudit.Kind.HUMAN }
            when {
                gap == null -> "有 ${audit.humanPending} 个任务点要你本人处理"
                gap.detail.contains("未开放") -> "有一章还没开放，开了再跑"
                else -> "${gap.chapterTitle}：${audit.humanPending} 个任务点它不代做，得你自己来"
            }
        }
    }

    private fun update(key: String, block: (State) -> State) {
        val current = _states.value[key] ?: State(key, "")
        _states.value = _states.value + (key to block(current))
    }
}
