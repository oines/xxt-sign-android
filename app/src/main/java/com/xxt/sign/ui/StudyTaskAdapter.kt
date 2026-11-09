package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.R
import com.xxt.sign.databinding.ItemStudyTaskBinding
import com.xxt.sign.study.StudyTaskItem
import com.xxt.sign.study.StudyTaskStatus

class StudyTaskAdapter : RecyclerView.Adapter<StudyTaskAdapter.VH>() {

    private var items: List<StudyTaskItem> = emptyList()

    fun submit(list: List<StudyTaskItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStudyTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemStudyTaskBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: StudyTaskItem) {
            val ctx = b.root.context
            b.tvTitle.text = item.name
            b.tvSub.text = "${item.courseTitle} · ${item.chapterTitle}"
            b.progress.setProgressCompat(item.progress.coerceIn(0, 100), false)
            b.progress.visibility = if (item.status == StudyTaskStatus.PENDING) View.GONE else View.VISIBLE

            val (label, color) = when (item.status) {
                StudyTaskStatus.PENDING -> "等待中" to R.color.muted
                StudyTaskStatus.RUNNING -> "进行中" to R.color.primary
                StudyTaskStatus.DONE -> "已完成" to R.color.ok
                StudyTaskStatus.FAILED -> "失败" to R.color.err
                StudyTaskStatus.SKIPPED -> "已跳过" to R.color.muted
            }
            val detail = if (item.message.isBlank()) label else "$label · ${item.message}"
            b.tvStatus.text = detail
            b.tvStatus.setTextColor(ctx.getColor(color))
        }
    }
}
