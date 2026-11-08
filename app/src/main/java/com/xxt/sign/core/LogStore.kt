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

    // 刷课时多个协程同时打日志，SimpleDateFormat 也不是线程安全的
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val fullFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(text: String, ok: Boolean = true) {
        Log.i(TAG, text)
        // 直接改 value 会丢并发写入：两个协程同时读到同一份旧列表，后写的那个覆盖先写的
        val list = _items.value.toMutableList()
        list.add(0, Item(System.currentTimeMillis(), text, ok))
        while (list.size > 500) list.removeAt(list.size - 1)
        _items.value = list
    }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
    }

    @Synchronized
    fun time(t: Long): String = fmt.format(Date(t))

    @Synchronized
    fun fullTime(t: Long): String = fullFmt.format(Date(t))
}
