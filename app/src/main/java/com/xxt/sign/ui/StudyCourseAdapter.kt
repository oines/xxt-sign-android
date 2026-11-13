package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.xxt.sign.R
import com.xxt.sign.databinding.ItemStudyCourseBinding
import com.xxt.sign.study.ChapterCheck
import com.xxt.sign.study.StudyChapter
import com.xxt.sign.study.StudyCourse

data class StudyCourseItem(
    val course: StudyCourse,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val checking: Boolean = false,
    val chapters: List<StudyChapter>? = null,
    /** 章节 id -> 任务点检查结果 */
    val checks: Map<String, ChapterCheck> = emptyMap(),
    /** 本次检查失败的章节 id；失败不写入缓存，避免被当成「未开放」 */
    val failedChapters: Set<String> = emptySet(),
    /** 已勾选的章节 id */
    val selectedChapters: Set<String> = emptySet()
)

class StudyCourseAdapter(
    private val onToggleCourse: (StudyCourse, Boolean) -> Unit,
    private val onToggleChapter: (StudyCourse, StudyChapter, Boolean) -> Unit,
    private val onExpand: (StudyCourse) -> Unit,
    private val onRefresh: (StudyCourse) -> Unit
) : RecyclerView.Adapter<StudyCourseAdapter.VH>() {

    private var items: List<StudyCourseItem> = emptyList()

    fun submit(list: List<StudyCourseItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStudyCourseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemStudyCourseBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: StudyCourseItem) {
            val chapters = item.chapters.orEmpty()
            val failedCount = item.failedChapters.size
            val checkedAll = chapters.isNotEmpty() &&
                chapters.all {
                    it.finished || item.checks.containsKey(it.id) || it.id in item.failedChapters
                }
            val allDone = checkedAll &&
                chapters.all { it.finished || item.checks[it.id]?.allComplete == true }
            val videoDone = checkedAll &&
                chapters.all { it.finished || item.checks[it.id]?.videoComplete == true }
            val videoPendingChapters = chapters.filter {
                !it.finished && (item.checks[it.id]?.videoPending == true || it.id in item.failedChapters)
            }
            val selectedVideoCount = videoPendingChapters.count { it.id in item.selectedChapters }

            val status = when {
                item.checking -> "检查任务点中…" to R.color.muted
                failedCount > 0 -> "有 $failedCount 章检查失败" to R.color.err
                chapters.isEmpty() && item.chapters != null -> "无章节" to R.color.muted
                !checkedAll -> "未检查" to R.color.muted
                allDone -> "全部任务点已完成" to R.color.study_blue
                videoDone -> "视频任务点已完成" to R.color.ok
                else -> "视频任务点未完成" to R.color.muted
            }
            b.tvTitle.setTextColor(b.root.context.getColor(status.second))
            b.tvCheck.setTextColor(b.root.context.getColor(status.second))
            b.tvCheck.text = status.first

            b.cbCourse.setOnCheckedChangeListener(null)
            b.cbCourse.isChecked =
                videoPendingChapters.isNotEmpty() && selectedVideoCount == videoPendingChapters.size
            b.cbCourse.isEnabled = !item.loading && !item.checking
            b.cbCourse.setOnCheckedChangeListener { _, checked ->
                onToggleCourse(item.course, checked)
            }

            b.tvTitle.text = item.course.title
            val chapterText = when {
                item.loading -> "正在读取章节…"
                item.chapters == null -> "章节未加载"
                chapters.isEmpty() -> "没有章节"
                failedCount > 0 && videoPendingChapters.isEmpty() -> "检查失败 $failedCount 章"
                videoPendingChapters.isEmpty() -> "无未完成视频"
                failedCount > 0 ->
                    "视频待刷 ${selectedVideoCount}/${videoPendingChapters.size} 章 · 失败 $failedCount 章"
                else -> "视频待刷 ${selectedVideoCount}/${videoPendingChapters.size} 章"
            }
            b.tvSub.text = listOfNotNull(
                item.course.teacher.takeIf { it.isNotBlank() },
                chapterText,
                "cpi=${item.course.cpi}".takeIf { item.course.cpi.isNotBlank() }
            ).joinToString(" · ")

            b.btnExpand.text = if (item.expanded) "收起" else "章节"
            b.btnExpand.setOnClickListener { onExpand(item.course) }

            b.btnRefresh.isEnabled = !item.loading && !item.checking
            b.btnRefresh.setOnClickListener { onRefresh(item.course) }

            b.llChapters.removeAllViews()
            b.llChapters.visibility = if (item.expanded) LinearLayout.VISIBLE else LinearLayout.GONE
            if (!item.expanded) return

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
            b.llChapters.addView(tv)
        }

        private fun addChapter(
            index: Int,
            chapter: StudyChapter,
            check: ChapterCheck?,
            failed: Boolean,
            checked: Boolean,
            course: StudyCourse
        ) {
            val ctx = b.root.context
            val (label, color) = when {
                chapter.finished -> "全部完成" to R.color.study_blue
                failed -> "检查失败" to R.color.err
                check == null -> "未检查" to R.color.muted
                check.notOpen -> "未开放" to R.color.muted
                check.allComplete -> "全部完成" to R.color.study_blue
                check.videoComplete -> "视频完成" to R.color.ok
                else -> "视频未完成" to R.color.muted
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
                isEnabled = !chapter.finished && (check == null || check.videoPending || failed)
                setPadding(dp(4), dp(2), 0, dp(2))
                setTextColor(ctx.getColor(color))
                setOnCheckedChangeListener { _, isChecked ->
                    onToggleChapter(course, chapter, isChecked)
                }
            }
            b.llChapters.addView(cb)
        }

        private fun dp(v: Int): Int =
            (v * b.root.resources.displayMetrics.density).toInt()
    }
}
