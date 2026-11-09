package com.xxt.sign.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.databinding.ItemStatusBinding

data class StatusItem(val title: String, val detail: String, val ok: Boolean)

class StatusAdapter : RecyclerView.Adapter<StatusAdapter.VH>() {

    private val data = mutableListOf<StatusItem>()

    fun clear() {
        data.clear()
        notifyDataSetChanged()
    }

    fun submit(list: List<StatusItem>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemStatusBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    inner class VH(private val b: ItemStatusBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(i: StatusItem) {
            b.tvTitle.text = i.title
            b.tvBody.text = i.detail
            b.tvBody.setTextColor(if (i.ok) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }
    }
}
