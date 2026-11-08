package com.xxt.sign.core

import android.content.Context

/**
 * 签到动态：有签到时它自己冒出来一条（地点 + 剩余时间），
 * 能自动签的直接签掉、只留一行结果；签不了的才升级成「要你本人处理」。
 */
object SignEvents {

    data class Card(val title: String, val detail: String)

    private const val KEY_TITLE = "sign_card_title"
    private const val KEY_DETAIL = "sign_card_detail"
    private const val KEY_AT = "sign_card_at"

    private fun sp(c: Context) = c.getSharedPreferences("sign_events", 0)

    fun publish(context: Context, title: String, detail: String) {
        sp(context).edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /** 只显示 30 分钟内的，过期就不烦人 */
    fun pending(context: Context): Card? {
        val p = sp(context)
        val title = p.getString(KEY_TITLE, "").orEmpty()
        if (title.isBlank()) return null
        val at = p.getLong(KEY_AT, 0L)
        if (System.currentTimeMillis() - at > 30 * 60_000L) return null
        return Card(title, p.getString(KEY_DETAIL, "").orEmpty())
    }

    fun clear(context: Context) {
        sp(context).edit().clear().apply()
    }
}
