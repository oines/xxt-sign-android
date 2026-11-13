package com.xxt.sign.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.FragmentHomeworkBinding
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyCourseStore
import com.xxt.sign.work.HomeworkApi
import com.xxt.sign.work.HomeworkCourse
import com.xxt.sign.work.HomeworkItem
import com.xxt.sign.work.HomeworkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「作业」页（做题页右上角切换过来）。
 *
 * 列出所有**未交**作业；点开详情可以作答，暂存 / 交卷都由用户手动触发，
 * AI 建议复用章节测验的题库 / 大模型配置。
 * 课程列表复用「刷课」页的缓存（[StudyCourseStore]），账号复用 [Settings.studyAccountId]。
 */
class HomeworkFragment : Fragment() {

    private var _b: FragmentHomeworkBinding? = null
    private val b get() = _b!!

    private val adapter = HomeworkAdapter(::openDetail, ::confirmRedo)
    private var loadJob: Job? = null
    private var redoJob: Job? = null
    private var accountId = ""
    private var api: HomeworkApi? = null

    /**
     * 当前选中的课程（多选，`courseId:clazzId`）。空 = 全部课程。
     *
     * 直接从 [Settings.homeworkCourseFilter] 读，**持久化**：下次进来还是这几个课程。
     * 同时也是刷新范围 —— 只拉这些课程的作业，不再把全部 14 门课扫一遍（慢的根源）。
     */
    private val courseFilterKeys: Set<String> get() = Settings.homeworkCourseFilter

    /** 未过滤的完整列表，过滤时从它里面筛 */
    private var allItems: List<HomeworkItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentHomeworkBinding.inflate(inflater, container, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.homeworkRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.homeworkRecycler.adapter = adapter
        b.btnHomeworkRefresh.setOnClickListener { load(force = true) }
        b.btnHomeworkFilter.setOnClickListener { showCourseFilter() }
        b.btnHomeworkSettings.setOnClickListener { QuizSettingsDialog.show(this) }

        val acc = AccountStore.get(Settings.studyAccountId)
        accountId = acc?.id.orEmpty()
        refreshHeader(acc)
        if (acc == null) {
            showEmpty("先到「账号」页勾选一个账号，再回来看作业。")
        } else {
            load(force = false)
        }
    }

    override fun onResume() {
        super.onResume()
        val acc = AccountStore.get(Settings.studyAccountId)
        refreshHeader(acc)
        val id = acc?.id.orEmpty()
        if (id != accountId) {
            accountId = id
            // 换账号了，之前选的课程过滤没有意义
            Settings.homeworkCourseFilter = emptySet()
            allItems = emptyList()
            if (acc == null) {
                showEmpty("先到「账号」页勾选一个账号，再回来看作业。")
            } else {
                load(force = true)
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        redoJob?.cancel()
        _b = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 加载

    private fun load(force: Boolean) {
        if (loadJob?.isActive == true) return
        val acc = AccountStore.get(Settings.studyAccountId)
        if (acc == null) {
            showEmpty("先到「账号」页勾选一个账号，再回来看作业。")
            return
        }
        if (!force && cacheAccountId == acc.id && cacheItems.isNotEmpty() &&
            System.currentTimeMillis() - cacheAt < CACHE_MS
        ) {
            allItems = cacheItems
            applyFilter()
            updateEmptyState()
            val unsent = cacheItems.count { it.unsubmitted }
            val redoable = cacheItems.count { it.redoable }
            status("共 ${cacheItems.size} 份需要处理的作业：未交 $unsent 份，可重做 $redoable 份（缓存）")
            return
        }
        refreshHeader(acc)
        setBusy(true, "正在准备账号…")
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (session, err) = SignEngine.sessionFor(acc)
                if (session == null) {
                    setBusy(false, "")
                    showEmpty("账号不可用：${err.ifBlank { "请到账号页重新登录" }}")
                    return@launch
                }

                var courses = StudyCourseStore.load(acc.id)?.courses.orEmpty()
                if (courses.isEmpty()) {
                    status("正在获取课程列表…")
                    courses = runCatching { StudyApi(session).getCourses() }.getOrDefault(emptyList())
                    if (courses.isNotEmpty()) {
                        StudyCourseStore.saveCourses(
                            acc.id,
                            courses,
                            StudyCourseStore.load(acc.id)?.chapters.orEmpty()
                        )
                    }
                }
                if (courses.isEmpty()) {
                    setBusy(false, "")
                    showEmpty("没有读到课程。先到「刷课」页点「获取课程」。")
                    return@launch
                }

                // 只刷新选中的课程。
                //
                // 速度问题就出在这里：一门课要串行拉 task-list 分页 + 每份作业 resolve + 逐题 scan，
                // 14 门课全扫一遍要好几分钟。按课程过滤持久化之后，平时只看 1–2 门课，
                // 刷新时间直接降到十分之一。
                val filter = courseFilterKeys
                val toScan = if (filter.isEmpty()) {
                    courses
                } else {
                    courses.filter { "${it.courseId}:${it.clazzId}" in filter }
                }
                if (toScan.isEmpty()) {
                    // 选中的课程一门都对不上课程列表 —— 多半是课程缓存过期（换学期/换了账号的课）。
                    // **不要动用户的选择**，只把这次刷新降级成全部课程，并在状态栏说清楚。
                    // （之前这里直接 Settings.homeworkCourseFilter = emptySet()，
                    //   用户辛苦选的课被静默清掉，表现就是「选了也没用」。）
                    LogStore.add(
                        "[作业] 选中的 ${filter.size} 门课程都不在课程列表里（课程缓存可能过期），本次按全部课程刷新",
                        false
                    )
                    status("选中的课程不在当前课程列表里（可能课程缓存过期），本次先刷新全部课程")
                }
                val scanList = if (toScan.isEmpty()) courses else toScan

                val api = HomeworkApi(session)
                this@HomeworkFragment.api = api
                val found = mutableListOf<HomeworkItem>()
                var failed = 0
                scanList.forEachIndexed { index, c ->
                    status(
                        if (scanList.size == courses.size) "正在读取作业 ${index + 1}/${scanList.size}：${c.title}"
                        else "正在读取选中课程 ${index + 1}/${scanList.size}：${c.title}"
                    )
                    val course = HomeworkCourse(c.courseId, c.clazzId, c.cpi, c.title)
                    val items = runCatching { api.list(course) }.getOrNull()
                    if (items == null) {
                        failed++
                    } else {
                        for (item in items) {
                            if (item.unsubmitted) {
                                // 未交：先解析作答入口，再扫一遍题型；没有选择题的作业不显示
                                val r = runCatching { api.resolve(course, item.workId) }.getOrNull()
                                val scanned = withScan(api, item, r?.doHomeWorkUrl)
                                if (scanned.hasChoice) found += scanned
                                continue
                            }
                            if (!item.submitted) continue
                            // 「待批阅」= 已经交了、等老师批。用户明确要求不显示，直接跳过。
                            if (item.isPendingReview) continue
                            // 已完成：再查一次状态页，拿分数和「重做」入口
                            val r = runCatching { api.resolve(course, item.workId) }.getOrNull()
                            val scored = item.copy(
                                score = r?.score.orEmpty(),
                                redoable = r?.redoable == true,
                                answerId = r?.answerId.orEmpty().ifBlank {
                                    HomeworkParser.workAnswerId(r?.doHomeWorkUrl.orEmpty())
                                }
                            )
                            // 分数不满、或还没出分的，继续显示在列表里
                            if (!scored.belowFull && scored.score.isNotBlank()) continue
                            // 已交卷的作业进去是「查看详情」页，无法按题扫描题型；
                            // 需要重做的都保留显示，题型由详情页自己判断。
                            found += scored
                        }
                    }
                }
                found.sortWith(compareBy({ !it.unsubmitted }, { it.course.title }))

                cacheAccountId = acc.id
                cacheAt = System.currentTimeMillis()
                cacheItems = found
                allItems = found
                applyFilter()
                updateEmptyState()
                setBusy(false, "")
                val unsent = found.count { it.unsubmitted }
                val redoable = found.count { it.redoable }
                val tail = if (failed > 0) "（$failed 门课读取失败）" else ""
                status("共 ${found.size} 份需要处理的作业：未交 $unsent 份，可重做 $redoable 份$tail")
                LogStore.add(
                    "[作业] ${acc.display}：未交 $unsent 份，可重做 $redoable 份$tail"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, "")
                showEmpty("读取失败：${e.message ?: e.javaClass.simpleName}")
                Toast.makeText(requireContext(), "读取作业失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 扫描题型；扫描失败就保持原样（宁可多显示，也不误伤） */
    private suspend fun withScan(
        api: HomeworkApi,
        item: HomeworkItem,
        doHomeWorkUrl: String?
    ): HomeworkItem {
        if (doHomeWorkUrl.isNullOrBlank()) return item
        val scan = runCatching { api.scanChoices(doHomeWorkUrl) }.getOrNull() ?: return item
        return item.copy(
            choiceCount = scan.choiceCount,
            questionCount = scan.total,
            allChoice = scan.allChoice,
            scanned = true
        )
    }

    // ------------------------------------------------------------ 课程过滤

    /**
     * 按当前选中的课程过滤后提交给列表；空集合 = 全部课程。
     *
     * 过滤是**本地筛 + 决定刷新范围**双重作用 —— 选中的课程会持久化，
     * 下次刷新只拉这些课的作业（见 [load]）。
     *
     * **绝不在这里"自动清理"用户的选择**。曾经写过一段「选中的课程已经不在
     * allItems 里就清掉」的逻辑，结果 allItems 只是「当前有作业的课程」子集，
     * 用户选了一门暂时没作业的课就被整段清空、退回「全部课程」——
     * 表现就是「只勾一门，确定后还是全部」。用户的选择只能由用户自己改。
     */
    private fun applyFilter() {
        val keys = Settings.homeworkCourseFilter
        val filtered = if (keys.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.course.key in keys }
        }
        adapter.submit(filtered)
        updateFilterButton()
    }

    /** 课程完整清单（缓存里的所有课程，不只是有作业的） */
    private fun allKnownCourses(): List<HomeworkCourse> =
        StudyCourseStore.load(accountId)?.courses.orEmpty()
            .map { HomeworkCourse(it.courseId, it.clazzId, it.cpi, it.title) }

    private fun updateFilterButton() {
        val bb = _b ?: return
        val keys = Settings.homeworkCourseFilter
        bb.btnHomeworkFilter.text = when {
            keys.isEmpty() -> "全部课程"
            keys.size == 1 -> {
                val key = keys.first()
                val title = allKnownCourses().firstOrNull { it.key == key }?.title
                    ?: allItems.firstOrNull { it.course.key == key }?.course?.title
                title ?: "1 门课程"
            }
            else -> "${keys.size} 门课程"
        }
    }

    /**
     * 课程过滤弹窗：**多选**，并且持久化。
     *
     * 课程来源优先用缓存的全量课程（这样第一次进页面、或某门课暂时没作业也能选），
     * 再并上已加载作业里的课程做兜底。
     */
    private fun showCourseFilter() {
        val ctx = context ?: return

        val cached = allKnownCourses()
        val courses = (cached + allItems.map { it.course }).distinctBy { it.key }
        if (courses.isEmpty()) {
            Toast.makeText(ctx, "还没有课程列表，先到「刷课」页获取课程", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = courses.map { it.title }.toTypedArray()
        val current = Settings.homeworkCourseFilter

        // 用可变集合跟踪勾选，而不是 BooleanArray + 事后反查索引 ——
        // 后者在「全选/全不选」的边界上容易把选择丢掉。
        val picked = LinkedHashSet<String>()
        if (current.isEmpty()) {
            picked.addAll(courses.map { it.key })   // 全部 = 弹窗里显示成全选
        } else {
            picked.addAll(current.filter { key -> courses.any { it.key == key } })
        }
        val checked = BooleanArray(courses.size) { courses[it].key in picked }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle("选择要显示/刷新的课程（可多选）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val key = courses[which].key
                if (isChecked) picked.add(key) else picked.remove(key)
            }
            .setPositiveButton("确定", null)   // 先不挂回调，见下面 create() 之后重设
            .setNeutralButton("全部课程", null)
            .setNegativeButton("取消", null)

        val dialog = builder.create()
        dialog.show()

        // 「确定」：只按用户勾的结果保存，**不再和课程总数比较**。
        // 之前写的是 if (sel.size == courses.size) emptySet()，用户只勾一门、
        // 而候选恰好只有一门时就会被判成「全部」。
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val sel = picked.toSet()
            Settings.homeworkCourseFilter = sel
            dialog.dismiss()
            applyFilter()
            updateEmptyState()
            status(
                when {
                    sel.isEmpty() -> "未选择课程：已按全部课程显示 ${adapter.itemCount} 份作业"
                    sel.size == 1 -> "已选 1 门课程「${courses.first { it.key == sel.first() }.title}」，" +
                        "共 ${adapter.itemCount} 份作业（下次只刷新这门）"
                    else -> "已选 ${sel.size} 门课程，共 ${adapter.itemCount} 份作业（下次只刷新这些）"
                }
            )
        }

        // 「全部课程」：显式清空，语义明确
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            Settings.homeworkCourseFilter = emptySet()
            dialog.dismiss()
            applyFilter()
            updateEmptyState()
            status("已切回全部课程，共 ${adapter.itemCount} 份作业")
        }
    }

    // ------------------------------------------------------------ UI

    private fun refreshHeader(acc: com.xxt.sign.core.Account?) {
        val bb = _b ?: return
        bb.tvHomeworkAccount.text =
            if (acc == null) getString(R.string.title_homework) + "账号：未选择"
            else "${getString(R.string.title_homework)}账号：${acc.display}"
        bb.tvHomeworkHint.text = if (acc == null) {
            "先在「账号」页勾选账号，再刷新作业列表。题目和 AI 建议只读取；暂存/交卷需要你在详情页手动点。"
        } else {
            "只读取题目和 AI 建议；暂存/交卷由你在详情页手动操作。" +
                "右上角「按课程过滤」可多选课程并记住，以后只刷新选中的课程。"
        }
    }

    private fun setBusy(busy: Boolean, text: String) {
        val bb = _b ?: return
        bb.homeworkProgress.visibility = if (busy) View.VISIBLE else View.GONE
        bb.btnHomeworkRefresh.isEnabled = !busy
        if (text.isNotBlank()) status(text)
    }

    private fun status(text: String) {
        val bb = _b ?: return
        bb.tvHomeworkStatus.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        bb.tvHomeworkStatus.text = text
    }

    private fun showEmpty(text: String) {
        val bb = _b ?: return
        adapter.submit(emptyList())
        bb.tvHomeworkEmpty.text = text
        bb.tvHomeworkEmpty.visibility = View.VISIBLE
    }

    private fun updateEmptyState() {
        val bb = _b ?: return
        if (adapter.itemCount == 0) {
            bb.tvHomeworkEmpty.text = if (courseFilterKeys.isNotEmpty()) {
                "选中的课程下没有需要处理的作业，点右上角可以改选课程。"
            } else {
                "没有需要处理的作业（未交的、以及分数不满可重做的都会显示在这里）。"
            }
            bb.tvHomeworkEmpty.visibility = View.VISIBLE
        } else {
            bb.tvHomeworkEmpty.visibility = View.GONE
        }
    }

    private fun openDetail(item: HomeworkItem) {
        val ctx = context ?: return
        startActivity(
            Intent(ctx, HomeworkDetailActivity::class.java).apply {
                putExtra(HomeworkDetailActivity.EXTRA_WORK_ID, item.workId)
                putExtra(HomeworkDetailActivity.EXTRA_COURSE_ID, item.course.courseId)
                putExtra(HomeworkDetailActivity.EXTRA_CLASS_ID, item.course.clazzId)
                putExtra(HomeworkDetailActivity.EXTRA_CPI, item.course.cpi)
                putExtra(HomeworkDetailActivity.EXTRA_COURSE_TITLE, item.course.title)
                putExtra(HomeworkDetailActivity.EXTRA_TITLE, item.title)
                putExtra(HomeworkDetailActivity.EXTRA_ALL_CHOICE, item.allChoice)
                putExtra(HomeworkDetailActivity.EXTRA_SCANNED, item.scanned)
                putExtra(HomeworkDetailActivity.EXTRA_CHOICE_COUNT, item.choiceCount)
                putExtra(HomeworkDetailActivity.EXTRA_QUESTION_COUNT, item.questionCount)
            }
        )
    }

    // ------------------------------------------------------------ 重做

    private fun confirmRedo(item: HomeworkItem) {
        val ctx = context ?: return
        val scoreText = if (item.score.isNotBlank()) "当前成绩 ${item.score} 分。" else ""
        MaterialAlertDialogBuilder(ctx)
            .setTitle("重做作业")
            .setMessage(
                "${scoreText}重做会覆盖前一次作答记录，作业成绩按最高分计算。\n\n" +
                    "确定重做《${item.title}》吗？"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("重做") { _, _ -> doRedo(item) }
            .show()
    }

    private fun doRedo(item: HomeworkItem) {
        val hw = api
        if (hw == null) {
            Toast.makeText(requireContext(), "还没读取完列表，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (item.answerId.isBlank()) {
            Toast.makeText(requireContext(), "缺少作答记录 id，先点「刷新作业列表」", Toast.LENGTH_SHORT).show()
            return
        }
        if (redoJob?.isActive == true) return
        val acc = AccountStore.get(Settings.studyAccountId) ?: return
        setBusy(true, "正在重做《${item.title}》…")
        redoJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = hw.redo(item.course, item.workId, item.answerId)
                if (!r.ok) {
                    setBusy(false, "")
                    Toast.makeText(requireContext(), "重做失败：${r.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
                LogStore.add("[作业] ${acc.display}：重做 ${item.course.title}《${item.title}》")
                cacheAt = 0L
                Toast.makeText(requireContext(), "已重做，正在打开…", Toast.LENGTH_SHORT).show()
                load(force = true)
                openDetail(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, "")
                Toast.makeText(
                    requireContext(),
                    "重做失败：${e.message ?: e.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private companion object {
        const val CACHE_MS = 10 * 60 * 1000L
        var cacheAccountId = ""
        var cacheAt = 0L
        var cacheItems: List<HomeworkItem> = emptyList()
    }
}
