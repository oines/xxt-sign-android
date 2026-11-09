package com.xxt.sign.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.core.Account
import com.xxt.sign.core.LogStore
import com.xxt.sign.databinding.ItemAccountBinding

class AccountAdapter(
    private val onRelogin: (Account) -> Unit,
    private val onDelete: (Account) -> Unit,
    private val onStudySelect: (Account) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    private var data: List<Account> = emptyList()
    private var studySelectedId: String = ""

    fun submit(list: List<Account>, selectedStudyId: String = studySelectedId) {
        data = list
        studySelectedId = selectedStudyId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

    inner class VH(private val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(a: Account) {
            b.tvName.text = a.display
            val sb = StringBuilder()
            if (a.display != a.phone) sb.append("手机号 ").append(a.phone).append(" · ")
            sb.append(if (a.hasSession) "登录状态：已登录" else "登录状态：未登录")
            if (a.lastLoginAt > 0) sb.append(" · 上次登录 ${LogStore.fullTime(a.lastLoginAt)}")
            if (a.lastError.isNotBlank()) sb.append("\n⚠ ${a.lastError}")
            b.tvDetail.text = sb.toString()
            b.btnRelogin.setOnClickListener { onRelogin(a) }
            b.btnDelete.setOnClickListener { onDelete(a) }

            b.rbStudy.setOnCheckedChangeListener(null)
            b.rbStudy.isChecked = a.id == studySelectedId
            b.rbStudy.setOnClickListener { onStudySelect(a) }
        }
    }
}
