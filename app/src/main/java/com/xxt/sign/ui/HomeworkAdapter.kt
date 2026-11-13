package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.R
import com.xxt.sign.databinding.ItemHomeworkBinding
import com.xxt.sign.work.HomeworkItem

/**
 * 「作业」列表适配器：一条 = 一份作业。
 *
 * 既显示「未交」的作业，也显示分数不满、服务端还允许「重做」的已完成作业。
 */
class HomeworkAdapter(
    private val onClick: (HomeworkItem) -> Unit,
    private val onRedo: (HomeworkItem) -> Unit
) : RecyclerView.Adapter<HomeworkAdapter.VH>() {

    private val items = mutableListOf<HomeworkItem>()

    fun submit(list: List<HomeworkItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemHomeworkBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val b: ItemHomeworkBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HomeworkItem) {
            b.tvHomeworkCourse.text = item.course.title
            b.tvHomeworkTitle.text = item.title
            b.tvHomeworkStatus.text = when {
                item.unsubmitted -> item.status.ifBlank { "未交" }
                item.score.isNotBlank() -> "${item.status} · ${item.score} 分"
                else -> item.status
            }
            b.tvHomeworkStatus.setTextColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (item.unsubmitted) R.color.err else R.color.muted
                )
            )
            b.tvHomeworkDeadline.text = item.deadline
            b.tvHomeworkDeadline.visibility =
                if (item.deadline.isBlank()) View.GONE else View.VISIBLE
            b.tvHomeworkRedo.visibility = if (item.redoable) View.VISIBLE else View.GONE
            b.tvHomeworkRedo.setOnClickListener { onRedo(item) }
            b.root.setOnClickListener { onClick(item) }
        }
    }
}
