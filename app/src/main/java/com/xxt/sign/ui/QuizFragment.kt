package com.xxt.sign.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.xxt.sign.R
import com.xxt.sign.core.Account
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.FragmentQuizBinding
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.study.ChapterCheck
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyChapter
import com.xxt.sign.study.StudyCheckStore
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.study.StudyCourseStore
import com.xxt.sign.work.QuizRunManager
import com.xxt.sign.work.WorkQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「做题」页：只扫章节测验并自动作答，和刷课完全分开。
 *
 * 课程/章节列表与刷课页共用 [StudyCourseStore]/[StudyCheckStore]，
 * 但章节勾选单独存 [Settings.quizSelectedChapters]，互不影响。
 */
class QuizFragment : Fragment() {

    private var _b: FragmentQuizBinding? = null
    private val b get() = _b!!

    private val items = mutableListOf<QuizCourseItem>()
    private val adapter = QuizCourseAdapter(
        onToggleCourse = ::onToggleCourse,
        onToggleChapter = ::onToggleChapter,
        onExpand = ::onExpand,
        onRefresh = ::onRefreshCourse
    )

    private var lastAccountId = Settings.studyAccountId
    private var loading = false
    private var checkingCount = 0
    private var apiAccountId = ""
    private var api: StudyApi? = null

    /** 用户勾了课程级复选框后，等章节检查完再自动回选 */
    private val selectAfterLoad = mutableSetOf<String>()

    private var lastActiveKey = ""
    private var lastResultsSize = -1
    private var lastRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentQuizBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.quizRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.quizRecycler.adapter = adapter

        b.btnQuizFetch.setOnClickListener { fetchCourses() }
        b.btnQuizStart.setOnClickListener { startQuiz() }
        b.btnQuizStop.setOnClickListener { QuizRunManager.stop("用户停止做题") }
        b.btnQuizSettings.setOnClickListener { QuizSettingsDialog.show(this) }
        b.btnQuizSelectAll.setOnClickListener { selectAll(true) }
        b.btnQuizClear.setOnClickListener { selectAll(false) }
        b.cardQuizDetail.setOnClickListener {
            startActivity(Intent(requireContext(), QuizDetailActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AccountStore.accounts.collect { refreshAccount() } }
                launch {
                    QuizRunManager.state.collect { state ->
                        render(state)
                    }
                }
            }
        }

        refreshAccount()
        render(QuizRunManager.state.value)
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshAccount()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ------------------------------------------------------------ 账号 / 课程

    private fun selectedAccount(): Account? = AccountStore.get(Settings.studyAccountId)

    private fun refreshAccount() {
        if (_b == null) return
        val selected = selectedAccount()
        if (selected == null) {
            b.tvQuizAccount.text = "做题账号：未选择"
            b.tvQuizAccountHint.text = "在「账号」页勾选要用于做题的账号"
            if (lastAccountId.isNotBlank()) {
                lastAccountId = ""
                resetApi()
                if (QuizRunManager.isRunning()) QuizRunManager.stop("账号已切换，做题已停止")
            }
            if (items.isNotEmpty()) {
                items.clear()
                adapter.submit(emptyList())
            }
            b.tvQuizEmpty.text = "请先在「账号」页勾选做题账号"
            b.tvQuizEmpty.visibility = View.VISIBLE
            updateHeader()
            return
        }

        b.tvQuizAccount.text = "做题账号：${selected.display}"
        b.tvQuizAccountHint.text = "在「账号」页可切换；当前手机号 ${selected.phone}"

        if (lastAccountId != selected.id) {
            lastAccountId = selected.id
            resetApi()
            if (QuizRunManager.isRunning()) QuizRunManager.stop("账号已切换，做题已停止")
            restoreCachedCourses(selected, keepSelection = false)
        } else if (items.isEmpty()) {
            restoreCachedCourses(selected)
        }
        updateHeader()
    }

    private fun resetApi() {
        api = null
        apiAccountId = ""
    }

    private fun restoreCachedCourses(account: Account, keepSelection: Boolean = true) {
        val snapshot = StudyCourseStore.load(account.id)
        if (snapshot == null || snapshot.courses.isEmpty()) {
            items.clear()
            adapter.submit(emptyList())
            b.tvQuizEmpty.text = "暂无课程缓存\n点「获取课程」从服务器拉取"
            b.tvQuizEmpty.visibility = View.VISIBLE
            updateHeader()
            return
        }

        val cachedChecks = StudyCheckStore.load(account.id)
        val stored = if (keepSelection) Settings.quizSelectedChapters else emptySet()
        items.clear()
        items.addAll(snapshot.courses.map { course ->
            val chapters = snapshot.chapters[course.key].orEmpty()
            val checks = cachedChecks[course.key].orEmpty()
            val prefix = course.key + "#"
            val selected = stored.asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.substringAfterLast('#') }
                .toSet()
            QuizCourseItem(
                course = course,
                chapters = chapters.takeIf { it.isNotEmpty() || snapshot.chapters.containsKey(course.key) },
                checks = checks,
                selectedChapters = selected
            )
        })
        adapter.submit(items.toList())
        b.tvQuizEmpty.text = "先选择账号并获取课程列表"
        b.tvQuizEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        updateHeader()
    }

    private fun fetchCourses() {
        val account = selectedAccount()
        if (account == null) {
            toast("先在「账号」页勾选做题账号")
            return
        }
        if (loading) return
        loading = true
        b.btnQuizFetch.isEnabled = false
        b.btnQuizFetch.text = "获取中…"
        b.tvQuizEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val courses = withContext(Dispatchers.IO) {
                runCatching { apiFor(account).getCourses() }
            }

            loading = false
            if (_b == null) return@launch
            b.btnQuizFetch.text = "获取课程"
            b.btnQuizFetch.isEnabled = true

            courses.onSuccess { list ->
                if (list.isEmpty()) {
                    toast("没有拉到课程")
                    updateHeader()
                    return@onSuccess
                }
                // 章节缓存是刷课页的，别因为这里刷新课程把它清掉
                val oldChapters = StudyCourseStore.load(account.id)?.chapters.orEmpty()
                StudyCourseStore.saveCourses(account.id, list, oldChapters)
                applyCourses(list)
            }.onFailure {
                toast("获取课程失败：${it.message ?: it.javaClass.simpleName}")
                updateHeader()
            }
        }
    }

    private fun applyCourses(courses: List<StudyCourse>, keepSelection: Boolean = true) {
        val oldByKey = items.associateBy { it.course.key }
        items.clear()
        items.addAll(courses.map { course ->
            val old = oldByKey[course.key]
            QuizCourseItem(
                course = course,
                expanded = old?.expanded ?: false,
                chapters = old?.chapters,
                checks = old?.checks.orEmpty(),
                failedChapters = old?.failedChapters.orEmpty(),
                selectedChapters = if (keepSelection) old?.selectedChapters.orEmpty() else emptySet()
            )
        })
        adapter.submit(items.toList())
        persistSelection()
        b.tvQuizEmpty.text = "先选择账号并获取课程列表"
        b.tvQuizEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        updateHeader()
    }

    private fun persistSelection() {
        if (lastAccountId.isBlank()) return
        Settings.quizSelectedChapters = items.flatMap { item ->
            item.selectedChapters.map { "${item.course.key}#$it" }
        }.toSet()
    }

    // ------------------------------------------------------------ 展开 / 检查

    private fun onExpand(course: StudyCourse) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0 || QuizRunManager.isRunning()) return
        val item = items[index]
        if (item.expanded) {
            items[index] = item.copy(expanded = false)
            adapter.submit(items.toList())
            return
        }
        items[index] = item.copy(expanded = true)
        adapter.submit(items.toList())
        if (item.chapters == null) {
            loadChapters(course, checkAfter = true)
        } else if (item.chapters.isNotEmpty()) {
            checkCourse(index)
        }
    }

    private fun onRefreshCourse(course: StudyCourse) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0 || QuizRunManager.isRunning()) return
        val account = selectedAccount() ?: return
        StudyCheckStore.clearCourse(account.id, course.key)
        items[index] = items[index].copy(
            expanded = true,
            chapters = null,
            checks = emptyMap(),
            failedChapters = emptySet(),
            selectedChapters = emptySet()
        )
        selectAfterLoad.remove(course.key)
        persistSelection()
        adapter.submit(items.toList())
        loadChapters(course, checkAfter = true)
    }

    private fun loadChapters(course: StudyCourse, checkAfter: Boolean) {
        val account = selectedAccount() ?: return
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0) return
        if (items[index].loading) return
        items[index] = items[index].copy(loading = true)
        adapter.submit(items.toList())

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { apiFor(account).getChapters(course) }
            }
            if (_b == null) return@launch
            val now = items.indexOfFirst { it.course.key == course.key }
            if (now < 0) return@launch
            result.onSuccess { chapters ->
                items[now] = items[now].copy(loading = false, chapters = chapters)
                StudyCourseStore.putChapters(account.id, course.key, chapters)
                adapter.submit(items.toList())
                if (checkAfter) {
                    checkCourse(now) {
                        if (selectAfterLoad.remove(course.key)) selectPendingChapters(now)
                    }
                }
            }.onFailure {
                items[now] = items[now].copy(loading = false)
                adapter.submit(items.toList())
                toast("读取章节失败：${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    /**
     * 逐章检查任务点，拿到 quizTasks/quizDone。网络异常不写缓存，标「检查失败」。
     */
    private fun checkCourse(index: Int, onDone: (() -> Unit)? = null) {
        if (QuizRunManager.isRunning()) return
        if (index !in items.indices) return
        val item = items[index]
        if (item.checking || item.loading) return
        val chapters = item.chapters ?: return
        if (chapters.isEmpty()) return
        val account = selectedAccount() ?: return
        val course = item.course

        items[index] = item.copy(checking = true, failedChapters = emptySet())
        adapter.submit(items.toList())
        checkingCount++
        showCheckProgress("检查章节测验：${course.title}", 0, chapters.size)

        viewLifecycleOwner.lifecycleScope.launch {
            val checks = items.getOrNull(index)?.checks?.toMutableMap() ?: mutableMapOf()
            val failed = mutableSetOf<String>()
            var done = 0

            withContext(Dispatchers.IO) {
                val studyApi = runCatching { apiFor(account) }.getOrNull()
                if (studyApi == null) {
                    failed.addAll(chapters.filter { !it.finished }.map { it.id })
                } else {
                    for (ch in chapters) {
                        if (ch.finished || checks.containsKey(ch.id)) {
                            done++
                            continue
                        }
                        runCatching { studyApi.checkChapter(course, ch) }
                            .onSuccess { checks[ch.id] = it }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                failed.add(ch.id)
                                LogStore.add(
                                    "[做题] 检查失败：${course.title} · ${ch.title}（${e.message ?: e.javaClass.simpleName}）",
                                    false
                                )
                            }
                        done++
                        withContext(Dispatchers.Main) {
                            if (_b != null) {
                                showCheckProgress("检查章节测验：${course.title}", done, chapters.size)
                            }
                        }
                    }
                    StudyCheckStore.putCourse(account.id, course.key, checks)
                }
            }

            if (failed.isEmpty()) {
                LogStore.add("[做题] 检查完成：${course.title} · ${chapters.size} 章", true)
            }
            hideCheckProgress()
            finishCheck(index, checks, failed)
            if (failed.isNotEmpty()) toast("${failed.size} 个章节检查失败，可点课程「刷新」重试")
            onDone?.invoke()
        }
    }

    private fun finishCheck(index: Int, checks: Map<String, ChapterCheck>, failed: Set<String>) {
        if (_b == null) return
        if (index !in items.indices) return
        val item = items[index]
        val selected = item.selectedChapters.filter { id ->
            checks[id]?.let { it.quizLeft > 0 } ?: true
        }.toSet()
        items[index] = item.copy(
            checking = false,
            checks = checks,
            failedChapters = failed,
            selectedChapters = selected
        )
        adapter.submit(items.toList())
        persistSelection()
        updateHeader()
    }

    private fun showCheckProgress(text: String, done: Int, total: Int) {
        if (_b == null) return
        b.tvQuizCheckProgress.visibility = View.VISIBLE
        b.quizCheckProgress.visibility = View.VISIBLE
        b.tvQuizCheckProgress.text = if (total > 0) "$text（$done/$total）" else text
        if (total > 0) {
            b.quizCheckProgress.isIndeterminate = false
            b.quizCheckProgress.max = total
            b.quizCheckProgress.setProgressCompat(done, true)
        } else {
            b.quizCheckProgress.isIndeterminate = true
        }
    }

    private fun hideCheckProgress() {
        checkingCount = (checkingCount - 1).coerceAtLeast(0)
        if (_b == null || checkingCount > 0) return
        b.tvQuizCheckProgress.visibility = View.GONE
        b.quizCheckProgress.visibility = View.GONE
    }

    // ------------------------------------------------------------ 勾选

    private fun onToggleCourse(course: StudyCourse, checked: Boolean) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0 || QuizRunManager.isRunning()) return
        if (!checked) {
            items[index] = items[index].copy(selectedChapters = emptySet())
            commitSelection()
            return
        }
        val item = items[index]
        if (item.chapters == null) {
            selectAfterLoad.add(course.key)
            loadChapters(course, checkAfter = true)
            return
        }
        if (item.chapters.isEmpty()) {
            toast("这门课没有章节")
            return
        }
        checkCourse(index) { selectPendingChapters(index) }
    }

    private fun onToggleChapter(course: StudyCourse, chapter: StudyChapter, checked: Boolean) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0 || QuizRunManager.isRunning()) return
        val item = items[index]
        items[index] = item.copy(
            selectedChapters = if (checked) item.selectedChapters + chapter.id
            else item.selectedChapters - chapter.id
        )
        commitSelection()
    }

    private fun selectPendingChapters(index: Int) {
        if (index !in items.indices) return
        val item = items[index]
        val pending = item.chapters.orEmpty().filter {
            !it.finished && ((item.checks[it.id]?.quizLeft ?: 0) > 0 || it.id in item.failedChapters)
        }
        items[index] = item.copy(selectedChapters = pending.map { it.id }.toSet())
        commitSelection()
    }

    private fun commitSelection() {
        persistSelection()
        adapter.submit(items.toList())
        updateHeader()
    }

    private fun selectAll(selected: Boolean) {
        if (QuizRunManager.isRunning()) return
        if (!selected) {
            for (i in items.indices) items[i] = items[i].copy(selectedChapters = emptySet())
            commitSelection()
            return
        }
        var count = 0
        for (i in items.indices) {
            val item = items[i]
            val pending = item.chapters.orEmpty().filter {
                !it.finished && (item.checks[it.id]?.quizLeft ?: 0) > 0
            }.map { it.id }.toSet()
            if (pending.isNotEmpty()) {
                items[i] = item.copy(selectedChapters = pending)
                count++
            }
        }
        commitSelection()
        if (count == 0) toast("还没有已检查的待做测验，先展开课程自动检查")
    }

    // ------------------------------------------------------------ 运行

    private fun updateHeader() {
        if (_b == null) return
        val selectedCourses = items.count { it.selectedChapters.isNotEmpty() }
        val selectedChapters = items.sumOf { it.selectedChapters.size }
        b.tvQuizCourses.text = when {
            items.isEmpty() -> "未拉取课程"
            selectedCourses == 0 -> "课程（未选择章节）"
            else -> "课程（$selectedCourses 门 / $selectedChapters 章）"
        }
        b.btnQuizStart.isEnabled = !QuizRunManager.isRunning() && selectedCourses > 0
        b.btnQuizSelectAll.isEnabled = !QuizRunManager.isRunning() && items.isNotEmpty()
        b.btnQuizClear.isEnabled = !QuizRunManager.isRunning() && items.isNotEmpty()
    }

    private fun startQuiz() {
        val account = selectedAccount()
        if (account == null) {
            toast("先在「账号」页勾选做题账号")
            return
        }
        val chosen = items.filter { it.selectedChapters.isNotEmpty() }
        if (chosen.isEmpty()) {
            toast("先勾选要做的章节")
            return
        }
        val courses = chosen.map { it.course }
        val selection = chosen.associate { it.course.key to it.selectedChapters }
        QuizRunManager.start(account.id, courses, selection)
    }

    private fun render(state: QuizRunManager.State) {
        if (_b == null) return

        b.tvQuizStatus.text = buildString {
            append(state.status)
            if (state.running && state.questionTotal > 0) {
                append("（第 ").append(state.questionIndex).append("/").append(state.questionTotal).append(" 题）")
            }
        }
        if (state.running) {
            b.quizProgress.visibility = View.VISIBLE
            if (state.totalCourses > 0) {
                b.quizProgress.isIndeterminate = false
                b.quizProgress.setProgressCompat(state.percent, true)
            } else {
                b.quizProgress.isIndeterminate = true
            }
        } else {
            b.quizProgress.visibility = View.GONE
        }
        renderDetail(state)

        b.btnQuizFetch.isEnabled = !state.running && !loading
        b.btnQuizStop.isEnabled = state.running
        b.btnQuizStart.isEnabled = !state.running && items.any { it.selectedChapters.isNotEmpty() }
        b.btnQuizSelectAll.isEnabled = !state.running && items.isNotEmpty()
        b.btnQuizClear.isEnabled = !state.running && items.isNotEmpty()

        val activeKey = if (state.running && state.currentCourseKey.isNotBlank()) {
            "${state.currentCourseKey}#${state.currentChapterId}"
        } else {
            ""
        }
        val resultsChanged = state.chapterResults.size != lastResultsSize
        val runningChanged = state.running != lastRunning

        if (items.isNotEmpty() &&
            (activeKey != lastActiveKey || resultsChanged || runningChanged)
        ) {
            lastActiveKey = activeKey
            lastResultsSize = state.chapterResults.size
            lastRunning = state.running

            // 交卷后 QuizRunManager 会立刻更新检查缓存；这里同步刷新，让章节变蓝、复选框变灰
            val savedChecks = if (resultsChanged || (runningChanged && !state.running)) {
                StudyCheckStore.load(Settings.studyAccountId)
            } else {
                emptyMap()
            }
            var selectionChanged = false

            for (i in items.indices) {
                val item = items[i]
                val activeChapter = if (state.running && item.course.key == state.currentCourseKey) {
                    state.currentChapterId.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val prefix = item.course.key + "#"
                val results = state.chapterResults
                    .filterKeys { it.startsWith(prefix) }
                    .mapKeys { it.key.substringAfterLast('#') }
                val freshChecks = savedChecks[item.course.key]
                val mergedChecks = if (freshChecks == null) {
                    item.checks
                } else {
                    item.checks.toMutableMap().apply {
                        freshChecks.forEach { (id, check) -> if (containsKey(id)) put(id, check) }
                    }
                }
                val submittedIds = results.filterValues { it == "已提交" }.keys
                val newSelection = item.selectedChapters - submittedIds
                if (newSelection.size != item.selectedChapters.size) selectionChanged = true
                items[i] = item.copy(
                    running = state.running,
                    activeChapterId = activeChapter,
                    chapterResults = results,
                    checks = mergedChecks,
                    selectedChapters = newSelection,
                    expanded = item.expanded || (activeChapter != null)
                )
            }
            if (selectionChanged) persistSelection()
            adapter.submit(items.toList())
            updateHeader()
        } else {
            updateHeader()
        }
    }

    // ------------------------------------------------------------ 做题详情

    /** 只渲染一行入口；具体内容在 [QuizDetailActivity]，避免挤压下面的课程/章节列表 */
    private fun renderDetail(state: QuizRunManager.State) {
        if (_b == null) return
        val detail = state.detail
        if (!state.running || detail == null) {
            b.cardQuizDetail.visibility = View.GONE
            return
        }
        b.cardQuizDetail.visibility = View.VISIBLE
        b.tvQuizDetailTitle.text = buildString {
            if (detail.chapterTitle.isNotBlank()) append(detail.chapterTitle).append(" · ")
            append("第 ").append(detail.index).append("/").append(detail.total).append(" 题 · ")
            append(WorkQuestion.typeName(detail.type))
        }
        b.tvQuizDetailStem.text = detail.stem.ifBlank { "（题干为空）" }
        val label = state.waitLabel.ifBlank { "等待" }
        b.tvQuizDetailWait.text = if (state.waitSeconds > 0) "$label ${state.waitSeconds}s" else ""
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private suspend fun apiFor(account: Account): StudyApi {
        val cached = api
        if (cached != null && apiAccountId == account.id) return cached
        val (session, err) = SignEngine.sessionFor(account)
        if (session == null) throw IllegalStateException("登录失败：$err")
        return StudyApi(session).also {
            api = it
            apiAccountId = account.id
        }
    }
}
