package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.xxt.sign.R
import com.xxt.sign.databinding.ItemQuizCourseBinding
import com.xxt.sign.study.ChapterCheck
import com.xxt.sign.study.StudyChapter
import com.xxt.sign.study.StudyCourse

data class QuizCourseItem(
    val course: StudyCourse,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val checking: Boolean = false,
    /** 做题任务正在跑，禁止改选择 */
    val running: Boolean = false,
    val chapters: List<StudyChapter>? = null,
    /** 章节 id -> 任务点检查结果 */
    val checks: Map<String, ChapterCheck> = emptyMap(),
    /** 本次检查失败的章节 id；失败不写入缓存 */
    val failedChapters: Set<String> = emptySet(),
    /** 已勾选的章节 id */
    val selectedChapters: Set<String> = emptySet(),
    /** 正在做题的章节 id */
    val activeChapterId: String? = null,
    /** 章节 id -> 「已提交 / 需人工 / 未完成」 */
    val chapterResults: Map<String, String> = emptyMap()
)

class QuizCourseAdapter(
    private val onToggleCourse: (StudyCourse, Boolean) -> Unit,
    private val onToggleChapter: (StudyCourse, StudyChapter, Boolean) -> Unit,
    private val onExpand: (StudyCourse) -> Unit,
    private val onRefresh: (StudyCourse) -> Unit
) : RecyclerView.Adapter<QuizCourseAdapter.VH>() {

    private var items: List<QuizCourseItem> = emptyList()

    fun submit(list: List<QuizCourseItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemQuizCourseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemQuizCourseBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: QuizCourseItem) {
            val chapters = item.chapters.orEmpty()
            val failedCount = item.failedChapters.size
            val checkedAll = chapters.isNotEmpty() &&
                chapters.all {
                    it.finished || item.checks.containsKey(it.id) || it.id in item.failedChapters
                }
            val pendingChapters = chapters.filter {
                !it.finished && ((item.checks[it.id]?.quizLeft ?: 0) > 0 || it.id in item.failedChapters)
            }
            val selectedCount = pendingChapters.count { it.id in item.selectedChapters }

            val status = when {
                item.activeChapterId != null -> "做题中…" to R.color.study_blue
                item.checking -> "检查测验中…" to R.color.muted
                failedCount > 0 -> "有 $failedCount 章检查失败" to R.color.err
                chapters.isEmpty() && item.chapters != null -> "无章节" to R.color.muted
                !checkedAll -> "未检查" to R.color.muted
                pendingChapters.isEmpty() -> "没有待做测验" to R.color.ok
                else -> "待做测验 $selectedCount/${pendingChapters.size} 章" to R.color.muted
            }
            b.tvQuizCourseCheck.setTextColor(b.root.context.getColor(status.second))
            if (b.tvQuizCourseCheck.text != status.first) {
                b.tvQuizCourseCheck.animate().cancel()
                b.tvQuizCourseCheck.alpha = 0.3f
                b.tvQuizCourseCheck.text = status.first
                b.tvQuizCourseCheck.animate().alpha(1f).setDuration(180).start()
            }

            b.cbQuizCourse.setOnCheckedChangeListener(null)
            b.cbQuizCourse.isChecked =
                pendingChapters.isNotEmpty() && selectedCount == pendingChapters.size
            b.cbQuizCourse.isEnabled = !item.loading && !item.checking && !item.running
            b.cbQuizCourse.setOnCheckedChangeListener { _, checked ->
                onToggleCourse(item.course, checked)
            }

            b.tvQuizCourseTitle.text = item.course.title
            val chapterText = when {
                item.loading -> "正在读取章节…"
                item.chapters == null -> "章节未加载"
                chapters.isEmpty() -> "没有章节"
                failedCount > 0 && pendingChapters.isEmpty() -> "检查失败 $failedCount 章"
                pendingChapters.isEmpty() -> "无待做测验"
                failedCount > 0 ->
                    "待做 ${selectedCount}/${pendingChapters.size} 章 · 失败 $failedCount 章"
                else -> "待做 ${selectedCount}/${pendingChapters.size} 章"
            }
            b.tvQuizCourseSub.text = listOfNotNull(
                item.course.teacher.takeIf { it.isNotBlank() },
                chapterText,
                "cpi=${item.course.cpi}".takeIf { item.course.cpi.isNotBlank() }
            ).joinToString(" · ")

            b.btnQuizExpand.text = if (item.expanded) "收起" else "章节"
            b.btnQuizExpand.isEnabled = !item.running
            b.btnQuizExpand.setOnClickListener { onExpand(item.course) }

            b.btnQuizRefresh.isEnabled = !item.loading && !item.checking && !item.running
            b.btnQuizRefresh.setOnClickListener { onRefresh(item.course) }

            b.llQuizChapters.visibility =
                if (item.expanded) LinearLayout.VISIBLE else LinearLayout.GONE
            if (!item.expanded) {
                b.llQuizChapters.removeAllViews()
                b.llQuizChapters.tag = null
                return
            }

            // 只有内容变了才重建章节行，否则每次状态刷新都会打断展开动画
            val signature = buildString {
                append(item.loading).append('|').append(item.running).append('|')
                append(item.activeChapterId).append('|')
                chapters.forEach { append(it.id).append(':').append(it.finished).append(':').append(it.jobCount).append(',') }
                append('|')
                item.checks.forEach { (k, v) ->
                    append(k).append(':').append(v.notOpen).append(':').append(v.quizTasks).append(':')
                        .append(v.quizDone).append(':').append(v.scanFailed).append(',')
                }
                append('|')
                item.failedChapters.sorted().forEach { append(it).append(',') }
                append('|')
                item.selectedChapters.sorted().forEach { append(it).append(',') }
                append('|')
                item.chapterResults.forEach { (k, v) -> append(k).append(':').append(v).append(',') }
            }
            if (b.llQuizChapters.tag == signature) return
            b.llQuizChapters.tag = signature
            b.llQuizChapters.removeAllViews()

            when {
                item.loading -> addHint("正在读取章节…")
                item.chapters.isNullOrEmpty() -> addHint("没有读到章节")
                else -> item.chapters.forEachIndexed { index, ch ->
                    addChapter(
                        index + 1,
                        ch,
                        item.checks[ch.id],
                        ch.id in item.failedChapters,
                        ch.id in item.selectedChapters,
                        item.running && item.activeChapterId == ch.id,
                        item.chapterResults[ch.id],
                        item.course
                    )
                }
            }
        }

        private fun addHint(text: String) {
            val ctx = b.root.context
            val tv = TextView(ctx).apply {
                this.text = text
                textSize = 12f
                setPadding(0, dp(6), 0, dp(6))
                setTextColor(ctx.getColor(R.color.muted))
            }
            b.llQuizChapters.addView(tv)
        }

        private fun addChapter(
            index: Int,
            chapter: StudyChapter,
            check: ChapterCheck?,
            failed: Boolean,
            checked: Boolean,
            active: Boolean,
            result: String?,
            course: StudyCourse
        ) {
            val ctx = b.root.context
            val (label, color) = when {
                chapter.finished -> "全部完成" to R.color.study_blue
                active -> "做题中…" to R.color.study_blue
                result != null -> result to (
                    if (result == "已提交") R.color.study_blue
                    else if (result == "需人工") R.color.err
                    else R.color.muted
                    )
                failed -> "检查失败" to R.color.err
                check == null -> "未检查" to R.color.muted
                check.notOpen -> "未开放" to R.color.muted
                check.scanFailed -> "扫描失败" to R.color.err
                check.quizTasks == 0 -> "无测验" to R.color.muted
                check.quizLeft == 0 -> "测验已做完" to R.color.study_blue
                else -> "测验待做 ${check.quizLeft}" to R.color.muted
            }
            val suffix = buildString {
                append(" · ").append(label)
                if (chapter.needUnlock) append(" · 需解锁")
                if (chapter.jobCount > 0) append(" · 任务点 ").append(chapter.jobCount)
            }

            val cb = MaterialCheckBox(ctx).apply {
                text = "$index. ${chapter.title}$suffix"
                textSize = 12f
                isChecked = checked
                isEnabled = !chapter.finished && !active &&
                    (check == null || check.quizLeft > 0 || failed)
                setPadding(dp(4), dp(2), 0, dp(2))
                setTextColor(ctx.getColor(color))
                setOnCheckedChangeListener { _, isChecked ->
                    onToggleChapter(course, chapter, isChecked)
                }
            }
            b.llQuizChapters.addView(cb)
        }

        private fun dp(v: Int): Int =
            (v * b.root.resources.displayMetrics.density).toInt()
    }
}
