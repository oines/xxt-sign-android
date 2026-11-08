package com.xxt.sign.work

import com.xxt.sign.net.CxSession
import com.xxt.sign.net.enc
import com.xxt.sign.net.get
import com.xxt.sign.net.postForm
import com.xxt.sign.study.RequestLimiter
import com.xxt.sign.study.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 提交结果 */
data class SubmitResult(val ok: Boolean, val message: String)

/**
 * 章节测验的网络层。
 *
 * 全部走 [CxSession.pcClient]（桌面 UA），因为 mooc1 的 work 页面是 PC 网页。
 * 这里只负责「发请求 + 把会话失效认出来」，HTML 解析在 [WorkParser]。
 *
 * 协议来源（已在真实页面验证）：
 *  - 取题：GET /mooc-ans/api/work?...&api=1（**必须带 api=1**，否则只有外壳页）
 *  - 提交：POST /mooc-ans/work/addStudentWorkNew（form-urlencoded）
 *  - 回查：GET /mooc-ans/work/record-list
 */
class WorkApi(private val session: CxSession) {

    private val c get() = session.pcClient
    private val limiter = RequestLimiter(600L, 300L)

    /** 拉一份卷子。返回 HTML 片段（已交卷时会是 record 页） */
    suspend fun fetch(job: WorkJob): String = withContext(Dispatchers.IO) {
        limiter.acquire()
        val url = "https://mooc1.chaoxing.com/mooc-ans/api/work" +
            "?api=1" +
            "&workId=${enc(job.workId)}" +
            "&jobid=${enc(job.jobId)}" +
            "&originJobId=${enc(job.jobId)}" +
            "&needRedirect=true&skipHeader=true" +
            "&knowledgeid=${enc(job.chapterId)}" +
            "&ktoken=${enc(job.ktoken)}" +
            "&cpi=${enc(job.cpi.ifBlank { "0" })}" +
            "&ut=s" +
            "&clazzId=${enc(job.clazzId)}" +
            "&type=" +
            "&enc=${enc(job.enc)}" +
            "&mooc2=1" +
            "&courseid=${enc(job.courseId)}"
        val body = c.get(url, mapOf("Referer" to referer(job))).body
        checkLogin(body)
        body
    }

    /**
     * 提交。
     *
     * @param answers qid -> 答案值（单选 "A"、多选 "ABC"（已排序）、判断 "true"/"false"、填空原文）
     * @param storeOnly true = 只暂存（pyFlag=1），false = 正式交卷
     */
    suspend fun submit(
        job: WorkJob,
        page: WorkPage,
        answers: Map<String, String>,
        storeOnly: Boolean
    ): SubmitResult = withContext(Dispatchers.IO) {
        limiter.acquire()
        val form = LinkedHashMap<String, String>()
        // 页面 form 里 name 不以 answer 开头的字段原样带回（enc / cpi / ktoken / workAnswerId …）
        form.putAll(page.formFields)
        if (form["workAnswerId"].isNullOrBlank()) form.remove("workAnswerId")

        page.questions.forEach { q ->
            val v = answers[q.qid] ?: return@forEach
            form["answer${q.qid}"] = v
            form["answertype${q.qid}"] = q.type.toString()
            // 富文本填空在部分页面用 answerEditor<qid>，两种都探测
            if (q.isBlank || q.type == WorkQuestion.TYPE_ESSAY) {
                form["answerEditor${q.qid}"] = v
            }
        }
        // 全部题目 id 逗号拼接，结尾留一个逗号（页面就是这么发的）
        form["answerwqbid"] = page.questions.joinToString(",") { it.qid } + ","
        if (storeOnly) form["pyFlag"] = "1"

        val r = c.postForm(
            "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew",
            form,
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to "https://mooc1.chaoxing.com",
                "Referer" to referer(job)
            )
        )
        checkLogin(r.body)
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        if (j == null) {
            return@withContext SubmitResult(false, "提交返回异常（HTTP ${r.code}）：${r.body.take(80)}")
        }
        SubmitResult(
            ok = j.optBoolean("status"),
            message = j.optString("msg").ifBlank { if (j.optBoolean("status")) "提交成功" else "提交失败" }
        )
    }

    /** 回查：交了几次、最高分 */
    suspend fun recordList(job: WorkJob): RecordSummary = withContext(Dispatchers.IO) {
        limiter.acquire()
        val url = "https://mooc1.chaoxing.com/mooc-ans/work/record-list" +
            "?clazzId=${enc(job.clazzId)}" +
            "&courseId=${enc(job.courseId)}" +
            "&workId=${enc(job.workId)}" +
            "&jobid=${enc(job.jobId)}" +
            "&knowledgeid=${enc(job.chapterId)}" +
            "&cpi=${enc(job.cpi.ifBlank { "0" })}" +
            "&enc=${enc(job.enc)}" +
            "&ktoken=${enc(job.ktoken)}" +
            "&ut=s&mooc2=1"
        val body = c.get(url, mapOf("Referer" to referer(job))).body
        checkLogin(body)
        RecordParser.parseList(body)
    }

    private fun referer(job: WorkJob): String =
        "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards" +
            "?clazzid=${job.clazzId}&courseid=${job.courseId}" +
            "&knowledgeid=${job.chapterId}&ut=s&cpi=${job.cpi.ifBlank { "0" }}&mooc2=1"

    private fun checkLogin(body: String) {
        if (body.contains("passport2.chaoxing.com/mlogin") ||
            body.contains("passport2.chaoxing.com/login") ||
            body.contains("请输入手机号")
        ) {
            throw SessionExpiredException()
        }
    }
}
