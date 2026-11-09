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

                val engine = StudyEngine(
                    api = StudyApi(session),
                    plan = p,
                    onState = { st -> if (id == runId && !stopping) _state.value = st },
                    onTask = { task -> if (id == runId) updateTask(task, p.accountId) }
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
