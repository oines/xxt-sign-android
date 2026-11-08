package com.xxt.sign.study

import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.sign.SignEngine
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

/**
 * 刷课任务的状态中心。
 * 没有前台服务：任务只跟随 App 进程，App 退到后台时由 [com.xxt.sign.App] 停止。
 */
object StudyManager {

    private val _state = MutableStateFlow(StudyState())
    val state: StateFlow<StudyState> = _state.asStateFlow()

    private val _tasks = MutableStateFlow<List<StudyTaskItem>>(emptyList())
    val tasks: StateFlow<List<StudyTaskItem>> = _tasks.asStateFlow()

    /** 由 UI 在启动前写入 */
    var plan: StudyPlan? = null

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    @Volatile
    private var runId = 0

    @Volatile
    private var stopping = false

    private val taskMap = LinkedHashMap<String, StudyTaskItem>()

    fun start() {
        val p = plan ?: return
        if (job?.isActive == true) return

        val id = ++runId
        stopping = false
        synchronized(taskMap) { taskMap.clear() }
        _tasks.value = emptyList()
        _state.value = StudyState(running = true, status = "准备中…")

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        job = newScope.launch {
            try {
                val acc = AccountStore.get(p.accountId)
                    ?: throw IllegalStateException("账号不存在，请重新选择")
                if (id == runId) {
                    _state.value = StudyState(running = true, status = "正在登录 ${acc.display}…")
                }

                val (session, err) = SignEngine.sessionFor(acc)
                if (session == null) throw IllegalStateException("登录失败：$err")

                val studyApi = StudyApi(session)
                val answers = com.xxt.sign.work.AnswerProvider(
                    provider = com.xxt.sign.core.Settings.quizProvider,
                    endpoint = com.xxt.sign.core.Settings.quizEndpoint,
                    token = com.xxt.sign.core.Settings.quizToken,
                    llm = buildPopQuizLlm()
                )
                val engine = StudyEngine(
                    api = studyApi,
                    plan = p,
                    onState = { st -> if (id == runId && !stopping) _state.value = st },
                    onTask = { task -> if (id == runId) updateTask(task, p.accountId) },
                    // 弹题接口没抓包时是 NONE：不发请求，只靠进度不动判断卡住
                    popQuizBridge = { course, job ->
                        if (com.xxt.sign.core.Settings.popQuizFetchUrl.isBlank()) {
                            PopQuizBridge.NONE
                        } else {
                            ConfiguredPopQuizBridge(session.pcClient, course, job)
                        }
                    },
                    popQuizSolver = { q -> solvePopQuiz(answers, q) }
                )
                val summary = engine.run()

                if (id == runId && !stopping) {
                    val total = _state.value.totalChapters
                    _state.value = StudyState(
                        running = false,
                        finished = true,
                        status = "处理：${summary.doneChapters} 章，失败 ${summary.failedTasks} 章",
                        doneChapters = summary.doneChapters,
                        totalChapters = total,
                        failedTasks = summary.failedTasks
                    )
                }
                LogStore.add(
                    "[刷课] 完成：${summary.doneChapters} 章，失败 ${summary.failedTasks} 章",
                    summary.failedTasks == 0
                )
            } catch (e: CancellationException) {
                if (id == runId && !stopping) {
                    _state.value = StudyState(running = false, finished = true, status = "已停止")
                }
                throw e
            } catch (e: Exception) {
                if (id == runId && !stopping) {
                    _state.value = StudyState(
                        running = false,
                        finished = true,
                        status = "出错：${e.message ?: e.javaClass.simpleName}"
                    )
                }
                LogStore.add("[刷课] 出错：${e.message}", false)
            }
        }
    }

    /** 弹题作答：把弹题翻译成章节测验那套 question 结构，走题库优先 + LLM 兜底 */
    private suspend fun solvePopQuiz(
        answers: com.xxt.sign.work.AnswerProvider,
        q: PopQuizQuestion
    ): String {
        val wq = com.xxt.sign.work.WorkQuestion(
            qid = q.id,
            type = q.type,
            stem = q.stem,
            options = q.options.mapIndexed { i, text ->
                com.xxt.sign.work.WorkOption(key = ('A'.code + i).toChar().toString(), text = text)
            }
        )
        val a = answers.search(wq) ?: return ""
        return a.value
    }

    /** 大模型只在开关打开且填了 key 时启用，和章节测验一致 */
    private fun buildPopQuizLlm(): com.xxt.sign.work.LlmClient? {
        if (!com.xxt.sign.core.Settings.quizLlmEnabled) return null
        val key = com.xxt.sign.core.Settings.quizLlmKey.trim()
        if (key.isEmpty()) return null
        return com.xxt.sign.work.LlmClient(
            apiKey = key,
            baseUrl = com.xxt.sign.core.Settings.quizLlmBaseUrl,
            model = com.xxt.sign.core.Settings.quizLlmModel
        )
    }

    private fun updateTask(task: StudyTaskItem, accountId: String) {
        if (task.status == StudyTaskStatus.DONE) {
            val parts = task.key.split("#", limit = 3)
            if (parts.size == 3) {
                StudyCheckStore.markVideoDone(accountId, parts[0], parts[1], parts[2])
            }
        }
        synchronized(taskMap) {
            taskMap[task.key] = task
            _tasks.value = taskMap.values.toList()
        }
    }

    fun stop(reason: String = "已停止") {
        if (!isRunning() && !_state.value.running) return
        runId++
        stopping = true
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null

        synchronized(taskMap) {
            taskMap.replaceAll { _, task ->
                if (task.status == StudyTaskStatus.PENDING || task.status == StudyTaskStatus.RUNNING) {
                    task.copy(status = StudyTaskStatus.FAILED, message = reason)
                } else {
                    task
                }
            }
            _tasks.value = taskMap.values.toList()
        }
        _state.value = StudyState(running = false, finished = true, status = reason)
        LogStore.add("[刷课] $reason", false)
    }

    fun isRunning(): Boolean = job?.isActive == true
}
