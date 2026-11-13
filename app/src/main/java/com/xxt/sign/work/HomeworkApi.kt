package com.xxt.sign.work

import com.xxt.sign.core.LogStore
import com.xxt.sign.net.CxSession
import com.xxt.sign.net.get
import com.xxt.sign.net.postForm
import com.xxt.sign.study.RequestLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** task-work 状态页的解析结果：拿到作答入口，或一句失败原因 */
data class HomeworkResolve(
    val doHomeWorkUrl: String?,
    val message: String = "",
    /** 已完成作业的分数（可能为空） */
    val score: String = "",
    /** 是否还能重做 */
    val redoable: Boolean = false,
    /** 已交卷作业的 workAnswerId（重做接口要带） */
    val answerId: String = ""
)

/** 暂存 / 交卷的返回 */
data class HomeworkSubmit(
    val ok: Boolean,
    val message: String,
    val answerId: String = ""
)

/** 题型扫描结果：列表用来过滤、详情用来决定能不能交卷 */
data class ChoiceScan(
    val hasChoice: Boolean,
    val allChoice: Boolean,
    val choiceCount: Int,
    val total: Int,
    val pagesRead: Int = 0
)

/**
 * 「作业」的网络层。
 *
 * 全部走 [CxSession.pcClient]（桌面 UA），和刷课 / 章节测验保持一致：
 * cookie 用的是同一个 jar，只是 UA/头换桌面版 —— 用户明确要求这样。
 *
 * 协议（2026-11 用真实账号逐条验证）：
 *  - 列表：GET `https://mooc1-api.chaoxing.com/mooc-ans/work/task-list?courseId=&classId=&cpi=&pageNum=N`
 *  - 状态：GET `https://mooc1-api.chaoxing.com/mooc-ans/work/phone/task-work?taskrefId=&courseId=&classId=&cpi=&msgId=0&ut=s&fromGroup=1`
 *  - 作答：GET `https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doHomeWork?...&index=N`
 *  - 暂存 / 交卷：POST `https://mooc1-api.chaoxing.com/mooc-ans/work/phone/doNormalHomeWorkSubmit?tempSave=<true|false>`
 *
 * 除 [submit] 外都是只读 GET。[submit] 只在用户亲自点「暂存本题」/「交卷」时调用，
 * 本模块不会自动提交，也不会替用户答题。
 */
class HomeworkApi(private val session: CxSession) {

    private val c get() = session.pcClient
    private val limiter = RequestLimiter(500L, 250L)

    /** 拉某门课的全部作业（自动翻页）；网络失败返回已拿到的部分，不抛异常 */
    suspend fun list(course: HomeworkCourse): List<HomeworkItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<HomeworkItem>()
        var page = 1
        var total = 1
        while (page <= total && page <= MAX_PAGES) {
            limiter.acquire()
            val resp = runCatching { c.get(listUrl(course, page), headers(course)) }.getOrNull()
                ?: break
            if (resp.code != 200 || resp.body.isBlank()) break
            if (looksLoggedOut(resp.body)) {
                LogStore.add("[作业] 会话已失效，读不到 ${course.title} 的作业列表", ok = false)
                break
            }
            out += HomeworkParser.parseList(resp.body, course)
            total = HomeworkParser.totalPages(resp.body).coerceAtMost(MAX_PAGES)
            page++
        }
        out
    }

    /** 打开作业状态页，解析出 doHomeWork 地址 */
    suspend fun resolve(course: HomeworkCourse, workId: String): HomeworkResolve =
        withContext(Dispatchers.IO) {
            limiter.acquire()
            val resp = runCatching { c.get(taskWorkUrl(course, workId), headers(course)) }.getOrNull()
                ?: return@withContext HomeworkResolve(null, "网络请求失败，稍后重试")
            if (resp.code != 200 || resp.body.isBlank()) {
                return@withContext HomeworkResolve(null, "作业页打不开（HTTP ${resp.code}）")
            }
            if (looksLoggedOut(resp.body)) {
                return@withContext HomeworkResolve(null, "会话已失效，请到账号页重新登录")
            }
            val url = HomeworkParser.doHomeWorkUrl(resp.body)
            val score = HomeworkParser.parseScore(resp.body)
            val redoable = HomeworkParser.parseRedoable(resp.body)
            if (url == null) {
                HomeworkResolve(
                    null,
                    "没找到作答入口（可能已交卷、已截止或需要重做）",
                    score = score,
                    redoable = redoable
                )
            } else {
                HomeworkResolve(
                    url,
                    score = score,
                    redoable = redoable,
                    answerId = HomeworkParser.workAnswerId(url)
                )
            }
        }

    /**
     * 重做（H5 `redo()` 的接口）：GET `/mooc-ans/work/phone/redo?workId=&classId=&courseId=&workAnswerId=`，
     * 返回 JSON `status==1` 表示成功，之后重新 resolve 就能拿到可作答的 doHomeWork 地址。
     *
     * 只在用户确认「重做」后调用；不做任何提交。
     */
    suspend fun redo(course: HomeworkCourse, workId: String, workAnswerId: String): HomeworkSubmit =
        withContext(Dispatchers.IO) {
            limiter.acquire()
            val url = "$MOOC_ANS/work/phone/redo?workId=$workId" +
                "&classId=${course.clazzId}&courseId=${course.courseId}" +
                "&workAnswerId=$workAnswerId"
            val headers = mapOf(
                "Referer" to taskWorkUrl(course, workId),
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
            android.util.Log.i("XXT-HW", "GET redo workId=$workId answerId=$workAnswerId")
            val resp = runCatching { c.get(url, headers) }.getOrNull()
                ?: return@withContext HomeworkSubmit(false, "网络请求失败，稍后重试")
            android.util.Log.i("XXT-HW", "redo code=${resp.code} body=${resp.body.take(300)}")
            if (resp.code != 200 || resp.body.isBlank()) {
                return@withContext HomeworkSubmit(false, "重做失败（HTTP ${resp.code}）")
            }
            val json = runCatching { JSONObject(resp.body) }.getOrNull()
                ?: return@withContext HomeworkSubmit(false, "重做返回不是 JSON：${resp.body.take(120)}")
            val status: Boolean = when (val s: Any? = json.opt("status")) {
                is Boolean -> s
                is Number -> s.toInt() != 0
                is String -> s == "1" || s.equals("true", ignoreCase = true)
                else -> false
            }
            val msg = json.optString("msg").trim()
            if (status) HomeworkSubmit(true, msg.ifBlank { "已重做" })
            else HomeworkSubmit(false, msg.ifBlank { "重做失败" })
        }

    /**
     * 扫描整份作业的题型：逐页取题，统计选择题（单选/多选/判断）数量。
     *
     * 用途：列表只显示「有选择题」的作业；详情页只有「全部是选择题」才允许交卷
     * （非选择题 App 里填不了，硬交会漏题）。
     *
     * 最多扫 [MAX_SCAN_PAGES] 页；一旦已知「有选择 + 有非选择」就提前停。
     */
    suspend fun scanChoices(doHomeWorkUrl: String): ChoiceScan = withContext(Dispatchers.IO) {
        var total = 0
        var choice = 0
        var nonChoice = 0
        var page = 0
        while (page < MAX_SCAN_PAGES) {
            val html = questionHtml(doHomeWorkUrl, page) ?: break
            val q = HomeworkParser.parseQuestion(html, "", page) ?: break
            total = maxOf(total, q.total)
            if (q.isChoice) choice++ else nonChoice++
            page++
            if (total > 0 && page >= total) break
            // 已经知道既有选择题又有非选择题，两个判断都定了，不用继续翻
            if (choice > 0 && nonChoice > 0) break
        }
        ChoiceScan(
            hasChoice = choice > 0,
            allChoice = choice > 0 && nonChoice == 0 && page >= (total.coerceAtLeast(1)),
            choiceCount = choice,
            total = if (total > 0) total else page,
            pagesRead = page
        )
    }

    /** 拉某一题（[index] 0 起）的 HTML；失败返回 null */
    suspend fun questionHtml(doHomeWorkUrl: String, index: Int): String? =
        withContext(Dispatchers.IO) {
            limiter.acquire()
            val url = withIndex(doHomeWorkUrl, index)
            android.util.Log.i("XXT-HW", "GET index=$index url=$url")
            val resp = runCatching { c.get(url, PC_REFERER) }.getOrNull() ?: return@withContext null
            android.util.Log.i(
                "XXT-HW",
                "GET index=$index code=${resp.code} len=${resp.body.length} final=${resp.finalUrl} " +
                    "setcookies=${resp.headers.values("Set-Cookie").joinToString(" | ").take(200)}"
            )
            if (resp.code != 200 || resp.body.isBlank() || looksLoggedOut(resp.body)) return@withContext null
            resp.body
        }

    /**
     * 暂存或正式交卷。
     *
     * 协议来自作业页自身的 JS（`submitFormNew`）：把 `form#submitTest` 全部字段
     * POST 到 `/work/phone/doNormalHomeWorkSubmit?tempSave=<true|false>`，
     * 返回 JSON：`status` / `msg` / `answerId` / `stuStatus`。
     *
     * 注意：这是**写请求**。UI 只会在用户亲自点「暂存本题」/「交卷」并确认后调用，
     * 本模块不会自动提交。
     */
    suspend fun submit(
        form: Map<String, String>,
        tempSave: Boolean,
        referer: String = ""
    ): HomeworkSubmit =
        withContext(Dispatchers.IO) {
            limiter.acquire()
            // 注意：URL 上的 tempSave 才是服务端判断「暂存/交卷」的依据，
            // body 里的 tempSave 只做冗余；两处必须一致，否则会把暂存当成交卷。
            val url = "$MOOC_ANS/work/phone/doNormalHomeWorkSubmit?tempSave=$tempSave"
            val body = form.toMutableMap()
            body["tempSave"] = tempSave.toString()
            val headers = LinkedHashMap(SUBMIT_HEADERS)
            if (referer.isNotBlank()) headers["Referer"] = referer
            android.util.Log.i(
                "XXT-HW",
                "POST submit tempSave=$tempSave fields=${body.size} " +
                    "answers=${body.keys.filter { it.startsWith("answer") }} " +
                    "referer=${referer.take(160)}"
            )
            val resp = runCatching { c.postForm(url, body, headers) }.getOrNull()
                ?: return@withContext HomeworkSubmit(false, "网络请求失败，稍后重试")
            android.util.Log.i(
                "XXT-HW",
                "POST submit tempSave=$tempSave code=${resp.code} len=${resp.body.length} " +
                    "body=${resp.body.take(400).replace('\n', ' ')}"
            )
            if (resp.code != 200 || resp.body.isBlank()) {
                return@withContext HomeworkSubmit(false, "提交失败（HTTP ${resp.code}）")
            }
            if (looksLoggedOut(resp.body)) {
                return@withContext HomeworkSubmit(false, "会话已失效，请到账号页重新登录")
            }
            val json = runCatching { JSONObject(resp.body) }.getOrNull()
                ?: return@withContext HomeworkSubmit(false, "提交返回不是 JSON：${resp.body.take(120)}")
            val status = when (val s: Any? = json.opt("status")) {
                is Boolean -> s
                is Number -> s.toInt() != 0
                is String -> s.equals("true", ignoreCase = true) || s == "1"
                else -> false
            }
            val msg = json.optString("msg").trim()
            val answerId = json.optString("answerId").trim()
            if (msg.contains("验证") || json.optInt("status") == 202) {
                HomeworkSubmit(false, msg.ifBlank { "提交需要验证码，请到网页端完成" })
            } else if (status) {
                HomeworkSubmit(true, msg.ifBlank { if (tempSave) "已暂存" else "提交成功" }, answerId)
            } else {
                HomeworkSubmit(false, msg.ifBlank { "提交失败" })
            }
        }

    private fun withIndex(url: String, index: Int): String {
        val full = if (url.startsWith("/")) "$MOOC_ANS$url" else url
        // 实测：doHomeWork 带 checkIntegrity=true 时，服务端会忽略 URL 里的 index，
        // 永远返回草稿当前题（第 1 题）；改成 false 后 index 正常翻页，且页面里
        // form#submitTest 的隐藏字段与 true 时完全一致（18 个字段、值相同）。
        val noIntegrity = full.replace("checkIntegrity=true", "checkIntegrity=false")
        return if (INDEX_PARAM.containsMatchIn(noIntegrity)) {
            INDEX_PARAM.replace(noIntegrity, "index=$index")
        } else {
            "$noIntegrity&index=$index"
        }
    }

    private fun listUrl(course: HomeworkCourse, page: Int): String =
        "$MOOC_ANS/work/task-list?courseId=${course.courseId}" +
            "&classId=${course.clazzId}" +
            "&cpi=${course.cpi.ifBlank { "0" }}" +
            "&pageNum=$page"

    private fun taskWorkUrl(course: HomeworkCourse, workId: String): String =
        "$MOOC_ANS/work/phone/task-work?taskrefId=$workId" +
            "&courseId=${course.courseId}" +
            "&classId=${course.clazzId}" +
            "&msgId=0" +
            "&cpi=${course.cpi.ifBlank { "0" }}" +
            "&ut=s" +
            "&fromGroup=1"

    private fun headers(course: HomeworkCourse): Map<String, String> = mapOf(
        "Referer" to "$MOOC_ANS/work/task-list?courseId=${course.courseId}&classId=${course.clazzId}"
    )

    private fun looksLoggedOut(body: String): Boolean =
        body.contains("passport2.chaoxing.com") ||
            body.contains("请输入手机号") ||
            body.contains("登录超时") ||
            body.contains("id=\"loginForm\"")

    companion object {
        const val BASE = "https://mooc1-api.chaoxing.com"
        const val MOOC_ANS = "$BASE/mooc-ans"
        private const val MAX_PAGES = 5
        private const val MAX_SCAN_PAGES = 20
        private val INDEX_PARAM = Regex("index=\\d+")

        private val PC_REFERER = mapOf("Referer" to "$MOOC_ANS/work/task")

        private val SUBMIT_HEADERS = mapOf(
            "Referer" to "$MOOC_ANS/work/phone/doHomeWork",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )
    }
}
