package com.xxt.sign.work

import com.xxt.sign.App
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyCheckStore
import com.xxt.sign.study.StudyCourse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 「做题」页的独立运行器：只扫章节测验，不碰视频。
 *
 * 和刷课完全分开，复用 [QuizAutoRunner] 的扫描/去重/交卷逻辑，但由用户手动选择课程。
 */
object QuizRunManager {

    data class State(
        val running: Boolean = false,
        val finished: Boolean = false,
        val status: String = "就绪",
        val currentCourse: String = "",
        val currentCourseKey: String = "",
        val currentChapter: String = "",
        val currentChapterId: String = "",
        val doneCourses: Int = 0,
        val totalCourses: Int = 0,
        val submitted: Int = 0,
        val needsHuman: Int = 0,
        val failed: Int = 0,
        /** 当前卷子第几题 / 共几题 */
        val questionIndex: Int = 0,
        val questionTotal: Int = 0,
        /** 0-100，按课程 + 当前卷题目数平滑推进 */
        val percent: Int = 0,
        /** "courseKey#chapterId" -> 已提交 / 需人工 / 未完成 */
        val chapterResults: Map<String, String> = emptyMap(),
        /** 当前正在停留的题目详情；做题页/详情页用它显示题干/选项/大模型输出 */
        val detail: QuizQuestionLive? = null,
        /** 本次运行看过的题目历史（详情页可前后切换） */
        val questions: List<QuizQuestionLive> = emptyList(),
        /** 当前倒计时秒数：本题停留或提交前等待 */
        val waitSeconds: Int = 0,
        /** 倒计时结束的绝对时间（epoch ms），详情页据此计算剩余秒数 */
        val waitUntil: Long = 0L,
        /** 倒计时含义：「本题停留」/「提交前等待」 */
        val waitLabel: String = "",
        /** 用户手改过的答案 qid -> value */
        val overrides: Map<String, String> = emptyMap()
    )

    fun chapterResultKey(courseKey: String, chapterId: String): String = "$courseKey#$chapterId"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    @Volatile
    private var runId = 0

    /** 引擎在 IO 线程读、UI 在主线程写，用并发 map */
    private val answerOverrides = ConcurrentHashMap<String, String>()

    fun isRunning(): Boolean = job?.isActive == true

    /** 做题页用户切换选项：只影响当前这一份卷子的提交值 */
    fun setAnswerOverride(qid: String, value: String) {
        if (qid.isBlank()) return
        if (value.isBlank()) {
            answerOverrides.remove(qid)
        } else {
            answerOverrides[qid] = value
        }
        val s = _state.value
        val d = s.detail
        if (d != null && d.qid == qid) {
            _state.value = s.copy(
                detail = d.copy(answer = value, source = "手动"),
                overrides = answerOverrides.toMap()
            )
        } else {
            _state.value = s.copy(overrides = answerOverrides.toMap())
        }
    }

    @Synchronized
    fun start(
        accountId: String,
        courses: List<StudyCourse>,
        /** course.key -> 选中的章节 id；为空 map 表示整门课都做 */
        chapterSelection: Map<String, Set<String>> = emptyMap()
    ) {
        if (job?.isActive == true) return
        if (accountId.isBlank() || courses.isEmpty()) return

        // 只跑用户勾了章节的课程；兼容不传选择的情况（整门课）
        val runCourses = courses.filter {
            chapterSelection.isEmpty() || !chapterSelection[it.key].isNullOrEmpty()
        }
        if (runCourses.isEmpty()) return

        val id = ++runId
        answerOverrides.clear()
        _state.value = State(
            running = true,
            status = "准备中…",
            totalCourses = runCourses.size
        )

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            try {
                val account = AccountStore.get(accountId)
                    ?: throw IllegalStateException("账号不存在，请重新选择")
                _state.value = _state.value.copy(status = "正在登录 ${account.display}…")

                val (session, err) = SignEngine.sessionFor(account)
                if (session == null) throw IllegalStateException("登录失败：$err")

                val studyApi = StudyApi(session)
                val engine = buildEngine(session)
                val runner = QuizAutoRunner(
                    accountId = accountId,
                    studyApi = studyApi,
                    workApi = WorkApi(session),
                    engine = engine,
                    onChapter = { course, chapter ->
                        if (id == runId) {
                            _state.value = _state.value.copy(
                                currentCourseKey = course.key,
                                currentChapter = chapter.title,
                                currentChapterId = chapter.id,
                                questionIndex = 0,
                                questionTotal = 0,
                                status = "正在做题：${course.title} · ${chapter.title}"
                            )
                        }
                    },
                    onResult = { course, chapter, result ->
                        if (id == runId) {
                            if (result.submitted) {
                                // 交卷成功后立刻落缓存，章节状态变蓝、复选框熄灭，不用等下一次检查
                                StudyCheckStore.markQuizDone(accountId, course.key, chapter.id)
                            }
                            val label = when {
                                result.submitted -> "已提交"
                                result.message.contains("提交失败") -> "提交失败"
                                result.needsHuman -> "需人工"
                                else -> "未完成"
                            }
                            _state.value = _state.value.copy(
                                chapterResults = _state.value.chapterResults +
                                    (chapterResultKey(course.key, chapter.id) to label)
                            )
                        }
                    }
                )

                var submitted = 0
                var human = 0
                var failed = 0
                var done = 0

                for (course in runCourses) {
                    if (id != runId) return@launch
                    _state.value = _state.value.copy(
                        currentCourse = course.title,
                        currentCourseKey = course.key,
                        currentChapter = "",
                        currentChapterId = "",
                        questionIndex = 0,
                        questionTotal = 0,
                        detail = null,
                        waitSeconds = 0,
                        waitUntil = 0L,
                        waitLabel = "",
                        status = "正在做题（${done + 1}/${runCourses.size}）：${course.title}"
                    )

                    val pass = runCatching {
                        runner.patrol(
                            courses = listOf(course),
                            maxCourses = 1,
                            maxQuizzes = 80,
                            maxChaptersPerCourse = 400,
                            chapterSelection = chapterSelection
                        )
                    }.getOrElse { e ->
                        failed++
                        LogStore.add("[做题] ${course.title} 出错：${e.message ?: e.javaClass.simpleName}", false)
                        QuizAutoRunner.Pass(emptyList(), 1, 0, 0)
                    }

                    val courseSubmitted = pass.results.count { it.submitted }
                    submitted += courseSubmitted
                    human += pass.pendingHuman
                    failed += pass.results.count { !it.submitted && !it.needsHuman }

                    done++
                    _state.value = _state.value.copy(
                        doneCourses = done,
                        submitted = submitted,
                        needsHuman = human,
                        failed = failed,
                        currentChapter = "",
                        currentChapterId = "",
                        questionIndex = 0,
                        questionTotal = 0,
                        detail = null,
                        waitSeconds = 0,
                        waitUntil = 0L,
                        waitLabel = "",
                        percent = if (runCourses.isEmpty()) 0 else done * 100 / runCourses.size,
                        status = "已完成 $done/${runCourses.size} 门，提交 $submitted 份" +
                            if (human > 0) "，$human 份需人工" else ""
                    )
                }

                if (id == runId) {
                    _state.value = _state.value.copy(
                        running = false,
                        finished = true,
                        currentCourse = "",
                        currentCourseKey = "",
                        currentChapter = "",
                        currentChapterId = "",
                        questionIndex = 0,
                        questionTotal = 0,
                        detail = null,
                        waitSeconds = 0,
                        waitUntil = 0L,
                        waitLabel = "",
                        percent = 100,
                        status = "做题完成：提交 $submitted 份" +
                            if (human > 0) "，$human 份需人工处理" else ""
                    )
                    LogStore.add(
                        "[做题] 完成：${runCourses.size} 门课，提交 $submitted 份" +
                            if (human > 0) "，$human 份需人工" else "",
                        failed == 0 && human == 0
                    )
                }
            } catch (e: CancellationException) {
                if (id == runId) {
                    _state.value = _state.value.copy(
                        running = false,
                        finished = true,
                        currentCourse = "",
                        currentCourseKey = "",
                        currentChapter = "",
                        currentChapterId = "",
                        questionIndex = 0,
                        questionTotal = 0,
                        status = "已停止"
                    )
                }
                throw e
            } catch (e: Exception) {
                if (id == runId) {
                    _state.value = _state.value.copy(
                        running = false,
                        finished = true,
                        currentCourse = "",
                        currentCourseKey = "",
                        currentChapter = "",
                        currentChapterId = "",
                        questionIndex = 0,
                        questionTotal = 0,
                        status = "出错：${e.message ?: e.javaClass.simpleName}"
                    )
                }
                LogStore.add("[做题] 出错：${e.message}", false)
            }
        }
    }

    @Synchronized
    fun stop(reason: String = "用户停止") {
        if (!isRunning()) return
        runId++
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        _state.value = _state.value.copy(
            running = false,
            finished = true,
            currentCourse = "",
            currentCourseKey = "",
            currentChapter = "",
            currentChapterId = "",
            questionIndex = 0,
            questionTotal = 0,
            detail = null,
            waitSeconds = 0,
            waitUntil = 0L,
            waitLabel = "",
            status = reason
        )
        LogStore.add("[做题] $reason", false)
    }

    private fun buildEngine(session: com.xxt.sign.net.CxSession): QuizEngine = QuizEngine(
        api = WorkApi(session),
        answers = AnswerProvider(
            provider = Settings.quizProvider,
            endpoint = Settings.quizEndpoint,
            token = Settings.quizToken,
            llm = buildLlm()
        ),
        config = QuizConfig(
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
                    source = AndroidGlyphSource(App.ctx, bytes),
                    references = AndroidReferenceFonts.list(App.ctx),
                    size = 16,
                    fixedThreshold = Settings.quizFontThreshold
                )
            }
        },
        log = { msg, ok -> LogStore.add(msg, ok) },
        onStep = { index, total ->
            val s = _state.value
            if (s.running && s.totalCourses > 0) {
                val base = s.doneCourses * 100f / s.totalCourses
                val span = 100f / s.totalCourses
                val fraction = if (total > 0) index.toFloat() / total else 0f
                _state.value = s.copy(
                    questionIndex = index,
                    questionTotal = total,
                    percent = (base + span * fraction).toInt().coerceIn(0, 100)
                )
            }
        },
        onQuestion = { q ->
            val s = _state.value
            if (s.running) {
                val history = if (s.questions.lastOrNull()?.qid == q.qid) {
                    s.questions
                } else {
                    (s.questions + q).takeLast(100)
                }
                _state.value = s.copy(
                    detail = q,
                    questions = history,
                    waitSeconds = q.waitSeconds,
                    waitUntil = System.currentTimeMillis() + q.waitSeconds * 1000L,
                    waitLabel = "本题停留"
                )
            }
        },
        onSubmitWait = { seconds ->
            val s = _state.value
            if (s.running) {
                _state.value = s.copy(
                    waitSeconds = seconds,
                    waitUntil = System.currentTimeMillis() + seconds * 1000L,
                    waitLabel = "提交前等待"
                )
            }
        },
        overrideAnswer = { qid -> answerOverrides[qid] }
    )

    /** 大模型只在开关打开且填了 key 时启用，和巡检一致 */
    private fun buildLlm(): LlmClient? {
        if (!Settings.quizLlmEnabled) return null
        val key = Settings.quizLlmKey.trim()
        if (key.isEmpty()) return null
        return LlmClient(
            apiKey = key,
            baseUrl = Settings.quizLlmBaseUrl,
            model = Settings.quizLlmModel,
            effort = Settings.quizLlmEffort
        )
    }
}
