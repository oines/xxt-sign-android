package com.xxt.sign.work

import com.xxt.sign.core.LogStore
import com.xxt.sign.net.CxSession
import com.xxt.sign.net.enc
import com.xxt.sign.net.get
import com.xxt.sign.net.postForm
import com.xxt.sign.study.RequestLimiter
import com.xxt.sign.study.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 提交结果。
 * [raw] 是服务端原文，失败时日志里必须原样打出来（只有一句人话没法排障）。
 */
data class SubmitResult(
    val ok: Boolean,
    val message: String,
    val raw: String = ""
)

/**
 * 章节测验的网络层。
 *
 * 全部走 [CxSession.pcClient]（桌面 UA），因为 mooc1 的 work 页面是 PC 网页。
 * 这里只负责「发请求 + 把会话失效认出来」，HTML 解析在 [WorkParser]。
 *
 * 协议来源（2026-11 用真实账号逐条验证过，见 docs/quiz-submit.md）：
 *  - 取题：GET /mooc-ans/api/work?...&api=1（**必须带 api=1**，否则只有外壳页）
 *  - 提交：POST 页面 form 的 action（`addStudentWorkNewWeb?...token=…&totalQuestionNum=…`），
 *    body 必须是 form 里**全部**有 name 的字段；少一个就回「无效的提交，参数异常！」
 *  - 回查：交卷后再拉一次 api/work，服务端 302 到 `selectWorkQuestionYiPiYue`，
 *    页面里带「第 N 次作答 / 本次成绩 X 分」
 */
class WorkApi(private val session: CxSession) {

    private val c get() = session.pcClient
    private val limiter = RequestLimiter(600L, 300L)

    /** 拉一份卷子。返回 HTML 片段（已交卷时会是 record 页） */
    suspend fun fetch(job: WorkJob): String = withContext(Dispatchers.IO) {
        limiter.acquire()
        val body = c.get(workUrl(job), mapOf("Referer" to referer(job))).body
        checkLogin(body)
        body
    }

    private fun workUrl(job: WorkJob): String =
        "https://mooc1.chaoxing.com/mooc-ans/api/work" +
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

    /**
     * 提交。
     *
     * 关键点（每条都是真账号验出来的）：
     *  1. body 从 [WorkPage.formFields] **原样全带**。页面里 `answerId`、`answertype<qid>`、
     *     `answer<qid>`、`answerEditor<qid><空号>`、`answerwqbid`、`pyFlag` 全在里面；
     *     只覆盖我们真知道答案的那几个。
     *  2. 没答的题**保留空值字段**，绝不删字段 —— 删了服务端按 `answerwqbid` 点数就报参数异常。
     *  3. 填空题没有 `answer<qid>`，答案写进 `answerEditor<qid><空号>`。
     *  4. 用页面 form 的 action（带 token/totalQuestionNum/workid），再补
     *     `ua=pc&formType=post&saveStatus=1&version=1`，跟浏览器一模一样。
     *  5. 暂存 = `pyFlag=1`；正式交卷 = `pyFlag` 空。
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
        val form = SubmitForm.build(page, answers, storeOnly)
        val url = SubmitForm.url(page)
        val r = c.postForm(
            url,
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
            LogStore.add("[测验] 提交返回异常（HTTP ${r.code}）：${r.body.take(300)}", false)
            return@withContext SubmitResult(false, "提交返回异常（HTTP ${r.code}）", r.body)
        }
        val ok = j.optBoolean("status")
        val msg = j.optString("msg").ifBlank { if (ok) "提交成功" else "提交失败" }
        if (!ok) {
            // 真机排障用：服务端原话一定要进日志，不要只剩一句「提交失败」
            LogStore.add("[测验] 提交被拒：$msg｜服务端原文：${r.body.take(300)}", false)
        }
        SubmitResult(ok, msg, r.body)
    }

    /**
     * 回查：交卷后再拉一次卷子。服务端会把已交卷的请求 302 到审阅页
     * （`selectWorkQuestionYiPiYue`），页面里带「第 N 次作答 / 本次成绩 X 分」。
     *
     * 注意 `/mooc-ans/work/record-list` 对章节测验是死的（实测回「此作业已被删除」），
     * 所以回查统一走审阅页。
     */
    suspend fun recordList(job: WorkJob): RecordSummary = withContext(Dispatchers.IO) {
        limiter.acquire()
        val body = c.get(workUrl(job), mapOf("Referer" to referer(job))).body
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

/**
 * 提交表单的拼装（纯函数，单测直接喂 [WorkPage]）。
 *
 * 真机上的「无效的提交，参数异常！」全都出在这张表里，所以从 WorkApi 里拆出来单独测。
 */
internal object SubmitForm {

    /**
     * 铁律：表单字段原样全带，只覆盖真知道答案的那几个；未答的题保留空值字段。
     * 删任何一个字段（尤其 answerId / answertype<qid> / 空的 answer<qid>）
     * 服务端就按 answerwqbid 点数报参数异常。
     */
    fun build(
        page: WorkPage,
        answers: Map<String, String>,
        storeOnly: Boolean
    ): LinkedHashMap<String, String> {
        val form = LinkedHashMap<String, String>()
        form.putAll(page.formFields)

        page.questions.forEach { q ->
            val v = answers[q.qid] ?: return@forEach
            if (q.isRichText) {
                // 填空/简答：新版页面是 answerEditor<qid><空号>；老版页面就是 answer<qid>（textarea）
                val slots = page.blankFields[q.qid].orEmpty()
                when {
                    slots.isEmpty() -> if (form.containsKey("answer${q.qid}")) {
                        form["answer${q.qid}"] = v
                    } else {
                        form["answerEditor${q.qid}1"] = v
                    }
                    slots.size == 1 -> form[slots[0]] = v
                    else -> {
                        val parts = v.split('\n')
                        if (parts.size == slots.size) {
                            slots.forEachIndexed { i, name -> form[name] = parts[i].trim() }
                        } else {
                            form[slots[0]] = v
                        }
                    }
                }
            } else {
                form["answer${q.qid}"] = v
            }
        }
        // 全部题目 id 逗号拼接，结尾留一个逗号（页面就是这么发的）
        val qids = page.questions.joinToString(",") { it.qid }
        if (qids.isNotBlank()) form["answerwqbid"] = "$qids,"
        // 暂存 = 1；正式提交 = 空（页面的 toadd() 就是把 pyFlag 清空的）
        form["pyFlag"] = if (storeOnly) "1" else ""
        return form
    }

    /**
     * 提交地址：优先用页面 form 的 action（带 token / totalQuestionNum / workid / jobid …），
     * 解析不到才退回老的 addStudentWorkNew。末尾补浏览器固定带的 4 个参数。
     */
    fun url(page: WorkPage): String {
        val raw = page.formAction.trim()
        val base = when {
            raw.isEmpty() -> "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew"
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("/") -> "https://mooc1.chaoxing.com$raw"
            else -> "https://mooc1.chaoxing.com/mooc-ans/work/$raw"
        }
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + "ua=pc&formType=post&saveStatus=1&version=1"
    }
}
