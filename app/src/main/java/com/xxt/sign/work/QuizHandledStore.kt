package com.xxt.sign.work

import com.xxt.sign.App
import org.json.JSONObject

/**
 * 已经处理过的测验，避免巡检反复重做。
 *
 *  - 正常处理过的（交了/暂存了）：6 小时内不再碰
 *  - 卡在硬阻塞（人脸/滑块）的：单独记一份，等人工处理
 *
 * 存在 SharedPreferences 里，服务重启也不会丢。
 */
object QuizHandledStore {

    private const val PREF = "quiz_handled"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private fun sp() = App.ctx.getSharedPreferences(PREF, 0)

    private fun keyOf(accountId: String) = "done_$accountId"
    private fun humanKeyOf(accountId: String) = "human_$accountId"

    fun mark(accountId: String, key: String, needsHuman: Boolean = false) {
        val all = readDone(accountId).toMutableMap()
        all[key] = System.currentTimeMillis()
        val obj = JSONObject()
        all.forEach { (k, v) -> obj.put(k, v) }
        sp().edit().putString(keyOf(accountId), obj.toString()).apply()
        if (needsHuman) markHuman(accountId, key)
    }

    fun isHandled(accountId: String, key: String): Boolean {
        val at = readDone(accountId)[key] ?: return false
        return System.currentTimeMillis() - at < TTL_MS
    }

    fun markHuman(accountId: String, key: String) {
        val set = humanKeys(accountId).toMutableSet()
        set.add(key)
        sp().edit().putStringSet(humanKeyOf(accountId), set).apply()
    }

    fun humanKeys(accountId: String): Set<String> =
        sp().getStringSet(humanKeyOf(accountId), emptySet())?.toSet().orEmpty()

    fun clear(accountId: String) {
        sp().edit().remove(keyOf(accountId)).remove(humanKeyOf(accountId)).apply()
    }

    private fun readDone(accountId: String): Map<String, Long> = runCatching {
        val raw = sp().getString(keyOf(accountId), "") ?: ""
        if (raw.isBlank()) return@runCatching emptyMap()
        val obj = JSONObject(raw)
        obj.keys().asSequence().associateWith { obj.optLong(it) }
    }.getOrDefault(emptyMap())
}
