package com.xxt.sign.study

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 课程列表与章节列表的本地缓存。
 *
 * 按账号存储：accountId -> { courses: [...], chapters: {courseKey: [...]}, savedAt }
 * 进入刷课页时优先读这里，避免每次重新进入都重新请求课程列表；
 * 顶部「刷新课程」会重新拉取并覆盖缓存。
 */
object StudyCourseStore {

    data class Snapshot(
        val courses: List<StudyCourse>,
        val chapters: Map<String, List<StudyChapter>>,
        val savedAt: Long
    )

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("xxt_study_courses", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(accountId: String): Snapshot? {
        if (accountId.isBlank() || !::sp.isInitialized) return null
        val raw = sp.getString(key(accountId), null) ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    /**
     * 保存最新课程列表。
     * [chapters] 里没有的课程不写章节；已有章节由调用方从旧缓存/当前列表带过来。
     */
    @Synchronized
    fun saveCourses(
        accountId: String,
        courses: List<StudyCourse>,
        chapters: Map<String, List<StudyChapter>>,
        savedAt: Long = System.currentTimeMillis()
    ) {
        if (accountId.isBlank() || courses.isEmpty() || !::sp.isInitialized) return

        val root = JSONObject()
        root.put("t", savedAt)
        val arr = JSONArray()
        courses.forEach { course ->
            val obj = encodeCourse(course)
            val list = chapters[course.key].orEmpty()
            if (list.isNotEmpty()) obj.put("chapters", encodeChapters(list))
            arr.put(obj)
        }
        root.put("courses", arr)
        sp.edit().putString(key(accountId), root.toString()).apply()
    }

    /** 读取/检查章节后增量更新，保留原来的课程列表时间戳 */
    @Synchronized
    fun putChapters(accountId: String, courseKey: String, chapters: List<StudyChapter>) {
        if (accountId.isBlank() || courseKey.isBlank() || !::sp.isInitialized) return
        val raw = sp.getString(key(accountId), null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val arr = root.optJSONArray("courses") ?: return

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (courseKeyOf(obj) != courseKey) continue
            if (chapters.isEmpty()) {
                obj.remove("chapters")
            } else {
                obj.put("chapters", encodeChapters(chapters))
            }
            sp.edit().putString(key(accountId), root.toString()).apply()
            return
        }
    }

    @Synchronized
    fun clear(accountId: String) {
        if (accountId.isBlank() || !::sp.isInitialized) return
        sp.edit().remove(key(accountId)).apply()
    }

    private fun key(accountId: String) = "cache_$accountId"

    private fun encodeCourse(course: StudyCourse): JSONObject = JSONObject().apply {
        put("courseId", course.courseId)
        put("clazzId", course.clazzId)
        put("cpi", course.cpi)
        put("title", course.title)
        put("teacher", course.teacher)
    }

    private fun decodeCourse(obj: JSONObject): StudyCourse? {
        val courseId = obj.optString("courseId")
        val clazzId = obj.optString("clazzId")
        if (courseId.isBlank() || clazzId.isBlank()) return null
        return StudyCourse(
            courseId = courseId,
            clazzId = clazzId,
            cpi = obj.optString("cpi"),
            title = obj.optString("title"),
            teacher = obj.optString("teacher")
        )
    }

    private fun courseKeyOf(obj: JSONObject): String {
        val id = obj.optString("courseId")
        val clazz = obj.optString("clazzId")
        return if (id.isBlank() || clazz.isBlank()) "" else "$id:$clazz"
    }

    private fun encodeChapters(chapters: List<StudyChapter>): JSONArray = JSONArray().apply {
        chapters.forEach { ch ->
            put(JSONObject().apply {
                put("id", ch.id)
                put("title", ch.title)
                put("jobCount", ch.jobCount)
                put("finished", ch.finished)
                put("needUnlock", ch.needUnlock)
            })
        }
    }

    private fun decodeChapters(arr: JSONArray): List<StudyChapter> {
        val out = ArrayList<StudyChapter>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            out.add(
                StudyChapter(
                    id = id,
                    title = obj.optString("title"),
                    jobCount = obj.optInt("jobCount", 0),
                    finished = obj.optBoolean("finished", false),
                    needUnlock = obj.optBoolean("needUnlock", false)
                )
            )
        }
        return out
    }

    private fun decode(root: JSONObject): Snapshot? {
        val arr = root.optJSONArray("courses") ?: return null
        val courses = ArrayList<StudyCourse>(arr.length())
        val chapters = LinkedHashMap<String, List<StudyChapter>>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val course = decodeCourse(obj) ?: continue
            courses.add(course)
            val chArr = obj.optJSONArray("chapters") ?: continue
            val list = decodeChapters(chArr)
            if (list.isNotEmpty()) chapters[course.key] = list
        }
        if (courses.isEmpty()) return null
        return Snapshot(courses, chapters, root.optLong("t", 0L))
    }
}
