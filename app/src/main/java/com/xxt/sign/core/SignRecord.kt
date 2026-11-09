package com.xxt.sign.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 签到记录（只读）。用户要的是「至少留个页面能回头看」，
 * 所以这里只存事实：什么课/什么地点、什么时候、成没成、没成的原因。
 */
data class SignRecord(
    /** 例：大学体育 · 3 号楼 302 */
    val title: String,
    val at: Long,
    val ok: Boolean,
    /** 没签上的原因，签上了就是空 */
    val reason: String
) {
    val okText: String get() = if (ok) "已签到" else "没签上"
}

object SignRecordStore {

    private const val PREF = "sign_records"
    private const val KEY = "records"
    private const val MAX = 100

    private fun sp(c: Context) = c.getSharedPreferences(PREF, 0)

    fun add(context: Context, record: SignRecord) {
        val all = load(context).toMutableList()
        all.add(0, record)
        val arr = JSONArray()
        all.take(MAX).forEach { r ->
            arr.put(
                JSONObject()
                    .put("title", r.title)
                    .put("at", r.at)
                    .put("ok", r.ok)
                    .put("reason", r.reason)
            )
        }
        sp(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** 时间倒序 */
    fun load(context: Context): List<SignRecord> {
        val raw = sp(context).getString(KEY, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SignRecord(
                    title = o.optString("title"),
                    at = o.optLong("at"),
                    ok = o.optBoolean("ok"),
                    reason = o.optString("reason")
                )
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }
}
