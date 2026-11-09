package com.xxt.sign.study

import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.net.enc
import com.xxt.sign.net.get
import com.xxt.sign.net.postForm
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * 弹题的 HTTP 实现。
 *
 * ⚠️ 接口地址**还没真机抓包确认**（见 docs/弹题.md）。所以这里不写死路径：
 * 设置里有 `popQuizFetchUrl` / `popQuizSubmitUrl` 两个模板，支持占位符
 * `{objectId} {jobid} {courseId} {clazzId} {cpi} {time}`，抓到之后填进去就能用。
 *
 * 没填 = [configured] 为 false，播放循环不会发任何弹题请求，
 * 只会靠「进度不前进」判断卡住并标成需要人工。
 */
class ConfiguredPopQuizBridge(
    private val client: OkHttpClient,
    private val course: StudyCourse,
    private val job: VideoJob,
    private val log: (String, Boolean) -> Unit = { m, ok -> LogStore.add(m, ok) }
) : PopQuizBridge {

    private val fetchTemplate = Settings.popQuizFetchUrl.trim()
    private val submitTemplate = Settings.popQuizSubmitUrl.trim()

    override val configured: Boolean
        get() = fetchTemplate.isNotBlank()

    override suspend fun poll(job: VideoJob, atSecond: Int): List<PopQuizQuestion> {
        if (!configured) return emptyList()
        val url = fill(fetchTemplate, atSecond)
        if (url.isBlank()) return emptyList()
        return runCatching {
            val r = client.get(url, mapOf("Referer" to VIDEO_REFERER))
            if (!r.ok) return@runCatching emptyList<PopQuizQuestion>()
            parseQuestions(r.body)
        }.getOrElse {
            log("[弹题] 取题失败：${it.message}", false)
            emptyList()
        }
    }

    override suspend fun submit(job: VideoJob, question: PopQuizQuestion, answer: String): Boolean {
        if (submitTemplate.isBlank() || answer.isBlank()) return false
        val url = fill(submitTemplate, 0)
        if (url.isBlank()) return false
        return runCatching {
            val r = client.postForm(
                url,
                mapOf(
                    "questionId" to question.id,
                    "answer" to answer,
                    "objectId" to job.objectId,
                    "jobid" to job.jobId
                ),
                mapOf("Referer" to VIDEO_REFERER, "Content-Type" to "application/json")
            )
            if (!r.ok) {
                log("[弹题] 提交失败 HTTP ${r.code}", false)
                return@runCatching false
            }
            val ok = runCatching { JSONObject(r.body).optBoolean("success", true) }.getOrDefault(true)
            if (!ok) log("[弹题] 服务端没接受答案：${r.body.take(120)}", false) else Unit
            ok
        }.getOrElse {
            log("[弹题] 提交异常：${it.message}", false)
            false
        }
    }

    private fun fill(template: String, atSecond: Int): String = template
        .replace("{objectId}", job.objectId)
        .replace("{jobid}", job.jobId)
        .replace("{courseId}", course.courseId)
        .replace("{clazzId}", course.clazzId)
        .replace("{cpi}", enc(course.cpi.ifBlank { "0" }))
        .replace("{time}", atSecond.toString())

    companion object {
        private const val VIDEO_REFERER =
            "https://mooc1.chaoxing.com/ananas/modules/video/index.html?v=2025-0725-1842"

        /**
         * 尽力解析：真机抓包前不知道字段名，所以对常见的几种形状都试一遍。
         * 拿到真实返回后，这里应该只剩下一种分支。
         */
        fun parseQuestions(body: String): List<PopQuizQuestion> {
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: runCatching { JSONArray(body) }.getOrNull()
                ?: return emptyList()

            val array: JSONArray = when (root) {
                is JSONObject -> {
                    val keys = listOf("questions", "question", "data", "list", "result")
                    keys.firstNotNullOfOrNull { k ->
                        (root.opt(k) as? JSONArray)
                            ?: (root.optJSONObject(k)?.optJSONArray("questions"))
                    } ?: return emptyList()
                }
                is JSONArray -> root
                else -> return emptyList()
            }

            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = listOf("id", "questionId", "qid")
                    .firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() } }
                    ?: return@mapNotNull null
                val stem = listOf("stem", "title", "question", "content")
                    .firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() } }
                    .orEmpty()
                val optionsArr = o.optJSONArray("options") ?: o.optJSONArray("optionList")
                val options = buildList {
                    if (optionsArr != null) {
                        for (j in 0 until optionsArr.length()) {
                            val opt = optionsArr.optJSONObject(j)
                            if (opt != null) {
                                add(
                                    opt.optString("content")
                                        .ifBlank { opt.optString("text") }
                                        .ifBlank { opt.optString("option") }
                                )
                            } else {
                                add(optionsArr.optString(j))
                            }
                        }
                    }
                }.filter { it.isNotBlank() }
                val type = o.optInt("type", o.optString("type").toIntOrNull() ?: 0)
                PopQuizQuestion(id, type, stem, options)
            }
        }
    }
}
