package com.xxt.sign.work

import com.xxt.sign.App
import com.xxt.sign.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 可替换的 HTTP 层。
 * 单测里注入假实现，就能把「重试 / 解析 / 校验」整条链路跑一遍，不联网。
 */
interface LlmHttp {
    /** 成功返回响应体；网络失败 / 超时 / 非 2xx 返回 null */
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): String?
}

class OkHttpLlmHttp(private val client: OkHttpClient = Http.base) : LlmHttp {
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long
    ): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
            .execute()
            .use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()
}

/**
 * 大模型兜底（DeepSeek 优先，任何 OpenAI 兼容接口都能用）。
 *
 * 只在题库没命中时调用（顺序由 [AnswerProvider] 保证），并且：
 *  - 只要求模型返回 `{"answer":"A"}` 这种 JSON，temperature=0、max_tokens 很小
 *  - 拿到之后必须过 [LlmFormat] 的格式校验，校验不过就留空，不猜
 *  - 网络失败重试 1 次（单次 20s 超时），还失败就留空，单题失败不影响整卷
 *  - 成本保护：一卷不超过题量、每天不超过设置的上限
 */
class LlmClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val http: LlmHttp = OkHttpLlmHttp(),
    private val timeoutMs: Long = 20_000L,
    /** 每日配额，单测里注入 { true } 跳过 SharedPreferences */
    private val quota: () -> Boolean = { LlmQuota.tryConsume() },
    private val retryDelayMs: Long = 800L
) {

    /** 最近一次失败原因，给日志用 */
    @Volatile
    var lastError: String = ""
        private set

    // 默认不限制；每卷开始时由 beginPaper(题量) 收紧
    private var paperBudget = Int.MAX_VALUE

    val enabled: Boolean get() = apiKey.isNotBlank()

    private val endpoint: String
        get() = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL } + "/chat/completions"

    /** 一卷开始：这一卷最多能调几次（不超过题量） */
    fun beginPaper(questionCount: Int) {
        paperBudget = questionCount.coerceAtLeast(0)
    }

    /**
     * 构造请求体。纯函数，方便单测断言 prompt 里包含了题干/选项/题型并且只要 JSON。
     */
    fun buildBody(q: WorkQuestion): String {
        val typeName = WorkQuestion.typeName(q.type)
        val builder = StringBuilder()
        builder.append("题型：").append(typeName).append('\n')
        builder.append("题干：").append(q.stem).append('\n')
        if (q.options.isNotEmpty()) {
            builder.append("选项：\n")
            q.options.forEach { builder.append(it.key).append(". ").append(it.text).append('\n') }
        }
        builder.append(
            when (q.type) {
                WorkQuestion.TYPE_SINGLE -> "只返回一个正确选项的字母，例如 {\"answer\":\"A\"}"
                WorkQuestion.TYPE_MULTI -> "返回所有正确选项的字母并排序，例如 {\"answer\":\"ABC\"}"
                WorkQuestion.TYPE_JUDGE -> "答案只能是 true 或 false，例如 {\"answer\":\"true\"}"
                else -> "直接给出答案文本，多个空用分号分隔，例如 {\"answer\":\"文本\"}"
            }
        )
        builder.append("\n只输出 JSON 本身，不要解释、不要 Markdown。")

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", builder.toString()))

        return JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("temperature", 0)
            .put("max_tokens", 48)
            .put("stream", false)
            .put("messages", messages)
            .toString()
    }

    /** 调一次模型（含 1 次重试与校验） */
    suspend fun answer(q: WorkQuestion): QuizAnswer? = withContext(Dispatchers.IO) {
        if (!enabled) {
            lastError = "未配置 key"
            return@withContext null
        }
        if (paperBudget <= 0) {
            lastError = "本卷 LLM 预算用尽"
            return@withContext null
        }
        if (!quota()) {
            lastError = "达到每日上限 ${LlmQuota.limit()}"
            return@withContext null
        }
        paperBudget--

        val body = buildBody(q)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )

        var raw: String? = null
        for (attempt in 0..1) {
            raw = http.post(endpoint, headers, body, timeoutMs)
            if (raw != null) break
            if (attempt == 0 && retryDelayMs > 0) delay(retryDelayMs)
        }
        if (raw == null) {
            lastError = "网络失败（已重试 1 次）"
            return@withContext null
        }

        val content = extractContent(raw)
        if (content == null) {
            lastError = "响应里没有 choices[0].message.content"
            return@withContext null
        }

        val check = LlmFormat.check(content, q)
        if (!check.ok) {
            lastError = check.reason
            return@withContext null
        }
        QuizAnswer(check.value, "LLM·${model.ifBlank { DEFAULT_MODEL }}")
    }

    /** OpenAI 兼容响应 → content；兼容 ```json 包裹 */
    internal fun extractContent(raw: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        val first = choices.optJSONObject(0) ?: return null
        val content = first.optJSONObject("message")?.optString("content")
            ?.takeIf { it.isNotBlank() } ?: first.optString("text").takeIf { it.isNotBlank() }
        return content
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        private const val SYSTEM_PROMPT =
            "你是答题器。只输出一个 JSON 对象，不要任何解释、不要 Markdown 代码块。"
    }
}

/**
 * LLM 返回值的格式校验。
 *
 * 规则（任何一条不过都留空，不猜）：
 *  - 单选：恰好一个字母，且必须在该题的选项里
 *  - 多选：非空、去重排序，且是选项字母集合的子集
 *  - 判断：只能是 true / false
 *  - 填空/简答：非空、长度合理、不是「无法确定」这类拒答
 */
object LlmFormat {

    data class Check(val ok: Boolean, val value: String = "", val reason: String = "")

    private val REFUSALS = listOf(
        "无法确定", "无法回答", "无法判断", "不确定", "不知道", "抱歉",
        "不能确定", "无法提供", "unknown", "n/a", "none", "无答案"
    )

    internal val FENCE = Regex("```(?:json)?", RegexOption.IGNORE_CASE)
    internal val JSON_OBJ = Regex("\\{[\\s\\S]*}")

    fun check(content: String, q: WorkQuestion): Check {
        val answer = extractAnswer(content)
            ?: return Check(false, reason = "不是 {\"answer\":...} 结构")
        return when (q.type) {
            WorkQuestion.TYPE_SINGLE -> single(answer, q)
            WorkQuestion.TYPE_MULTI -> multi(answer, q)
            WorkQuestion.TYPE_JUDGE -> judge(answer)
            WorkQuestion.TYPE_BLANK, WorkQuestion.TYPE_ESSAY -> text(answer)
            else -> Check(false, reason = "未知题型 ${q.type}，不采用")
        }
    }

    /** 从模型输出里抠出 answer 字段；容忍 ```json 包裹和前后废话 */
    fun extractAnswer(content: String): String? {
        val cleaned = FENCE.replace(content, "").trim()
        val candidate = JSON_OBJ.find(cleaned)?.value ?: cleaned
        val json = runCatching { JSONObject(candidate) }.getOrNull() ?: return null
        val raw = json.opt("answer") ?: return null
        val s = when (raw) {
            is String -> raw
            is Number, is Boolean -> raw.toString()
            is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                raw.opt(i)?.toString()
            }.joinToString("")
            else -> return null
        }
        return s.trim().ifBlank { null }
    }

    private fun single(raw: String, q: WorkQuestion): Check {
        val letters = raw.uppercase().toCharArray().filter { it in 'A'..'Z' }
        if (letters.size != 1) return Check(false, reason = "单选答案不是单个字母：$raw")
        val key = letters[0].toString()
        val keys = q.options.map { it.key.uppercase() }.toSet()
        if (keys.isNotEmpty() && key !in keys) {
            return Check(false, reason = "单选字母 $key 不在选项 $keys 内")
        }
        return Check(true, key)
    }

    private fun multi(raw: String, q: WorkQuestion): Check {
        val letters = raw.uppercase().toCharArray()
            .filter { it in 'A'..'Z' }
            .distinct()
            .sorted()
        if (letters.isEmpty()) return Check(false, reason = "多选答案里没有字母：$raw")
        val keys = q.options.map { it.key.uppercase() }.toSet()
        val valid = letters.filter { it.toString() in keys }
        if (valid.size != letters.size) {
            return Check(false, reason = "多选包含不在选项里的字母：$raw")
        }
        return Check(true, valid.joinToString("") { it.toString() })
    }

    private fun judge(raw: String): Check {
        return when (raw.trim().lowercase()) {
            "true" -> Check(true, "true")
            "false" -> Check(true, "false")
            else -> Check(false, reason = "判断题答案不是 true/false：$raw")
        }
    }

    private fun text(raw: String): Check {
        val s = raw.trim()
        if (s.isEmpty()) return Check(false, reason = "填空答案为空")
        if (s.length > 200) return Check(false, reason = "填空答案过长（${s.length} 字）")
        val lower = s.lowercase()
        if (REFUSALS.any { lower.contains(it) }) {
            return Check(false, reason = "模型拒答：${s.take(20)}")
        }
        return Check(true, s)
    }
}

/**
 * LLM 每日调用上限。
 *
 * 用 SharedPreferences 记「日期 + 已用次数」，跨天自动归零。
 * 超限时 [tryConsume] 返回 false，调用方就只走题库。
 */
object LlmQuota {

    private const val PREF = "llm_quota"

    private fun sp() = App.ctx.getSharedPreferences(PREF, 0)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun limit(): Int = runCatching { com.xxt.sign.core.Settings.quizLlmDailyLimit }.getOrDefault(200)

    fun used(): Int {
        val sp = sp()
        return if (sp.getString("date", "") == today()) sp.getInt("used", 0) else 0
    }

    @Synchronized
    fun tryConsume(): Boolean {
        val limit = limit()
        if (limit <= 0) return false
        val sp = sp()
        val day = today()
        val used = if (sp.getString("date", "") == day) sp.getInt("used", 0) else 0
        if (used >= limit) return false
        sp.edit().putString("date", day).putInt("used", used + 1).apply()
        return true
    }
}
