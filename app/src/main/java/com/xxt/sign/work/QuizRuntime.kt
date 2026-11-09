package com.xxt.sign.work

import com.xxt.sign.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 首页用的运行状态：交代完就不管，只在首页显示「正在做什么」 */
object QuizRuntime {
    data class State(val running: Boolean = false, val text: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun start(text: String) {
        _state.value = State(true, text)
    }

    fun update(text: String) {
        if (_state.value.running) _state.value = _state.value.copy(text = text)
    }

    fun stop() {
        _state.value = State(false, "")
    }
}

/** 本周完成统计（首页那句「本周完成 N 个任务」） */
object QuizStatsStore {

    private fun sp() = App.ctx.getSharedPreferences("quiz_stats", 0)

    private fun weekKey(): String =
        SimpleDateFormat("yyyy-'W'ww", Locale.US).format(Date())

    private fun key(name: String) = "${weekKey()}_$name"

    @Synchronized
    fun addQuiz(answered: Int, total: Int) {
        val sp = sp()
        sp.edit()
            .putInt(key("papers"), sp.getInt(key("papers"), 0) + 1)
            .putInt(key("answered"), sp.getInt(key("answered"), 0) + answered)
            .putInt(key("questions"), sp.getInt(key("questions"), 0) + total)
            .apply()
    }

    fun papers(): Int = sp().getInt(key("papers"), 0)
    fun answered(): Int = sp().getInt(key("answered"), 0)
    fun questions(): Int = sp().getInt(key("questions"), 0)
}
