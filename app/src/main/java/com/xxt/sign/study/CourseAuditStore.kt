package com.xxt.sign.study

import android.content.SharedPreferences
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 体检结果缓存：首页不联网也能显示「还差几个任务点」，
 * 巡检跑完后写回，界面跟着变。
 */
object CourseAuditStore {

    private lateinit var sp: SharedPreferences

    fun init(context: android.content.Context) {
        sp = context.getSharedPreferences("xxt_course_audit", 0)
    }

    fun save(accountId: String, audits: List<CourseAudit>) {
        if (accountId.isBlank() || !::sp.isInitialized) return
        val arr = JSONArray()
        audits.forEach { a ->
            arr.put(
                JSONObject()
                    .put("courseId", a.courseId).put("courseTitle", a.courseTitle)
                    .put("taskPoints", a.taskPoints).put("doneTaskPoints", a.doneTaskPoints)
                    .put("videoPending", a.videoPending).put("quizPending", a.quizPending)
                    .put("humanPending", a.humanPending).put("chapters", a.chapters)
                    .put("checkedChapters", a.checkedChapters).put("auditedAt", a.auditedAt)
            )
        }
        sp.edit().putString(accountId, arr.toString()).apply()
    }

    fun load(accountId: String): List<CourseAudit> {
        if (accountId.isBlank() || !::sp.isInitialized) return emptyList()
        val raw = sp.getString(accountId, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CourseAudit(
                    courseId = o.optString("courseId"),
                    courseTitle = o.optString("courseTitle"),
                    taskPoints = o.optInt("taskPoints"),
                    doneTaskPoints = o.optInt("doneTaskPoints"),
                    videoPending = o.optInt("videoPending"),
                    quizPending = o.optInt("quizPending"),
                    humanPending = o.optInt("humanPending"),
                    chapters = o.optInt("chapters"),
                    checkedChapters = o.optInt("checkedChapters"),
                    auditedAt = o.optLong("auditedAt")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun loadCurrent(): List<CourseAudit> {
        val account = AccountStore.get(Settings.studyAccountId) ?: return emptyList()
        return load(account.id)
    }

    /** 全绿了几门 / 一共几门 / 还差多少任务点 */
    fun summary(audits: List<CourseAudit>): Triple<Int, Int, Int> {
        if (audits.isEmpty()) return Triple(0, 0, 0)
        val green = audits.count { it.allGreen }
        return Triple(green, audits.size, audits.sumOf { it.remaining })
    }
}
