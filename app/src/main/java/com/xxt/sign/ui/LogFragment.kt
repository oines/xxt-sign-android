package com.xxt.sign.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.xxt.sign.core.LogStore
import com.xxt.sign.databinding.FragmentLogBinding
import kotlinx.coroutines.launch

class LogFragment : Fragment() {

    private var _b: FragmentLogBinding? = null
    private val b get() = _b!!
    private val adapter = LogAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentLogBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter
        b.btnClear.setOnClickListener { LogStore.clear() }
        viewLifecycleOwner.lifecycleScope.launch {
            LogStore.items.collect { adapter.submit(it) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
