package com.xxt.sign.study

import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 学习页 HTML → 数据模型。
 *
 * 这里只保留「看视频」需要的最小解析：课程列表、章节、视频任务卡片。
 * 题目 / 测验 / 字体解密全部不实现。
 */
object StudyParser {

    fun parseCourses(html: String): List<StudyCourse> {
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, StudyCourse>()
        for (course in doc.select("div.course")) {
            if (course.selectFirst("a.not-open-tip") != null ||
                course.selectFirst("div.not-open-tip") != null
            ) continue

            val clazzId = course.selectFirst("input.clazzId")?.attr("value").orEmpty()
            val courseId = course.selectFirst("input.courseId")?.attr("value").orEmpty()
            if (clazzId.isBlank() || courseId.isBlank()) continue

            val href = course.selectFirst("a")?.attr("href").orEmpty()
            val cpi = Regex("cpi=([^&]+)").find(href)?.groupValues?.getOrNull(1).orEmpty()
            val titleEl = course.selectFirst("span.course-name")
            val title = titleEl?.let { it.attr("title").ifBlank { it.text() } }.orEmpty()
            val teacher = course.selectFirst("p.color3")?.attr("title").orEmpty()

            val c = StudyCourse(
                courseId = courseId,
                clazzId = clazzId,
                cpi = cpi,
                title = title.ifBlank { courseId },
                teacher = teacher
            )
            out[c.key] = c
        }
        return out.values.toList()
    }

    /** 课程文件夹 id；有文件夹的课程需要按 folderId 再拉一次列表 */
    fun parseFolders(html: String): List<String> {
        val doc = Jsoup.parse(html)
        return doc.select("ul.file-list > li")
            .mapNotNull { it.attr("fileid").takeIf { id -> id.isNotBlank() } }
            .distinct()
    }

    fun parseChapters(html: String): List<StudyChapter> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<StudyChapter>()
        for (unit in doc.select("div.chapter_unit")) {
            for (li in unit.select("li")) {
                val div = li.children().firstOrNull {
                    it.tagName() == "div" && it.id().isNotBlank()
                } ?: continue
                val pointId = Regex("^cur(\\d{1,20})$").find(div.id())?.groupValues?.getOrNull(1)
                    ?: continue
                val title = li.selectFirst("a.clicktitle")?.text()
                    ?.replace("\n", "")?.trim().orEmpty()
                val jobCount = li.selectFirst("input.knowledgeJobCount")
                    ?.attr("value")?.toIntOrNull() ?: 1
                val tip = li.selectFirst("span.bntHoverTips")?.text().orEmpty()
                out.add(
                    StudyChapter(
                        id = pointId,
                        title = title.ifBlank { "章节 $pointId" },
                        jobCount = jobCount,
                        finished = tip.contains("已完成"),
                        needUnlock = tip.contains("解锁")
                    )
                )
            }
        }
        return out.distinctBy { it.id }
    }

    /**
     * 解析 knowledge/cards 页面里的 mArg JSON，只取 type=video 且未通过的任务点。
     * 页面出现「章节未开放」时返回 notOpen=true。
     */
    fun parseCards(html: String): ChapterJobs {
        if (html.contains("章节未开放")) return ChapterJobs(true, emptyList())

        // 官方页面把 mArg 写成一整行 JS 对象；去掉空格后再取，和参考实现保持一致
        val compact = html.replace(" ", "")
        val raw = Regex("mArg=\\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL)
            .find(compact)?.groupValues?.getOrNull(1) ?: return ChapterJobs(false, emptyList())

        val json = runCatching { JSONObject("{$raw}") }.getOrNull()
            ?: return ChapterJobs(false, emptyList())
        val attachments = json.optJSONArray("attachments") ?: return ChapterJobs(false, emptyList())

        val jobs = LinkedHashMap<String, VideoJob>()
        for (i in 0 until attachments.length()) {
            val card = attachments.optJSONObject(i) ?: continue
            if (isPassed(card)) continue
            if (!card.optString("type").equals("video", ignoreCase = true)) continue

            val property = card.optJSONObject("property") ?: JSONObject()
            val jobId = card.optString("jobid")
            val objectId = card.optString("objectId")
            if (jobId.isBlank() || objectId.isBlank()) continue

            // 参考实现只保留 & 前面的部分，后面的参数会干扰进度上报
            val otherInfo = card.optString("otherInfo").substringBefore("&")

            jobs[jobId] = VideoJob(
                jobId = jobId,
                name = property.optString("name").ifBlank { "视频任务" },
                objectId = objectId,
                playTimeMs = card.optLong("playTime", 0L),
                rt = property.optString("rt").takeUnless { it == "null" }.orEmpty(),
                otherInfo = otherInfo,
                attDuration = card.optString("attDuration").takeUnless { it == "null" }.orEmpty(),
                attDurationEnc = card.optString("attDurationEnc").takeUnless { it == "null" }.orEmpty(),
                faceCaptureEnc = card.optString("videoFaceCaptureEnc").takeUnless { it == "null" }.orEmpty()
            )
        }
        return ChapterJobs(false, jobs.values.toList())
    }

    private fun isPassed(card: JSONObject): Boolean {
        val value = card.opt("isPassed") ?: return false
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    /** 检查任务点：返回这一页里的所有附件任务及其完成状态 */
    fun parseCardTasks(html: String): List<CardTask> {
        if (html.contains("章节未开放")) return emptyList()
        val compact = html.replace(" ", "")
        val raw = Regex("mArg=\\{(.*?)\\};", RegexOption.DOT_MATCHES_ALL)
            .find(compact)?.groupValues?.getOrNull(1) ?: return emptyList()
        val json = runCatching { JSONObject("{$raw}") }.getOrNull() ?: return emptyList()
        val attachments = json.optJSONArray("attachments") ?: return emptyList()

        val out = mutableListOf<CardTask>()
        for (i in 0 until attachments.length()) {
            val card = attachments.optJSONObject(i) ?: continue
            val type = card.optString("type").ifBlank { "unknown" }
            val jobId = card.optString("jobid")
            val mid = card.optString("mid")
            val objectId = card.optString("objectId")
            val key = when {
                jobId.isNotBlank() -> jobId
                mid.isNotBlank() -> "$type:$mid"
                objectId.isNotBlank() -> "$type:$objectId"
                else -> "$type:$i"
            }
            out.add(
                CardTask(
                    key = key,
                    isVideo = type.equals("video", ignoreCase = true),
                    passed = isPassed(card)
                )
            )
        }
        return out
    }
}
