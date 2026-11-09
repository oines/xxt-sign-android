package com.xxt.sign.study

import org.json.JSONArray
import org.json.JSONObject

/**
 * 体检结果的 JSON 编解码（纯函数，单测直接跑）。
 *
 * 单独拆出来的原因：真机上出现过「存的是 v0、读的也以为是 v0」——
 * 保存端和读取端必须共用同一份格式定义，不然漏一个字段就整屏「没查到」。
 */
internal object CourseAuditJson {

    /** 一行的字段名，读写共用 */
    private const val K_TITLE = "t"
    private const val K_DONE = "d"
    private const val K_TOTAL = "n"
    private const val K_STATE = "s"
    private const val K_VIDEO_DONE = "vd"
    private const val K_VIDEO_TASKS = "vt"

    fun encode(audits: List<CourseAudit>): String {
        val arr = JSONArray()
        audits.forEach { a ->
            val rows = JSONArray()
            a.chapterRows.forEach { r ->
                rows.put(
                    JSONObject()
                        .put(K_TITLE, r.title)
                        .put(K_DONE, r.done)
                        .put(K_TOTAL, r.total)
                        .put(K_STATE, r.state.name)
                        .put(K_VIDEO_DONE, r.videoDone)
                        .put(K_VIDEO_TASKS, r.videoTasks)
                )
            }
            arr.put(
                JSONObject()
                    .put("courseId", a.courseId)
                    .put("courseTitle", a.courseTitle)
                    .put("taskPoints", a.taskPoints)
                    .put("doneTaskPoints", a.doneTaskPoints)
                    .put("videoPending", a.videoPending)
                    .put("quizPending", a.quizPending)
                    .put("documentPending", a.documentPending)
                    .put("humanPending", a.humanPending)
                    .put("chapters", a.chapters)
                    .put("checkedChapters", a.checkedChapters)
                    .put("auditedAt", a.auditedAt)
                    // 关键：写入当前口径版本；读端据此判断行状态能不能信
                    .put("rowsVersion", a.rowsVersion)
                    .put("rows", rows)
            )
        }
        return arr.toString()
    }

    fun decode(raw: String): List<CourseAudit> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CourseAudit(
                courseId = o.optString("courseId"),
                courseTitle = o.optString("courseTitle"),
                taskPoints = o.optInt("taskPoints"),
                doneTaskPoints = o.optInt("doneTaskPoints"),
                videoPending = o.optInt("videoPending"),
                documentPending = o.optInt("documentPending"),
                quizPending = o.optInt("quizPending"),
                humanPending = o.optInt("humanPending"),
                chapterRows = o.optJSONArray("rows")?.let { rows ->
                    (0 until rows.length()).mapNotNull { j ->
                        val r = rows.optJSONObject(j) ?: return@mapNotNull null
                        ChapterRow(
                            title = r.optString(K_TITLE),
                            total = r.optInt(K_TOTAL),
                            done = r.optInt(K_DONE),
                            state = runCatching { ChapterRow.State.valueOf(r.optString(K_STATE)) }
                                .getOrDefault(ChapterRow.State.UNCHECKED),
                            videoDone = r.optInt(K_VIDEO_DONE),
                            videoTasks = r.optInt(K_VIDEO_TASKS)
                        )
                    }
                } ?: emptyList(),
                chapters = o.optInt("chapters"),
                checkedChapters = o.optInt("checkedChapters"),
                rowsVersion = o.optInt("rowsVersion", 0),
                auditedAt = o.optLong("auditedAt")
            )
        }
    }
}
