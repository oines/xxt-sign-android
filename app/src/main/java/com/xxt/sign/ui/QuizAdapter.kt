package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.databinding.ItemQuizBinding
import com.xxt.sign.work.WorkJob

/** 章节测验列表项 */
data class QuizItem(
    val job: WorkJob,
    val courseTitle: String,
    val questionCount: Int,
    val state: String,
    val checked: Boolean = true,
    val submitted: Boolean = false,
    /** 交卷后回查到的分数 */
    val score: String = "",
    /** 处理时间（HH:mm） */
    val time: String = "",
    /** 失败/需人工时的可读原因；非空时显示「重试」 */
    val error: String = "",
    /** 正在跑 */
    val running: Boolean = false
) {
    val title: String get() = job.name.ifBlank { "章节测验" }
    val sub: String get() = "$courseTitle · ${job.chapterTitle}"

    val stateLine: String
        get() = buildString {
            append(if (questionCount > 0) "题量 $questionCount" else "题量未知")
            append(" · ").append(if (running) "正在跑…" else state)
            if (score.isNotBlank()) append(" · ").append(score).append(" 分")
            if (time.isNotBlank()) append(" · ").append(time)
        }

    val canRetry: Boolean get() = !running && error.isNotBlank()
}

class QuizAdapter(
    private val onToggle: (QuizItem, Boolean) -> Unit,
    private val onRetry: (QuizItem) -> Unit
) : RecyclerView.Adapter<QuizAdapter.VH>() {

    private val list = mutableListOf<QuizItem>()

    fun submit(items: List<QuizItem>) {
        list.clear()
        list.addAll(items)
        notifyDataSetChanged()
    }

    fun update(index: Int, item: QuizItem) {
        if (index !in list.indices) return
        list[index] = item
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemQuizBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount(): Int = list.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(list[position])

    inner class VH(private val b: ItemQuizBinding) : RecyclerView.ViewHolder(b.root) {
        private var current: QuizItem? = null

        init {
            b.cbQuiz.setOnCheckedChangeListener { _, checked ->
                val item = current ?: return@setOnCheckedChangeListener
                if (item.checked == checked) return@setOnCheckedChangeListener
                onToggle(item, checked)
            }
            b.btnRetry.setOnClickListener {
                current?.let { onRetry(it) }
            }
        }

        fun bind(item: QuizItem) {
            current = item
            b.tvQuizTitle.text = item.title
            b.tvQuizSub.text = item.sub
            b.tvQuizState.text = item.stateLine
            b.cbQuiz.isChecked = item.checked
            b.cbQuiz.isEnabled = !item.submitted && !item.running

            if (item.error.isBlank()) {
                b.tvQuizError.visibility = View.GONE
            } else {
                b.tvQuizError.visibility = View.VISIBLE
                b.tvQuizError.text = "原因：${item.error}"
            }
            b.btnRetry.visibility = if (item.canRetry) View.VISIBLE else View.GONE
        }
    }
}
