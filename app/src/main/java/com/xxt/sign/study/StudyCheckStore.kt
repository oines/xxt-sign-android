package com.xxt.sign.study

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 任务点检查结果的本地缓存。
 * 按账号存储：accountId -> courseKey -> chapterId -> ChapterCheck。
 */
object StudyCheckStore {

    private lateinit var sp: SharedPreferences

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        sp = context.getSharedPreferences("xxt_checks", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(accountId: String): Map<String, Map<String, ChapterCheck>> {
        val root = readRoot(accountId)
        val out = LinkedHashMap<String, Map<String, ChapterCheck>>()
        root.keys().forEach { courseKey ->
            val course = root.optJSONObject(courseKey) ?: return@forEach
            val chapters = LinkedHashMap<String, ChapterCheck>()
            course.keys().forEach { chapterId ->
                val obj = course.optJSONObject(chapterId) ?: return@forEach
                chapters[chapterId] = decode(obj)
            }
            out[courseKey] = chapters
        }
        return out
    }

    @Synchronized
    fun putCourse(accountId: String, courseKey: String, checks: Map<String, ChapterCheck>) {
        val root = readRoot(accountId)
        val course = JSONObject()
        checks.forEach { (chapterId, check) ->
            course.put(chapterId, encode(check))
        }
        root.put(courseKey, course)
        writeRoot(accountId, root)
        bump()
    }

    @Synchronized
    fun clearCourse(accountId: String, courseKey: String) {
        val root = readRoot(accountId)
        root.remove(courseKey)
        writeRoot(accountId, root)
        bump()
    }

    /**
     * 视频任务完成时本地增量更新。
     * 只有已经检查过该章节（缓存存在）时才更新；否则等下次检查。
     */
    @Synchronized
    fun markVideoDone(accountId: String, courseKey: String, chapterId: String, jobId: String) {
        if (accountId.isBlank() || jobId.isBlank()) return
        val root = readRoot(accountId)
        val course = root.optJSONObject(courseKey) ?: return
        val obj = course.optJSONObject(chapterId) ?: return
        val check = decode(obj)
        if (jobId in check.doneVideoJobs) return

        val newJobs = check.doneVideoJobs + jobId
        val videoIncrement = if (check.videoDone < check.videoTasks) 1 else 0
        val newVideoDone = check.videoDone + videoIncrement
        val newAllDone = if (videoIncrement > 0 && check.allDone < check.allTasks) {
            check.allDone + 1
        } else {
            check.allDone
        }
        val updated = check.copy(
            allDone = newAllDone,
            videoDone = newVideoDone,
            doneVideoJobs = newJobs
        )
        course.put(chapterId, encode(updated))
        root.put(courseKey, course)
        writeRoot(accountId, root)
        bump()
    }

    /**
     * 章节测验交卷成功时本地增量更新。
     * 只有已经检查过该章节（缓存存在）时才更新；否则等下次检查。
     */
    @Synchronized
    fun markQuizDone(accountId: String, courseKey: String, chapterId: String) {
        if (accountId.isBlank()) return
        val root = readRoot(accountId)
        val course = root.optJSONObject(courseKey) ?: return
        val obj = course.optJSONObject(chapterId) ?: return
        val check = decode(obj)
        if (check.quizDone >= check.quizTasks) return

        val newQuizDone = check.quizDone + 1
        val newAllDone = if (check.allDone < check.allTasks) check.allDone + 1 else check.allDone
        val updated = check.copy(
            allDone = newAllDone,
            quizDone = newQuizDone
        )
        course.put(chapterId, encode(updated))
        root.put(courseKey, course)
        writeRoot(accountId, root)
        bump()
    }

    private fun readRoot(accountId: String): JSONObject {
        if (!::sp.isInitialized) return JSONObject()
        val raw = sp.getString(key(accountId), "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun writeRoot(accountId: String, root: JSONObject) {
        if (!::sp.isInitialized) return
        sp.edit().putString(key(accountId), root.toString()).apply()
    }

    private fun key(accountId: String) = "checks_$accountId"

    private fun bump() {
        _version.value = _version.value + 1
    }

    private fun encode(c: ChapterCheck): JSONObject = JSONObject().apply {
        put("a", c.allTasks)
        put("ad", c.allDone)
        put("v", c.videoTasks)
        put("vd", c.videoDone)
        put("n", c.notOpen)
        put("qt", c.quizTasks)
        put("qd", c.quizDone)
        put("sf", c.scanFailed)
        put("jobs", JSONArray(c.doneVideoJobs.toList()))
    }

    private fun decode(o: JSONObject): ChapterCheck {
        val jobs = mutableSetOf<String>()
        o.optJSONArray("jobs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) jobs.add(s)
            }
        }
        return ChapterCheck(
            allTasks = o.optInt("a", 0),
            allDone = o.optInt("ad", 0),
            videoTasks = o.optInt("v", 0),
            videoDone = o.optInt("vd", 0),
            notOpen = o.optBoolean("n", false),
            doneVideoJobs = jobs,
            quizTasks = o.optInt("qt", 0),
            quizDone = o.optInt("qd", 0),
            scanFailed = o.optBoolean("sf", false)
        )
    }
}
