package com.xxt.sign.work

import com.xxt.sign.App
import org.json.JSONArray
import org.json.JSONObject

/** 测验列表本地存档：重启后保留上次扫描结果与每卷状态 */
object QuizListStore {

    private fun sp() = App.ctx.getSharedPreferences("quiz_list", 0)

    data class Saved(
        val courseId: String, val clazzId: String, val cpi: String,
        val chapterId: String, val chapterTitle: String,
        val jobId: String, val workId: String, val enc: String, val ktoken: String,
        val name: String, val courseTitle: String,
        val questionCount: Int, val state: String, val checked: Boolean,
        val submitted: Boolean, val score: String, val time: String, val error: String
    )

    fun save(accountId: String, items: List<Saved>) {
        if (accountId.isBlank()) return
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(
                JSONObject()
                    .put("courseId", i.courseId).put("clazzId", i.clazzId).put("cpi", i.cpi)
                    .put("chapterId", i.chapterId).put("chapterTitle", i.chapterTitle)
                    .put("jobId", i.jobId).put("workId", i.workId).put("enc", i.enc)
                    .put("ktoken", i.ktoken).put("name", i.name).put("courseTitle", i.courseTitle)
                    .put("questionCount", i.questionCount).put("state", i.state)
                    .put("checked", i.checked).put("submitted", i.submitted)
                    .put("score", i.score).put("time", i.time).put("error", i.error)
            )
        }
        sp().edit().putString(accountId, arr.toString()).apply()
    }

    fun load(accountId: String): List<Saved> = runCatching {
        val raw = sp().getString(accountId, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            Saved(
                courseId = o.optString("courseId"), clazzId = o.optString("clazzId"),
                cpi = o.optString("cpi"), chapterId = o.optString("chapterId"),
                chapterTitle = o.optString("chapterTitle"), jobId = o.optString("jobId"),
                workId = o.optString("workId"), enc = o.optString("enc"),
                ktoken = o.optString("ktoken"), name = o.optString("name"),
                courseTitle = o.optString("courseTitle"),
                questionCount = o.optInt("questionCount"), state = o.optString("state"),
                checked = o.optBoolean("checked", true), submitted = o.optBoolean("submitted"),
                score = o.optString("score"), time = o.optString("time"), error = o.optString("error")
            )
        }
    }.getOrDefault(emptyList())

    fun clear(accountId: String) {
        sp().edit().remove(accountId).apply()
    }
}
