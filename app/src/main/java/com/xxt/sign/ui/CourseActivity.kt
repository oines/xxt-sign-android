package com.xxt.sign.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.ActivityCourseBinding
import com.xxt.sign.study.CourseAuditStore
import com.xxt.sign.study.ChapterRow
import com.xxt.sign.study.CourseRunQueue
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.study.StudyCourseStore
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

/**
 * 课程页：一个 开始/暂停 按钮 + 进度，别的都不放。
 *
 * 文案只有两种字：开始 / 暂停。状态行只说人话（正在刷视频、正在做测验、等你到教室签个到）。
 */
class CourseActivity : AppCompatActivity() {

    private lateinit var b: ActivityCourseBinding
    private var course: StudyCourse? = null
    private val chapterAdapter = ChapterRowAdapter()

    /** 已经自动滚过的章：只在「正在刷的章变了」时滚，不跟用户抢滚动手势 */
    private var lastScrolledChapter = ""
    private var lastLoggedSignature = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCourseBinding.inflate(layoutInflater)
        setContentView(b.root)

        val key = intent.getStringExtra(EXTRA_COURSE_KEY).orEmpty()
        val account = AccountStore.get(Settings.studyAccountId)
        course = account?.let { StudyCourseStore.load(it.id) }
            ?.courses?.firstOrNull { it.key == key }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.listChapters.layoutManager = LinearLayoutManager(this)
        b.listChapters.adapter = chapterAdapter
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        val c = course
        if (c == null) {
            b.toolbar.title = "没找到这门课"
            b.btnToggle.isEnabled = false
            return
        }
        b.toolbar.title = c.title
        if (AccountStore.get(Settings.studyAccountId) == null) {
            // 没有账号就别让按钮看起来能点
            b.tvStateLine.text = "先去「账号」页加一个账号"
            b.btnToggle.isEnabled = false
        } else {
            b.btnToggle.setOnClickListener { CourseRunQueue.toggle(c) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CourseRunQueue.states.collect { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = course ?: return
        val run = CourseRunQueue.stateOf(c.key)
        val audit = CourseAuditStore.loadCurrent().firstOrNull { it.courseId == c.courseId }

        val done = run?.donePoints ?: audit?.doneTaskPoints ?: 0
        val total = run?.totalPoints ?: audit?.taskPoints ?: 0

        // 一行就够：在跑就写在跑什么；没跑就只说进度，不再多写一句「没在跑」
        val runningText = when {
            run != null && run.running -> run.message
            run != null && run.phase == CourseRunQueue.Phase.STUCK -> run.message
            else -> ""
        }
        val progressText = if (total > 0) "任务点 $done/$total" else ""
        val line = listOf(runningText, progressText).filter { it.isNotBlank() }.joinToString(" · ")
        b.tvStateLine.visibility = if (line.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvStateLine.text = line

        // 进度条：0 就画 0%（之前底槽 + 尾端小圆点看起来像满格）
        val percent = if (total > 0) (done * 100L / total).toInt().coerceIn(0, 100) else 0
        b.tvProgressText.visibility = android.view.View.VISIBLE
        b.tvProgressText.text = if (total > 0) {
            "任务点 $done/$total · $percent%"
        } else {
            "还没体检过，点开始"
        }
        b.progress.visibility = if (total > 0) android.view.View.VISIBLE else android.view.View.GONE
        b.progress.setProgressCompat(percent, false)

        // 逐章列表：数据来自体检结果 + 本地章节缓存，不额外请求；正在跑的那章高亮
        val current = run?.currentChapter.orEmpty()
        val runningNow = run?.running == true && current.isNotBlank()
        val cachedChapters = AccountStore.get(Settings.studyAccountId)
            ?.let { StudyCourseStore.load(it.id) }
            ?.chapters?.get(c.key).orEmpty()
        val chapterRows = mergeChapterRows(audit?.chapterRows.orEmpty(), cachedChapters, current, runningNow)
        chapterAdapter.submit(chapterRows)

        val finishedChapters = chapterRows.count { it.state == ChapterRow.State.DONE }
        b.tvChapterHeader.visibility = if (chapterRows.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvChapterHeader.text = "章节 · $finishedChapters/${chapterRows.size} 章已完成"

        // 真机排障：列表为空到底是没体检、课程 id 对不上，还是缓存里也没章节
        val signature = "${chapterRows.size}/${audit?.chapterRows?.size}/${cachedChapters.size}/$current"
        if (signature != lastLoggedSignature) {
            lastLoggedSignature = signature
            LogStore.add(
                "[课程页] ${c.title} 章节列表 ${chapterRows.size} 行" +
                    "（体检 ${audit?.chapterRows?.size ?: 0} / 缓存 ${cachedChapters.size}）" +
                    "，正在刷：${current.ifBlank { "—" }}",
                chapterRows.isNotEmpty()
            )
            if (chapterRows.isEmpty()) {
                LogStore.add("[课程页] 章节列表是空的：体检结果里有这门课的章节行吗？课程 id=${c.courseId}", false)
            }
        }

        // 当前正在刷的那章自动滚到可见位置（只跟换章时滚一次）
        if (runningNow && current != lastScrolledChapter) {
            val index = chapterRows.indexOfFirst { it.running }
            if (index >= 0) {
                lastScrolledChapter = current
                b.listChapters.post {
                    (b.listChapters.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(index, b.listChapters.height / 3)
                }
            }
        }

        val needs = run?.needsYou.orEmpty().ifBlank { if ((audit?.humanPending ?: 0) > 0) "有要你本人处理的事" else "" }
        b.tvNeedsYou.visibility = if (needs.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvNeedsYou.text = needs

        val running = run?.running == true || run?.phase == CourseRunQueue.Phase.QUEUED
        b.btnToggle.text = if (running) "暂停" else "开始"

        // 运行期间让屏幕别自己灭（锁屏测试用：不按电源键就不会黑）；暂停/跑完立即清掉
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 逐章列表：体检行优先（状态最准），体检缺的章用本地章节缓存补，
     * 两边都没有才真的空。零额外请求。
     */
    private fun mergeChapterRows(
        audited: List<ChapterRow>,
        cached: List<com.xxt.sign.study.StudyChapter>,
        current: String,
        runningNow: Boolean
    ): List<ChapterRowAdapter.Row> {
        val byTitle = audited.associateBy { it.title }
        val titles = LinkedHashSet<String>()
        audited.forEach { titles.add(it.title) }
        cached.forEach { titles.add(it.title) }
        return titles.map { title ->
            val row = byTitle[title]
            val ch = cached.firstOrNull { it.title == title }
            val state = row?.state ?: when {
                ch == null -> ChapterRow.State.UNCHECKED
                ch.finished -> ChapterRow.State.DONE
                ch.needUnlock -> ChapterRow.State.LOCKED
                else -> ChapterRow.State.TODO
            }
            ChapterRowAdapter.Row(
                title = title,
                done = row?.done ?: 0,
                total = row?.total ?: ch?.jobCount ?: 0,
                state = state,
                running = runningNow && title == current
            )
        }
    }

    companion object {
        const val EXTRA_COURSE_KEY = "course_key"
    }
}
