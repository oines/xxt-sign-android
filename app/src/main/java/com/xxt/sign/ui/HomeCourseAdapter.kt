package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.R
import com.xxt.sign.databinding.ItemHomeCourseBinding
import com.xxt.sign.study.CourseAudit
import com.xxt.sign.study.CourseRunQueue
import com.xxt.sign.study.StudyCourse

/** 首页课程行：课程名 + 视频/测验进度 + 状态（人话） */
class HomeCourseAdapter(
    private val onOpen: (StudyCourse) -> Unit
) : RecyclerView.Adapter<HomeCourseAdapter.VH>() {

    private val courses = mutableListOf<StudyCourse>()
    private var audits: Map<String, CourseAudit> = emptyMap()
    private var runs: Map<String, CourseRunQueue.State> = emptyMap()

    fun submit(courses: List<StudyCourse>, audits: List<CourseAudit>, runs: Map<String, CourseRunQueue.State>) {
        this.courses.clear()
        this.courses.addAll(courses)
        this.audits = audits.associateBy { it.courseId }
        this.runs = runs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHomeCourseBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = courses.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(courses[position])

    inner class VH(private val b: ItemHomeCourseBinding) : RecyclerView.ViewHolder(b.root) {
        private var course: StudyCourse? = null

        init {
            b.root.setOnClickListener { course?.let(onOpen) }
        }

        fun bind(item: StudyCourse) {
            course = item
            val run = runs[item.key]
            val audit = audits[item.courseId]
            b.tvCourseTitle.text = item.title

            // 口径：已完成任务点 / 总任务点（带 jobid 的 attachment），不用 isPassed
            b.tvCourseNumbers.text = if (audit == null || audit.taskPoints == 0) {
                "还没体检，点开始会先看看还差什么"
            } else {
                val videoLeft = audit.videoPending
                val quizLeft = audit.quizPending
                "任务点 ${audit.doneTaskPoints}/${audit.taskPoints}" +
                    (if (videoLeft > 0) " · 视频 $videoLeft 章待刷" else "") +
                    (if (quizLeft > 0) " · 测验约 $quizLeft 份待做" else "")
            }

            val pct = when {
                audit == null || audit.taskPoints <= 0 -> 0
                else -> audit.doneTaskPoints * 100 / audit.taskPoints
            }
            b.progressCourse.setProgressCompat(pct, false)

            val (text, color) = when {
                run != null && run.running -> run.message to R.color.primary
                run != null && run.phase == CourseRunQueue.Phase.STUCK -> "卡住了：${run.message}" to R.color.err
                audit != null && audit.humanPending > 0 ->
                    "有 ${audit.humanPending} 处要你本人处理" to R.color.err
                audit != null && audit.allGreen -> "全绿了" to R.color.ok
                else -> "没在跑" to R.color.muted
            }
            b.tvCourseState.text = text
            b.tvCourseState.setTextColor(b.root.context.getColor(color))
        }
    }
}
