package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.core.LogStore
import com.xxt.sign.databinding.ItemLogBinding

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private var data: List<LogStore.Item> = emptyList()

    fun submit(list: List<LogStore.Item>) {
        data = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    inner class VH(private val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(i: LogStore.Item) {
            b.tvTime.text = LogStore.fullTime(i.time)
            b.tvText.text = i.text
        }
    }
}
