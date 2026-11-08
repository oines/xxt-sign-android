package com.xxt.sign.work

import com.xxt.sign.net.Http
import com.xxt.sign.net.enc
import com.xxt.sign.study.RequestLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 答案来源。三种模式，顺序写死在代码里，不会被配置搞反：
 *
 *  - [Mode.BANK_FIRST]（auto / tikuadapter / yanxi / lemon）
 *      题库优先；没命中才走大模型（LLM 没配 key 就留空）
 *  - [Mode.LLM_ONLY]（provider = llm）
 *      完全不查题库，每题直接问大模型
 *  - [Mode.OFF]（off / none / 留空）
 *      都不查，只暂存/留空
 *
 * 题库本身支持 tikuAdapter / 言溪 / 柠檬，都是公网 HTTP、不需要学习通登录态。
 */
class AnswerProvider(
    private val provider: String,
    private val endpoint: String,
    private val token: String,
    private val llm: LlmClient? = null,
    private val client: OkHttpClient = Http.base
) {

    enum class Mode { BANK_FIRST, LLM_ONLY, OFF }

    val mode: Mode
        get() = when (provider.trim().lowercase()) {
            "", "off", "none", "disable" -> Mode.OFF
            "llm", "off+llm" -> Mode.LLM_ONLY
            else -> Mode.BANK_FIRST
        }

    private val limiter = RequestLimiter(300L, 200L)

    /**
     * 实际生效的题库：
     *  - 空 / auto  → 内置免费源（柠檬），用户不用配任何东西也能跑
     *  - off        → 明确关掉，一题都不查
     */
    private val effective: String
        get() = when (provider.trim().lowercase()) {
            // 留空 / off / none → 明确不查题库，只暂存
            "", "off", "disable", "none" -> "off"
            // auto → 内置免费源
            "auto" -> "lemon"
            else -> provider.trim().lowercase()
        }

    private val bankReady: Boolean
        get() = effective != "off" && (effective != "tikuadapter" || endpoint.isNotBlank())

    private val llmReady: Boolean
        get() = llm?.enabled == true

    val configured: Boolean
        get() = when (mode) {
            Mode.OFF -> false
            Mode.LLM_ONLY -> llmReady
            Mode.BANK_FIRST -> bankReady || llmReady
        }

    /** 一卷开始：把这一卷的 LLM 预算（不超过题量）重置 */
    fun beginPaper(questionCount: Int) {
        llm?.beginPaper(questionCount)
    }

    /** 最近一次 LLM 失败原因，给日志用 */
    val lastLlmError: String get() = llm?.lastError.orEmpty()

    suspend fun search(question: WorkQuestion): QuizAnswer? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null

        // 1) 题库优先（纯 LLM 模式跳过）
        if (mode == Mode.BANK_FIRST && bankReady) {
            val fromBank = runCatching {
                limiter.acquire()
                when (effective) {
                    "tikuadapter" -> tikuAdapter(question)
                    "yanxi" -> getStyle("https://${host(endpoint, "tk.enncy.cn")}/query", question, "言溪")
                    "lemon" -> getStyle("https://${host(endpoint, "api.lemtk.xyz")}/api/v1/cx", question, "柠檬")
                    else -> null
                }
            }.getOrNull()
            if (fromBank != null) return@withContext fromBank
        }

        // 2) 题库没命中（或本来就是纯 LLM 模式）才问大模型
        if (llmReady) {
            val fromLlm = runCatching { llm?.answer(question) }.getOrNull()
            if (fromLlm != null) return@withContext fromLlm
        }
        null
    }

    private fun host(raw: String, def: String): String =
        raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').ifBlank { def }

    private fun tikuAdapter(q: WorkQuestion): QuizAnswer? {
        val base = endpoint.trim().trimEnd('/')
        val url = if (base.contains("/adapter-service")) "$base/search" else "$base/adapter-service/search"
        val body = JSONObject().apply {
            put("question", q.stem)
            put("options", JSONArray(q.options.map { it.text }))
            put("type", q.type)
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", Http.DESKTOP_UA)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val text = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        return parse(text, q, "tikuAdapter")
    }

    private fun getStyle(url: String, q: WorkQuestion, source: String): QuizAnswer? {
        val full = buildString {
            append(url)
            append(if (url.contains('?')) '&' else '?')
            append("question=").append(enc(q.stem))
            if (q.options.isNotEmpty()) {
                val opts = q.options.joinToString("\n") { "${it.key}.${it.text}" }
                append("&options=").append(enc(opts))
            }
            append("&type=").append(q.type)
            if (token.isNotBlank()) append("&token=").append(enc(token))
        }
        val req = Request.Builder().url(full).header("User-Agent", Http.DESKTOP_UA).build()
        val text = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        return parse(text, q, source)
    }

    /** 两个题库的返回都是 JSON，但字段名不一致，这里做宽松提取 */
    private fun parse(text: String, q: WorkQuestion, source: String): QuizAnswer? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val raw = findAnswerString(root) ?: return null
        val normalized = AnswerNormalizer.normalize(raw, q) ?: return null
        return QuizAnswer(normalized, "题库·$source")
    }

    /** 在 JSON 里找第一个看起来像答案的内容 */
    private fun findAnswerString(node: Any?, depth: Int = 0): String? {
        if (depth > 6) return null
        when (node) {
            is JSONObject -> {
                for (key in listOf("answerKey", "answerText", "answer", "result")) {
                    val s = scalar(node.opt(key))
                    if (!s.isNullOrBlank()) return s
                }
                val nested = node.opt("answer")
                if (nested is JSONObject) findAnswerString(nested, depth + 1)?.let { return it }
                val arr = node.opt("options")
                if (arr is JSONArray) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optBoolean("isCorrect") || o.optBoolean("correct")) {
                            scalar(o.opt("key"))?.let { return it }
                        }
                    }
                }
                val data = node.opt("data")
                if (data != null && data !is String) findAnswerString(data, depth + 1)?.let { return it }
                return null
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findAnswerString(node.opt(i), depth + 1)?.let { return it }
                }
            }
            is String -> return node.takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun scalar(v: Any?): String? = when (v) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> null
    }
}

/**
 * 把题库返回的答案归一化成页面提交需要的格式：
 *  - 单选 "A"
 *  - 多选字母升序 "ABC"（**必须排序**）
 *  - 判断 "true"/"false"
 *  - 填空/简答：原文
 *
 * 纯函数，方便单测。
 */
object AnswerNormalizer {

    fun normalize(raw: String, q: WorkQuestion): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when (q.type) {
            WorkQuestion.TYPE_SINGLE -> single(s, q)
            WorkQuestion.TYPE_MULTI -> multi(s, q)
            WorkQuestion.TYPE_JUDGE -> judge(s)
            WorkQuestion.TYPE_BLANK, WorkQuestion.TYPE_ESSAY -> s
            else -> null
        }
    }

    private fun single(raw: String, q: WorkQuestion): String? {
        val keys = keysOf(q)
        val letters: List<Char> = raw.uppercase().toCharArray().filter { it in 'A'..'Z' }
        val hit = letters.firstOrNull { it.toString() in keys }
        if (hit != null) return hit.toString()
        // 题库有时直接回选项原文
        return q.options.firstOrNull { it.text.isNotBlank() && raw.contains(it.text) }?.key
    }

    private fun multi(raw: String, q: WorkQuestion): String? {
        val keys = keysOf(q)
        val letters: List<Char> = raw.uppercase().toCharArray()
            .filter { it in 'A'..'Z' }
            .distinct()
            .sorted()
        val valid: List<Char> = letters.filter { it.toString() in keys }
        if (valid.isEmpty()) return null
        return valid.joinToString("") { it.toString() }
    }

    private fun judge(raw: String): String? {
        val s = raw.lowercase()
        return when {
            s.contains("错") || s.contains("false") || s == "f" || s.contains("不对") ||
                s.contains("×") || s.contains("否") -> "false"
            s.contains("对") || s.contains("true") || s == "t" || s.contains("正确") ||
                s.contains("√") || s.contains("是") -> "true"
            else -> null
        }
    }

    private fun keysOf(q: WorkQuestion): Set<String> = q.options.map { it.key.uppercase() }.toSet()
}
