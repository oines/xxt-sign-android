package com.xxt.sign.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.xxt.sign.core.Account
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.FragmentStudyBinding
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.study.ChapterCheck
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyChapter
import com.xxt.sign.study.StudyCheckStore
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.study.StudyManager
import com.xxt.sign.study.StudyPlan
import com.xxt.sign.study.StudyService
import com.xxt.sign.study.StudyState
import com.xxt.sign.study.StudyTaskItem
import com.xxt.sign.study.StudyTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * 刷课页：
 * 选账号（账号页）→ 拉课程 → 展开课程加载章节 → 勾选章节 → 开始。
 * 只处理视频任务点；任务只在 App 前台运行。
 */
class StudyFragment : Fragment() {

    private var _b: FragmentStudyBinding? = null
    private val b get() = _b!!

    private val items = mutableListOf<StudyCourseItem>()
    private val adapter = StudyCourseAdapter(
        ::onToggleCourse,
        ::onToggleChapter,
        ::onExpand,
        ::onRefreshCourse
    )

    private var api: StudyApi? = null
    private var loadingCourses = false
    private var lastAccountId = Settings.studyAccountId

    private val speeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startServiceNow() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentStudyBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.tvConcurrency.text = Settings.studyConcurrency.toString()
        b.btnSpeed.text = formatSpeed(Settings.studySpeed)

        b.btnFetch.setOnClickListener { fetchCourses() }
        b.btnStart.setOnClickListener { startStudy() }
        b.btnStop.setOnClickListener { stopStudy() }
        b.btnMinus.setOnClickListener { setConcurrency(Settings.studyConcurrency - 1) }
        b.btnPlus.setOnClickListener { setConcurrency(Settings.studyConcurrency + 1) }
        b.btnSpeed.setOnClickListener { cycleSpeed() }
        b.btnSelectAll.setOnClickListener { selectAll(true) }
        b.btnClear.setOnClickListener { selectAll(false) }
        b.cardRun.setOnClickListener {
            startActivity(Intent(requireContext(), StudyDetailActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AccountStore.accounts.collect { onAccountsChanged(it) } }
                launch {
                    StudyManager.state.collect { state ->
                        render(state)
                        updateRunCard(state, StudyManager.tasks.value)
                    }
                }
                launch {
                    StudyManager.tasks.collect { tasks ->
                        updateRunCard(StudyManager.state.value, tasks)
                    }
                }
                launch {
                    StudyCheckStore.version.collect { reloadChecks() }
                }
            }
        }
        refreshAccount()
        render(StudyManager.state.value)
        updateCoursesHeader()
        updateRunCard(StudyManager.state.value, StudyManager.tasks.value)
    }

    override fun onResume() {
        super.onResume()
        refreshAccount()
        reloadChecks()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshAccount()
            reloadChecks()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ------------------------------------------------------------ 账号

    private fun selectedAccount(): Account? = AccountStore.get(Settings.studyAccountId)

    private fun onAccountsChanged(list: List<Account>) {
        refreshAccount(list.size)
    }

    private fun refreshAccount(accountCount: Int = AccountStore.accounts.value.size) {
        if (_b == null) return
        val selected = AccountStore.get(Settings.studyAccountId)
        if (selected == null) {
            if (Settings.studyAccountId.isNotBlank() && accountCount > 0) {
                Settings.studyAccountId = ""
            }
            b.tvAccount.text = "刷课账号：未选择"
            b.tvAccountHint.text = "在「账号」页勾选要用于刷课的账号"
            if (lastAccountId.isNotBlank()) {
                lastAccountId = ""
                api = null
                Settings.studySelectedChapters = emptySet()
                if (items.isNotEmpty()) clearCourses("账号已移除或未选择，请重新选择")
            }
            return
        }

        b.tvAccount.text = "刷课账号：${selected.display}"
        b.tvAccountHint.text = if (accountCount > 1) {
            "在「账号」页可切换；当前手机号 ${selected.phone}"
        } else {
            "在「账号」页可切换"
        }

        if (lastAccountId != selected.id) {
            lastAccountId = selected.id
            if (StudyManager.isRunning()) StudyManager.stop("账号已切换，刷课已停止")
            api = null
            Settings.studySelectedChapters = emptySet()
            if (items.isNotEmpty()) clearCourses("已切换账号，请重新拉取课程")
        }
    }

    // ------------------------------------------------------------ 课程

    private fun fetchCourses() {
        val account = selectedAccount() ?: run {
            toast("请先在「账号」页勾选刷课账号")
            return
        }
        if (StudyManager.isRunning()) return toast("刷课进行中")
        if (loadingCourses) return

        val fetchAccountId = account.id
        loadingCourses = true
        b.btnFetch.isEnabled = false
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = true
        b.tvStatus.text = "正在登录并拉取课程…"
        b.tvCheckProgress.visibility = View.GONE
        b.checkProgress.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (session, err) = SignEngine.sessionFor(account)
                    if (session == null) throw IllegalStateException("登录失败：$err")
                    val studyApi = StudyApi(session)
                    api = studyApi
                    studyApi.getCourses()
                }
            }

            loadingCourses = false
            b.btnFetch.isEnabled = !StudyManager.isRunning()
            b.progress.isIndeterminate = false
            b.progress.visibility = View.GONE

            result.onSuccess { courses ->
                if (Settings.studyAccountId != fetchAccountId) {
                    api = null
                    return@onSuccess
                }
                if (courses.isEmpty()) {
                    clearCourses("没有拉到课程（会话可能已失效）")
                    return@onSuccess
                }

                val stored = Settings.studySelectedChapters
                val storedChecks = StudyCheckStore.load(account.id)
                items.clear()
                items.addAll(courses.map { course ->
                    val prefix = "${course.key}#"
                    val checks = storedChecks[course.key].orEmpty()
                    val selected = stored
                        .filter { it.startsWith(prefix) }
                        .map { it.substringAfterLast('#') }
                        .filter { checks[it]?.videoComplete != true }
                        .toSet()
                    StudyCourseItem(
                        course = course,
                        checks = checks,
                        selectedChapters = selected
                    )
                })
                persistSelection()
                adapter.submit(items.toList())
                b.tvEmpty.visibility = View.GONE
                b.tvStatus.text = "已拉取 ${courses.size} 门课程，展开后勾选章节"
                updateCoursesHeader()
            }.onFailure {
                b.tvStatus.text = "拉取失败：${it.message}"
                toast("拉取失败：${it.message}")
            }
        }
    }

    private fun onToggleCourse(course: StudyCourse, checked: Boolean) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0) return
        val item = items[index]

        if (!checked) {
            items[index] = item.copy(selectedChapters = emptySet())
            adapter.submit(items.toList())
            persistSelection()
            updateCoursesHeader()
            return
        }
        if (item.loading || item.checking) return
        if (StudyManager.isRunning()) return toast("刷课进行中")

        viewLifecycleOwner.lifecycleScope.launch {
            val studyApi = ensureApi() ?: return@launch
            checkItem(index, studyApi)
            val now = items.getOrNull(index) ?: return@launch
            val pending = now.chapters.orEmpty()
                .filter { !it.finished && now.checks[it.id]?.videoPending == true }
                .map { it.id }
                .toSet()
            if (pending.isEmpty()) {
                toast("该课程没有未完成的视频任务")
                return@launch
            }
            items[index] = now.copy(expanded = true, selectedChapters = pending)
            adapter.submit(items.toList())
            persistSelection()
            updateCoursesHeader()
        }
    }

    private fun onToggleChapter(course: StudyCourse, chapter: StudyChapter, checked: Boolean) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0) return
        val item = items[index]
        val selected = item.selectedChapters.toMutableSet()
        if (checked) selected.add(chapter.id) else selected.remove(chapter.id)
        items[index] = item.copy(selectedChapters = selected)
        adapter.submit(items.toList())
        persistSelection()
        updateCoursesHeader()
    }

    private fun onExpand(course: StudyCourse) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0) return
        val item = items[index]

        if (item.expanded) {
            items[index] = item.copy(expanded = false)
            adapter.submit(items.toList())
            return
        }

        items[index] = item.copy(expanded = true)
        adapter.submit(items.toList())
        if (item.chapters.isNullOrEmpty()) {
            loadChapters(course, checkAfter = true)
        } else {
            checkCourse(index)
        }
    }

    /** 展开课程后只检查这一门 */
    private fun checkCourse(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (item.checking || item.loading) return
        val chapters = item.chapters
        if (chapters != null && chapters.all { it.finished || item.checks.containsKey(it.id) }) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val studyApi = ensureApi() ?: return@launch
            checkItem(index, studyApi)
        }
    }

    /** 手动刷新：清掉这一门的本地检查缓存后重新检查 */
    private fun onRefreshCourse(course: StudyCourse) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0) return
        val item = items[index]
        if (item.checking || item.loading) return
        if (StudyManager.isRunning()) return toast("刷课进行中")
        val account = selectedAccount() ?: return toast("请先在「账号」页勾选刷课账号")

        StudyCheckStore.clearCourse(account.id, course.key)
        items[index] = item.copy(checks = emptyMap(), checking = false)
        adapter.submit(items.toList())
        checkCourse(index)
    }

    /** 从本地持久化恢复检查状态（任务完成、切换账号、重启 App 后） */
    private fun reloadChecks() {
        if (_b == null) return
        if (items.any { it.checking || it.loading }) return
        val account = selectedAccount() ?: return
        val stored = StudyCheckStore.load(account.id)
        var changed = false
        for (i in items.indices) {
            val checks = stored[items[i].course.key].orEmpty()
            val selected = items[i].selectedChapters
                .filter { checks[it]?.videoComplete != true }
                .toSet()
            if (items[i].checks != checks || items[i].selectedChapters != selected) {
                items[i] = items[i].copy(checks = checks, selectedChapters = selected)
                changed = true
            }
        }
        if (changed) {
            adapter.submit(items.toList())
            persistSelection()
            updateCoursesHeader()
        }
    }

    /** 刷完的章节从勾选里移除 */
    private fun pruneSelection(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        val selected = item.selectedChapters
            .filter { item.checks[it]?.videoComplete != true }
            .toSet()
        if (selected == item.selectedChapters) return false
        items[index] = item.copy(selectedChapters = selected)
        return true
    }

    /** 只负责把章节列表读出来；checkAfter=true 时读完立即检查这一门 */
    private fun loadChapters(course: StudyCourse, checkAfter: Boolean = false) {
        val index = items.indexOfFirst { it.course.key == course.key }
        if (index < 0) return
        if (items[index].loading) return

        items[index] = items[index].copy(expanded = true, loading = true)
        adapter.submit(items.toList())

        viewLifecycleOwner.lifecycleScope.launch {
            val studyApi = ensureApi()
            if (studyApi == null) {
                val now = items.indexOfFirst { it.course.key == course.key }
                if (now >= 0) {
                    items[now] = items[now].copy(loading = false)
                    adapter.submit(items.toList())
                }
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching { studyApi.getChapters(course) }
            }
            val now = items.indexOfFirst { it.course.key == course.key }
            if (now >= 0) {
                val loaded = result.getOrDefault(emptyList())
                val existing = items[now].selectedChapters
                items[now] = items[now].copy(
                    loading = false,
                    expanded = true,
                    chapters = loaded,
                    selectedChapters = existing.intersect(loaded.map { it.id }.toSet())
                )
                adapter.submit(items.toList())
                persistSelection()
                updateCoursesHeader()
                if (checkAfter) checkItem(now, studyApi)
            }
            result.onFailure { toast("章节读取失败：${it.message}") }
        }
    }

    /** 检查一个课程里所有章节的任务点，并更新颜色 */
    private suspend fun checkItem(index: Int, studyApi: StudyApi) {
        val course = items.getOrNull(index)?.course ?: return
        val accountId = selectedAccount()?.id ?: return
        if (items[index].checking) return
        items[index] = items[index].copy(checking = true)
        adapter.submit(items.toList())

        val chapters = ensureChaptersLoaded(index, studyApi)
        val checks = items[index].checks.toMutableMap()
        var done = 0
        val total = chapters.size

        b.tvCheckProgress.visibility = View.VISIBLE
        b.tvCheckProgress.text = "检查任务点 0/$total：${course.title}"
        b.checkProgress.visibility = if (total > 0) View.VISIBLE else View.GONE
        b.checkProgress.isIndeterminate = false
        b.checkProgress.max = total.coerceAtLeast(1)
        b.checkProgress.setProgressCompat(0, false)

        for (chapter in chapters) {
            done++
            b.tvCheckProgress.text = "检查任务点 $done/$total：${chapter.title}"
            if (chapter.finished) {
                checks[chapter.id] = ChapterCheck(1, 1, 1, 1)
                b.checkProgress.setProgressCompat(done, true)
                continue
            }
            if (checks.containsKey(chapter.id)) {
                b.checkProgress.setProgressCompat(done, true)
                continue
            }
            val check = withContext(Dispatchers.IO) {
                runCatching { studyApi.checkChapter(course, chapter) }
                    .getOrElse { ChapterCheck(0, 0, 0, 0, notOpen = true) }
            }
            checks[chapter.id] = check
            items[index] = items[index].copy(checks = checks.toMap())
            adapter.submit(items.toList())
            b.checkProgress.setProgressCompat(done, true)
        }
        items[index] = items[index].copy(checking = false, checks = checks.toMap())
        val pruned = pruneSelection(index)
        adapter.submit(items.toList())
        if (pruned) {
            persistSelection()
            updateCoursesHeader()
        }
        StudyCheckStore.putCourse(accountId, course.key, checks.toMap())
        b.checkProgress.visibility = View.GONE
        b.tvCheckProgress.text = "检查完成：${course.title}（${total} 章）"
        LogStore.add("[刷课] 检查完成：${course.title}（${checks.size} 章）", true)
    }

    /** 确保章节已加载；返回章节列表 */
    private suspend fun ensureChaptersLoaded(index: Int, studyApi: StudyApi): List<StudyChapter> {
        val item = items.getOrNull(index) ?: return emptyList()
        item.chapters?.let { return it }

        items[index] = item.copy(expanded = true, loading = true)
        adapter.submit(items.toList())
        val loaded = withContext(Dispatchers.IO) {
            runCatching { studyApi.getChapters(item.course) }.getOrDefault(emptyList())
        }
        items[index] = items[index].copy(loading = false, chapters = loaded)
        adapter.submit(items.toList())
        return loaded
    }

    /** 全选/清空：只对已展开/已检查的课程生效，只选视频任务点未完成的章节 */
    private fun selectAll(checked: Boolean) {
        if (items.isEmpty()) return toast("请先拉取课程")
        if (!checked) {
            for (i in items.indices) items[i] = items[i].copy(selectedChapters = emptySet())
            adapter.submit(items.toList())
            persistSelection()
            updateCoursesHeader()
            return
        }
        if (StudyManager.isRunning()) return toast("刷课进行中")
        if (items.any { it.checking || it.loading }) return toast("正在检查课程，请稍候")

        var total = 0
        for (i in items.indices) {
            if (items[i].chapters.isNullOrEmpty()) continue
            val pending = items[i].chapters.orEmpty()
                .filter { !it.finished && items[i].checks[it.id]?.videoPending == true }
                .map { it.id }
                .toSet()
            items[i] = items[i].copy(selectedChapters = pending)
            total += pending.size
        }
        adapter.submit(items.toList())
        persistSelection()
        updateCoursesHeader()
        b.tvStatus.text = if (total > 0) {
            "已选中 $total 个视频未完成章节"
        } else {
            "请先展开课程检查任务点"
        }
        if (total == 0) toast("没有已检查的可刷章节")
    }

    private suspend fun ensureApi(): StudyApi? {
        api?.let { return it }
        val account = selectedAccount() ?: run {
            withContext(Dispatchers.Main) { toast("请先在「账号」页勾选刷课账号") }
            return null
        }
        val (session, err) = SignEngine.sessionFor(account)
        if (session == null) {
            withContext(Dispatchers.Main) { toast("登录失败：$err") }
            return null
        }
        val studyApi = StudyApi(session)
        api = studyApi
        return studyApi
    }

    private fun persistSelection() {
        Settings.studySelectedChapters = items.flatMap { item ->
            item.selectedChapters.map { "${item.course.key}#$it" }
        }.toSet()
    }

    private fun clearCourses(message: String) {
        items.clear()
        adapter.submit(emptyList())
        b.tvEmpty.visibility = View.VISIBLE
        b.tvStatus.text = message
        b.tvCheckProgress.visibility = View.GONE
        b.checkProgress.visibility = View.GONE
        updateCoursesHeader()
    }

    private fun updateCoursesHeader() {
        val courseCount = items.count { it.selectedChapters.isNotEmpty() }
        val chapterCount = items.sumOf { it.selectedChapters.size }
        b.tvCourses.text = when {
            items.isEmpty() -> "未拉取课程"
            else -> "已选 $chapterCount 章 / $courseCount 门课"
        }
    }

    // ------------------------------------------------------------ 启动

    private fun startStudy() {
        val account = selectedAccount() ?: run {
            toast("请先在「账号」页勾选刷课账号")
            return
        }
        val selection = items
            .filter { it.selectedChapters.isNotEmpty() }
            .associate { it.course.key to it.selectedChapters }
        val selectedCourses = items
            .filter { selection[it.course.key]?.isNotEmpty() == true }
            .map { it.course }
        if (selectedCourses.isEmpty()) return toast("请先勾选至少一个章节")
        if (StudyManager.isRunning()) return toast("刷课进行中")
        if (items.any { it.checking || it.loading }) return toast("正在检查任务点，请稍候")

        StudyManager.plan = StudyPlan(
            accountId = account.id,
            courses = selectedCourses,
            chapterSelection = selection,
            concurrency = Settings.studyConcurrency,
            speed = Settings.studySpeed
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServiceNow()
        }
    }

    private fun startServiceNow() {
        StudyService.start(requireContext())
        toast("刷课已开始，退到后台也会继续")
    }

    private fun stopStudy() {
        StudyManager.stop()
        StudyService.stop(requireContext())
    }

    // ------------------------------------------------------------ 设置 / 渲染

    private fun setConcurrency(value: Int) {
        Settings.studyConcurrency = value
        b.tvConcurrency.text = Settings.studyConcurrency.toString()
    }

    private fun cycleSpeed() {
        val current = Settings.studySpeed
        val index = speeds.indexOfFirst { abs(it - current) < 0.01f }
        val next = speeds[if (index < 0) 0 else (index + 1) % speeds.size]
        Settings.studySpeed = next
        b.btnSpeed.text = formatSpeed(next)
    }

    private fun formatSpeed(v: Float): String =
        if (abs(v - v.toInt()) < 0.01f) {
            String.format(Locale.US, "%.1fx", v)
        } else {
            String.format(Locale.US, "%.2fx", v)
        }

    private fun render(state: StudyState) {
        b.tvStatus.text = buildString {
            append(state.status)
            if (state.running && state.current.isNotBlank()) {
                append('\n')
                append(state.current)
            }
        }

        val showProgress = state.running || (state.finished && state.totalChapters > 0)
        b.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (state.totalChapters > 0) {
            b.progress.isIndeterminate = false
            b.progress.setProgressCompat(state.progress, true)
        } else if (state.running) {
            b.progress.isIndeterminate = true
        }

        b.btnFetch.isEnabled = !state.running
        b.btnStart.isEnabled = !state.running
        b.btnStop.isEnabled = state.running
    }

    private fun updateRunCard(state: StudyState, tasks: List<StudyTaskItem>) {
        val show = state.running || state.finished || tasks.isNotEmpty()
        b.cardRun.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        b.tvRunTitle.text = when {
            state.running -> "刷课任务 · 进行中"
            state.finished -> "刷课任务 · 已结束"
            else -> "刷课任务"
        }
        val doneCount = tasks.count { it.status == StudyTaskStatus.DONE }
        val failedCount = tasks.count { it.status == StudyTaskStatus.FAILED }
        b.tvRunSub.text = when {
            tasks.isNotEmpty() -> buildString {
                append("视频完成 $doneCount/${tasks.size}")
                if (failedCount > 0) append(" · 失败 $failedCount")
            }
            state.running -> state.status
            else -> "暂无视频任务"
        }
    }
}
