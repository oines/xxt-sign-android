package com.xxt.sign.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val TAG = "XXTSign"

    data class Item(val time: Long, val text: String, val ok: Boolean)

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fullFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun add(text: String, ok: Boolean = true) {
        Log.i(TAG, text)
        val list = _items.value.toMutableList()
        list.add(0, Item(System.currentTimeMillis(), text, ok))
        while (list.size > 500) list.removeAt(list.size - 1)
        _items.value = list
    }

    fun clear() {
        _items.value = emptyList()
    }

    fun time(t: Long): String = fmt.format(Date(t))
    fun fullTime(t: Long): String = fullFmt.format(Date(t))
}
