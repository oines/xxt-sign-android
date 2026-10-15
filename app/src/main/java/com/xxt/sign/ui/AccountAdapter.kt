package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.core.Account
import com.xxt.sign.core.LogStore
import com.xxt.sign.databinding.ItemAccountBinding

class AccountAdapter(
    private val onRelogin: (Account) -> Unit,
    private val onDelete: (Account) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    private var data: List<Account> = emptyList()

    fun submit(list: List<Account>) {
        data = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    inner class VH(private val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: Account) {
            b.tvName.text = "${a.display}   (${a.phone})"
            val sb = StringBuilder()
            sb.append(if (a.hasSession) "会话已保存" else "未登录")
            if (a.uid.isNotBlank()) sb.append(" · uid=${a.uid}")
            if (a.lastLoginAt > 0) sb.append(" · 上次登录 ${LogStore.fullTime(a.lastLoginAt)}")
            if (a.lastError.isNotBlank()) sb.append("\n⚠ ${a.lastError}")
            b.tvDetail.text = sb.toString()
            b.btnRelogin.setOnClickListener { onRelogin(a) }
            b.btnDelete.setOnClickListener { onDelete(a) }
        }
    }
}
