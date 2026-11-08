package com.xxt.sign.study

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按课程跑：一门课点了「开始」，就把这门课能自动的都做完。
 *
 * 为什么是队列：多门课同时跑等于同时打十几个请求，风控第一个抓的就是这个。
 * 所以**串行，并发 1**，后点的排队等前面的跑完。
 *
 * 顺序：体检（还差什么）→ 文档任务点（一次 GET 就完成）→ 视频 → 章节测验。
 * 用户只看得到三句话：正在刷视频 / 正在做测验 / 等你到教室签个到。
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
    private var scope: CoroutineScope? = null
    private var worker: kotlinx.coroutines.Job? = null
    private val busy = AtomicBoolean(false)

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
        ensureRunner(account.id)
    }

    fun pause(courseKey: String) {
        queue.remove(courseKey)
        StudyManager.stop("已暂停")
        update(courseKey) { it.copy(phase = Phase.IDLE, message = "已暂停") }
        LogStore.add("[课程] ${_states.value[courseKey]?.title.orEmpty()}：已暂停", true)
        if (queue.isEmpty()) stopRunner()
    }

    /** 全局停下（App 退出、用户清理时用） */
    fun pauseAll() {
        queue.clear()
        StudyManager.stop("已暂停")
        _states.value = _states.value.mapValues { (_, s) ->
            if (s.running || s.phase == Phase.QUEUED) s.copy(phase = Phase.IDLE, message = "已暂停") else s
        }
        stopRunner()
    }

    private fun ensureRunner(accountId: String) {
        if (worker?.isActive == true) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        worker = newScope.launch {
            while (queue.isNotEmpty()) {
                val key = queue.removeFirstOrNull() ?: break
                val course = courses[key] ?: continue
                busy.set(true)
                runCatching { runOne(accountId, course) }
                    .onFailure {
                        LogStore.add("[课程] ${course.title} 出错：${it.message}", false)
                        update(key) { s -> s.copy(phase = Phase.STUCK, message = "出错了，点开始再跑一次") }
                    }
                busy.set(false)
                StudyManager.stop("这门的视频刷完了")
            }
            stopRunner()
        }
    }

    private fun stopRunner() {
        scope?.cancel()
        scope = null
        worker = null
        busy.set(false)
    }

    private suspend fun runOne(accountId: String, course: StudyCourse) {
        val key = course.key
        val account = AccountStore.get(accountId) ?: return
        update(key) { it.copy(phase = Phase.AUDIT, message = "正在看看还差什么") }

        val (session, err) = SignEngine.sessionFor(account)
        if (session == null) {
            update(key) { it.copy(phase = Phase.STUCK, message = "登录失效了，去账号页重新登录", needsYou = err) }
            return
        }
        val api = StudyApi(session)
        val chapters = runCatching { api.getChapters(course) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            update(key) { it.copy(phase = Phase.STUCK, message = "没读到章节，稍后再点开始") }
            return
        }

        // 1) 体检：知道这门课还差几个任务点
        val audit = CourseAuditor(api).audit(course, chapters) { update(key) { s -> s.copy(message = it) } }
        update(key) {
            it.copy(
                donePoints = audit.audit.doneTaskPoints,
                totalPoints = audit.audit.taskPoints,
                needsYou = humanText(audit.audit)
            )
        }

        // 2) 文档任务点：一次 GET 就完成，先捡这些便宜
        if (Settings.studyDocuments) {
            update(key) { it.copy(phase = Phase.DOCUMENT, message = "正在把文档任务点做掉") }
            val docs = chapters.filterNot { it.finished }
            var done = 0
            for (ch in docs) {
                val jobs = runCatching { api.getDocumentJobs(course, ch) }.getOrDefault(emptyList())
                for (job in jobs) {
                    if (runCatching { api.completeDocument(course, ch, job) }.getOrDefault(false)) done++
                }
            }
            if (done > 0) LogStore.add("[课程] ${course.title}：完成 $done 个文档任务点", true)
        }

        // 3) 视频：交给现有刷课引擎（并发按设置，默认 1）
        val videoChapters = audit.videoChapters.ifEmpty { chapters.filter { !it.finished } }
        if (videoChapters.isNotEmpty()) {
            update(key) { it.copy(phase = Phase.VIDEO, message = "正在刷视频") }
            StudyManager.plan = StudyPlan(
                accountId = accountId,
                courses = listOf(course),
                chapterSelection = mapOf(course.key to videoChapters.map { it.id }.toSet()),
                concurrency = Settings.studyConcurrency,
                speed = Settings.studySpeed
            )
            StudyManager.start()
            // 等这一门的视频跑完（串行队列就是这样）
            while (StudyManager.isRunning()) {
                kotlinx.coroutines.delay(1000)
            }
        }

        // 4) 章节测验
        update(key) { it.copy(phase = Phase.QUIZ, message = "正在做测验") }
        runCatching { runQuiz(account, course, api) }

        // 5) 收尾：再体检一次，把「还差什么」刷新到最新
        val after = runCatching {
            CourseAuditor(api).audit(course, chapters) {}
        }.getOrNull()
        val latest = after?.audit ?: audit.audit
        val merged = CourseAuditStore.load(accountId).filterNot { it.courseId == course.courseId } + latest
        CourseAuditStore.save(accountId, merged)

        update(key) {
            it.copy(
                phase = if (latest.allGreen) Phase.DONE else Phase.STUCK,
                donePoints = latest.doneTaskPoints,
                totalPoints = latest.taskPoints,
                needsYou = humanText(latest),
                message = when {
                    latest.allGreen && latest.humanPending == 0 -> "这门课全绿了"
                    latest.allGreen -> "它能做的都做完了，剩下要你本人"
                    else -> "这一轮跑完了，还差 ${latest.remaining} 个任务点"
                }
            )
        }
        LogStore.add("[课程] ${course.title}：${_states.value[key]?.message.orEmpty()}", latest.allGreen)
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
        val pass = runner.patrol(listOf(course), maxCourses = 1, maxQuizzes = 8, maxChaptersPerCourse = 40)
        if (pass.results.isNotEmpty()) {
            val submitted = pass.results.count { it.submitted }
            LogStore.add("[课程] ${course.title}：本轮提交 $submitted 份测验", submitted > 0)
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

    /** 「需要你本人」只用一句人话，别把任务点/接口名漏给用户 */
    private fun humanText(audit: CourseAudit): String = when {
        audit.humanPending <= 0 -> ""
        else -> {
            val gap = audit.gaps.firstOrNull { it.kind == CourseAudit.Kind.HUMAN }
            when {
                gap == null -> "有 ${audit.humanPending} 处要你本人处理"
                gap.detail.contains("未开放") -> "有一章还没开放，开了再跑"
                else -> "${gap.chapterTitle}：作业或考试要你自己做"
            }
        }
    }

    private fun update(key: String, block: (State) -> State) {
        val current = _states.value[key] ?: State(key, "")
        _states.value = _states.value + (key to block(current))
    }
}
