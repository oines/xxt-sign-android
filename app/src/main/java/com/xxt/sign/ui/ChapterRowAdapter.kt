package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.R
import com.xxt.sign.databinding.ItemChapterRowBinding
import com.xxt.sign.study.ChapterRow

/**
 * 课程页的逐章进度列表：一行一章，右边是状态，正在跑的那章高亮。
 *
 * 数据全部来自 [com.xxt.sign.study.CourseAudit]（体检结果），不额外发请求；
 * 「进行中」由 [com.xxt.sign.study.CourseRunQueue] 的 currentChapter 决定。
 */
class ChapterRowAdapter : RecyclerView.Adapter<ChapterRowAdapter.Holder>() {

    /** 渲染用的行：体检结论 + 运行时状态 */
    data class Row(
        val title: String,
        val done: Int,
        val total: Int,
        val state: ChapterRow.State,
        val running: Boolean
    )

    private var rows: List<Row> = emptyList()

    fun submit(newRows: List<Row>) {
        if (newRows == rows) return
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemChapterRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(b)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    class Holder(private val b: ItemChapterRowBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(row: Row) {
            val ctx = b.root.context
            // 只渲染状态：逐章的数字口径跟顶部（平台 X/Y）还没完全统一，
            // 摆出来只会跟 392 打架，不如不摆
            b.tvChapterName.text = row.title

            val (label, colorRes) = when {
                row.running -> "进行中" to R.color.primary
                row.state == ChapterRow.State.DONE -> "已完成" to R.color.ok
                row.state == ChapterRow.State.LOCKED -> "未开放" to R.color.muted
                row.state == ChapterRow.State.UNCHECKED -> "没检查到" to R.color.muted
                else -> "未开始" to R.color.muted
            }
            b.tvChapterState.text = label
            b.tvChapterState.setTextColor(ContextCompat.getColor(ctx, colorRes))
            b.tvChapterName.setTextColor(
                if (row.running) ContextCompat.getColor(ctx, R.color.primary) else themeColor(ctx)
            )
            b.tvChapterName.alpha = if (row.state == ChapterRow.State.DONE && !row.running) 0.65f else 1f
            b.root.setBackgroundColor(
                ContextCompat.getColor(
                    ctx,
                    if (row.running) R.color.chapter_running_bg else android.R.color.transparent
                )
            )
        }

        /** 正文色跟主题走，不写死黑/白（深色模式会糊） */
        private fun themeColor(ctx: android.content.Context): Int {
            val tv = TypedValue()
            val ok = ctx.theme.resolveAttribute(
                com.google.android.material.R.attr.colorOnSurface, tv, true
            )
            return if (ok) tv.data else ContextCompat.getColor(ctx, R.color.muted)
        }
    }
}
