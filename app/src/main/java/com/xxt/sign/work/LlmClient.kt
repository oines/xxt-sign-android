package com.xxt.sign.work

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
import java.util.concurrent.TimeUnit

/**
 * 可替换的 HTTP 层。
 * 单测里注入假实现，就能把「重试 / 解析 / 校验」整条链路跑一遍，不联网。
 */
/** HTTP 结果：状态码 + 响应体（失败时也要带上，日志才能看出是 401/404/模型不存在） */
data class LlmHttpResult(val code: Int, val body: String)

interface LlmHttp {
    /** 网络层彻底失败（超时/连不上）返回 null；有响应就带上状态码和 body */
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): LlmHttpResult?
}

class OkHttpLlmHttp(private val client: OkHttpClient = Http.base) : LlmHttp {
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long
    ): LlmHttpResult? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
            .execute()
            .use { r -> LlmHttpResult(r.code, r.body?.string().orEmpty()) }
    }.getOrNull()
}

/**
 * 大模型兜底（DeepSeek 优先，任何 OpenAI 兼容接口都能用）。
 *
 * 只在题库没命中时调用（顺序由 [AnswerProvider] 保证），并且：
 *  - 只要求模型返回 `{"answer":"A"}` 这种 JSON，temperature=0、max_tokens 很小
 *  - 拿到之后必须过 [LlmFormat] 的格式校验，校验不过就留空，不猜
 *  - 网络失败重试 1 次（单次 20s 超时），还失败就留空，单题失败不影响整卷
 */
class LlmClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val http: LlmHttp = OkHttpLlmHttp(),
    private val timeoutMs: Long = 20_000L,
    private val retryDelayMs: Long = 800L,
    /** 这个模型是「先推理再出正文」，推理档位越低越省、越不干扰 JSON 输出 */
    private val effort: String = DEFAULT_EFFORT
) {

    /** 最近一次失败原因，给日志用 */
    @Volatile
    var lastError: String = ""
        private set

    val enabled: Boolean get() = apiKey.isNotBlank()

    private val endpoint: String
        get() = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL } + "/chat/completions"

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

        val body = JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("temperature", 0)
            // 不传 max_tokens：额度那点钱不值得省，传小了推理段会把预算吃光、正文变空
            .put("stream", false)
            .put("messages", messages)
        // 只填一个字母，把推理压到最低（low/high/max）
        val eff = effort.trim().lowercase()
        if (eff in EFFORTS) body.put("reasoning_effort", eff)
        return body.toString()
    }

    /** 调一次模型（含 1 次重试与校验） */
    suspend fun answer(q: WorkQuestion): QuizAnswer? = withContext(Dispatchers.IO) {
        if (!enabled) {
            lastError = "未配置 key"
            return@withContext null
        }
        val body = buildBody(q)
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )

        var resp: LlmHttpResult? = null
        for (attempt in 0..1) {
            resp = http.post(endpoint, headers, body, timeoutMs)
            // 4xx 是配置问题（key/地址/模型名），重试没意义
            if (resp != null && resp.code in 400..499) break
            if (resp != null && resp.code in 200..299) break
            if (attempt == 0 && retryDelayMs > 0) delay(retryDelayMs)
        }
        if (resp == null) {
            lastError = "连不上 ${endpoint}（网络或代理问题，已重试 1 次）"
            return@withContext null
        }

        // 失败就把原文写进日志，别只说「没有 content」
        if (resp.code !in 200..299) {
            lastError = readableError(resp)
            log("[LLM] 请求失败：${resp.code} ${resp.body.take(300)}", false)
            return@withContext null
        }

        val parsed = parseMessage(resp.body)
        val content = parsed.content
        if (content == null) {
            lastError = parsed.error
            log("[LLM] ${parsed.error}｜原始返回：${resp.body.take(300)}", false)
            return@withContext null
        }

        val check = LlmFormat.check(content, q)
        if (!check.ok) {
            lastError = check.reason
            log("[LLM] 格式校验没过（${check.reason}）｜原始返回：${resp.body.take(300)}", false)
            return@withContext null
        }
        QuizAnswer(check.value, "LLM·${model.ifBlank { DEFAULT_MODEL }}")
    }

    /** 把各种失败翻成人能看懂的一句话 */
    internal fun readableError(resp: LlmHttpResult): String = when (resp.code) {
        401, 403 -> "key 无效或没权限（HTTP ${resp.code}）"
        404 -> "接口地址不对（HTTP 404），base_url 应该是 https://api.deepseek.com 这种，不要带 /chat/completions"
        429 -> "请求太频繁被限流（HTTP 429）"
        in 500..599 -> "服务端错误（HTTP ${resp.code}）"
        else -> "HTTP ${resp.code}：${shortError(resp.body)}"
    }

    /** 200 但内容是 error / 空 content 的情况 */
    internal fun readableBodyError(body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        if (!msg.isNullOrBlank()) {
            return if (msg.contains("model", ignoreCase = true)) {
                "模型名不对：$msg（DeepSeek 官方只认 deepseek-chat / deepseek-reasoner）"
            } else {
                "服务端返回错误：$msg"
            }
        }
        return "返回里没有 choices[0].message.content：${body.take(120)}"
    }

    private fun shortError(body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return msg?.takeIf { it.isNotBlank() } ?: body.take(120)
    }

    /** 原始返回写日志，截断 300 字，排查用 */
    private fun log(message: String, ok: Boolean) {
        runCatching { com.xxt.sign.core.LogStore.add(message, ok) }
    }

    data class Parsed(
        val content: String?,
        val error: String = "",
        val fromReasoning: Boolean = false,
        val finishReason: String = ""
    )

    /**
     * 解析一条 chat/completions 响应。
     *
     * 踩过的坑：这个模型「先推理后正文」，`max_tokens` 小的时候正文直接是空的，
     * 推理内容在 `reasoning_content` / `reasoning` 里。所以：
     *  - 先读 content
     *  - content 空就读推理段，里面要是有 JSON 答案也能用（标一下来自推理）
     *  - 两边都空，给一句能直接定位原因的话
     */
    internal fun parseMessage(raw: String): Parsed {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Parsed(null, "返回不是 JSON：${raw.take(120)}")

        root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let { msg ->
            return Parsed(null, readableBodyError(raw))
        }

        val choices = root.optJSONArray("choices")
        val first = choices?.optJSONObject(0)
        val msg = first?.optJSONObject("message")

        val content = (msg?.optString("content")?.takeIf { it.isNotBlank() }
            ?: first?.optString("text")?.takeIf { it.isNotBlank() })

        if (content != null) return Parsed(content, finishReason = first?.optString("finish_reason").orEmpty())

        val reasoning = listOf("reasoning_content", "reasoning")
            .firstNotNullOfOrNull { k -> msg?.optString(k)?.takeIf { it.isNotBlank() } }
        if (reasoning != null) {
            // 推理段里可能就有 {"answer":"A"}
            val hasJson = Regex("\\{\\s*\"answer\"").containsMatchIn(reasoning)
            return if (hasJson) {
                Parsed(reasoning, fromReasoning = true, finishReason = first?.optString("finish_reason").orEmpty())
            } else {
                Parsed(
                    null,
                    "模型只返回了推理，没有正文（reasoning_effort 太高，或服务端把预算用完了）"
                )
            }
        }

        val finish = first?.optString("finish_reason").orEmpty()
        return Parsed(
            null,
            if (finish == "length")
                "输出被截断，推理占用全部额度（finish_reason=length）。正常情况下我们没传 max_tokens，" +
                    "如果你手动设过上限，把它去掉"
            else "返回里没有 choices[0].message.content：${raw.take(120)}",
            finishReason = finish
        )
    }

    /** 测试连接：发一条最小请求，把结果说成人话 */
    suspend fun probe(): Probe = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext Probe(false, "还没填 key")
        val body = JSONObject()
            .put("model", model.ifBlank { DEFAULT_MODEL })
            .put("temperature", 0)
            .put("stream", false)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "回复 ok"))
            )
            .toString()
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        )
        val started = System.currentTimeMillis()
        val resp = runCatching { http.post(endpoint, headers, body, timeoutMs) }.getOrNull()
            ?: return@withContext Probe(false, "连不上 $endpoint（网络或代理）")
        val ms = System.currentTimeMillis() - started

        if (resp.code !in 200..299) {
            return@withContext Probe(false, readableError(resp) + "｜" + resp.body.take(200))
        }
        val parsed = parseMessage(resp.body)
        val finish = parsed.finishReason.ifBlank { "-" }
        if (parsed.content != null) {
            Probe(
                true,
                "通了：content=\"${parsed.content.trim().take(40)}\", finish_reason=$finish, 耗时 ${ms}ms"
            )
        } else {
            Probe(false, "${parsed.error}（finish_reason=$finish，耗时 ${ms}ms）")
        }
    }

    data class Probe(val ok: Boolean, val detail: String)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        /** 推理档位：low 最省，只填一个字母不需要想太多 */
        const val DEFAULT_EFFORT = "low"
        val EFFORTS = setOf("low", "medium", "high", "max")

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
