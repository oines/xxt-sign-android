package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.View
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

            // 没数据就什么都不显示，别写「还没体检」这种废话
            val hasData = audit != null && audit.taskPoints > 0
            b.tvCourseNumbers.visibility = if (hasData) View.VISIBLE else View.GONE
            b.progressCourse.visibility = if (hasData) View.VISIBLE else View.GONE
            if (hasData) {
                b.tvCourseNumbers.text = "任务点 ${audit!!.doneTaskPoints}/${audit.taskPoints}"
                b.progressCourse.setProgressCompat(audit.doneTaskPoints * 100 / audit.taskPoints, false)
            }

            // 只有真的在跑 / 卡住 / 要你动手时才写一行状态，其余留白
            val (text, color) = when {
                run != null && run.running -> run.message to R.color.primary
                run != null && run.phase == CourseRunQueue.Phase.STUCK -> run.message to R.color.err
                audit != null && audit.humanPending > 0 -> "有要你本人处理的事" to R.color.err
                else -> "" to R.color.muted
            }
            b.tvCourseState.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            b.tvCourseState.text = text
            b.tvCourseState.setTextColor(b.root.context.getColor(color))
        }
    }
}
