package com.xxt.sign.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xxt.sign.R
import com.xxt.sign.core.SignRecord
import com.xxt.sign.core.SignRecordStore
import com.xxt.sign.databinding.FragmentSignRecordsBinding
import com.xxt.sign.databinding.ItemSignRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 签到记录：只读，没有开关也没有手动签到按钮 */
class SignRecordFragment : Fragment() {

    private var _b: FragmentSignRecordsBinding? = null
    private val b get() = _b!!
    private val adapter = RecordAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentSignRecordsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.rvRecords.layoutManager = LinearLayoutManager(requireContext())
        b.rvRecords.adapter = adapter
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun render() {
        if (_b == null) return
        val records = SignRecordStore.load(requireContext())
        adapter.submit(records)
        b.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        b.rvRecords.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
    }

    private class RecordAdapter : RecyclerView.Adapter<RecordAdapter.VH>() {
        private val items = mutableListOf<SignRecord>()
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun submit(list: List<SignRecord>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSignRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(private val b: ItemSignRecordBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(r: SignRecord) {
                b.tvRecordTitle.text = r.title.ifBlank { "签到" }
                val suffix = if (r.reason.isBlank()) r.okText else "${r.okText}：${r.reason}"
                b.tvRecordLine.text = "${fmt.format(Date(r.at))} · $suffix"
                b.tvRecordLine.setTextColor(
                    b.root.context.getColor(if (r.ok) R.color.ok else R.color.err)
                )
            }
        }
    }
}
