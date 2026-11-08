package com.xxt.sign.ui

import android.view.LayoutInflater
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
    val submitted: Boolean = false
) {
    val title: String get() = job.name.ifBlank { "章节测验" }
    val sub: String get() = "$courseTitle · ${job.chapterTitle}"
    val countText: String get() = if (questionCount > 0) "题量 $questionCount" else "题量未知"
}

class QuizAdapter(
    private val onToggle: (QuizItem, Boolean) -> Unit
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
        }

        fun bind(item: QuizItem) {
            current = item
            b.tvQuizTitle.text = item.title
            b.tvQuizSub.text = item.sub
            b.tvQuizState.text = "${item.countText} · ${item.state}"
            b.cbQuiz.isChecked = item.checked
            b.cbQuiz.isEnabled = !item.submitted
        }
    }
}
