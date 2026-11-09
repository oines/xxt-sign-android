package com.xxt.sign.work

import com.xxt.sign.net.Http
import kotlinx.coroutines.CancellationException
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
 * 把任意 Throwable（含 [ExceptionInInitializerError] / [NoClassDefFoundError]）压成一行人话。
 *
 * 真机上 LlmFormat 的静态初始化在 Android 的 ICU 正则引擎上炸过：类初始化失败之后，
 * 第一次调用只会拿到 ExceptionInInitializerError（消息常常是空的），后面每次都是
 * NoClassDefFoundError「Rejecting re-init on previously-failed class」。这两种错误
 * 不是 Exception，只 catch Exception 根本接不住，所以整条 LLM 链路会被静默吞掉。
 * 这里只用类名 + message + cause 拼字符串，**不用正则**。
 */
internal fun describeThrowable(t: Throwable): String {
    val self = t::class.java.name + ": " + (t.message?.trim()?.takeIf { it.isNotEmpty() } ?: "（无消息）")
    val cause = t.cause
    if (cause == null || cause === t) return self
    val inner = cause::class.java.name + ": " +
        (cause.message?.trim()?.takeIf { it.isNotEmpty() } ?: "（无消息）")
    return "$self ← $inner"
}

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

    /** 外部（[AnswerProvider] 等）catch 到 Throwable 时，把原因写回来，别让日志显示「没有原因」 */
    internal fun recordFailure(reason: String) {
        lastError = reason
        log("[LLM] $reason", false)
    }

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

    /**
     * 调一次模型（含 1 次重试与校验）。
     *
     * **整条链路兜住 Throwable**：解析/校验（包括 [LlmFormat] 的类初始化）出任何问题，
     * 都要留下「一题一行 + 原始响应前 200 字」的日志再留空，绝不静默。
     * 协程取消要原样抛出，不能被兜住。
     */
    suspend fun answer(q: WorkQuestion): QuizAnswer? = withContext(Dispatchers.IO) {
        try {
            callOnce(q)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val reason = "调用异常：${describeThrowable(t)}"
            lastError = reason
            log("[LLM] $reason｜题：${brief(q.stem, 40)}", false)
            null
        }
    }

    /** [answer] 的实现体；异常统一由 [answer] 兜住并写日志 */
    private suspend fun callOnce(q: WorkQuestion): QuizAnswer? {
        lastError = ""
        if (!enabled) {
            lastError = "未配置 key"
            return null
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
            return null
        }

        // 失败就把原文写进日志，别只说「没有 content」
        if (resp.code !in 200..299) {
            lastError = readableError(resp)
            log("[LLM] 请求失败：${resp.code} ${brief(resp.body)}", false)
            return null
        }

        val raw = resp.body
        val parsed = try {
            parseMessage(raw)
        } catch (t: Throwable) {
            return parseFailed(raw, "解析响应异常：${describeThrowable(t)}")
        }
        val content = parsed.content
        if (content == null) {
            lastError = parsed.error
            log("[LLM] ${parsed.error}｜原始返回：${brief(raw)}", false)
            return null
        }

        // LlmFormat 的类初始化一旦失败（真机上 ICU 正则炸过），这里会抛 ExceptionInInitializerError /
        // NoClassDefFoundError，必须接住并写日志，否则整卷都会变成「题库/LLM 都没命中」还查不到原因。
        val check = try {
            LlmFormat.check(content, q)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return parseFailed(raw, "格式校验异常：${describeThrowable(t)}")
        }
        if (!check.ok) {
            lastError = check.reason
            log("[LLM] 格式校验没过（${check.reason}）｜原始返回：${brief(raw)}", false)
            return null
        }
        // 成功也要留一行：真机排查时要能直接看到模型到底回了哪个选项
        log("[LLM] 答案=${check.value}｜题：${brief(q.stem, 40)}", true)
        return QuizAnswer(check.value, "LLM·${model.ifBlank { DEFAULT_MODEL }}")
    }

    /** 解析/校验抛异常时的统一出口：原因 + 原始响应前 200 字，一题一行 */
    private fun parseFailed(rawBody: String, reason: String): QuizAnswer? {
        lastError = reason
        log("[LLM] $reason｜原始返回：${brief(rawBody)}", false)
        return null
    }

    /** 日志用：压成一行并按 [max] 截断（默认 200 字） */
    internal fun brief(raw: String, max: Int = MAX_LOG_CHARS): String =
        raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim().take(max)

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
            // 推理段里可能就有 {"answer":"A"}（字符串查找，不用正则）
            val hasJson = LlmFormat.hasAnswerKey(reasoning)
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

        /** 日志里原始返回只留前 200 字 */
        internal const val MAX_LOG_CHARS = 200
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
 *
 * **这里一处正则都不许有。** 真机（Pixel 5 / Android 14）上静态初始化里抠 JSON 对象的那条正则
 * 被 Android 的 ICU 正则引擎抛了 PatternSyntaxException（消息还是空的），变成
 * ExceptionInInitializerError → 类永久不可用 → 整卷 LLM 全挂。JVM 上不抛，单测和编译都发现不了。
 * 所以去 markdown 围栏、取 JSON 对象全改成 indexOf / substring，纯字符串处理。
 */
object LlmFormat {

    data class Check(val ok: Boolean, val value: String = "", val reason: String = "")

    private val REFUSALS = listOf(
        "无法确定", "无法回答", "无法判断", "不确定", "不知道", "抱歉",
        "不能确定", "无法提供", "unknown", "n/a", "none", "无答案"
    )

    /** Markdown 围栏标记。字符串常量，不涉及任何正则引擎 */
    internal const val FENCE = "```"

    fun check(content: String, q: WorkQuestion): Check {
        return try {
            val answer = extractAnswer(content)
                ?: return Check(false, reason = "不是 {\"answer\":...} 结构")
            when (q.type) {
                WorkQuestion.TYPE_SINGLE -> single(answer, q)
                WorkQuestion.TYPE_MULTI -> multi(answer, q)
                WorkQuestion.TYPE_JUDGE -> judge(answer)
                WorkQuestion.TYPE_BLANK, WorkQuestion.TYPE_ESSAY -> text(answer)
                else -> Check(false, reason = "未知题型 ${q.type}，不采用")
            }
        } catch (t: Throwable) {
            // 兜底：解析器自己出任何问题都只算「这题没答案」，绝不把异常抛给调用方
            Check(false, reason = "解析异常：${describeThrowable(t)}")
        }
    }

    /**
     * 从模型输出里抠出 answer 字段；容忍 ```json 包裹和前后废话。
     *
     * 全是字符串查找（indexOf / substring / trim），没有一处正则，也不依赖 ICU。
     */
    fun extractAnswer(content: String): String? {
        answerFrom(stripFence(content))?.let { return it }
        // 围栏不成对（只开了没关 / 只有结尾）时，按「删掉所有围栏」再来一次
        return answerFrom(removeFences(content))
    }

    /** 取第一个 `{` 到最后一个 `}`，再解析 JSON 拿 answer */
    private fun answerFrom(text: String): String? {
        val candidate = pickJsonObject(text) ?: return null
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

    /**
     * 去掉 ```json 围栏：找第一个 ``` 与最后一个 ```，取中间，再 trim。
     * 只有一个围栏（没闭合）时，取它后面的剩下全部。
     */
    internal fun stripFence(content: String): String {
        val first = content.indexOf(FENCE)
        if (first < 0) return content.trim()
        val after = first + FENCE.length
        val last = content.lastIndexOf(FENCE)
        val inner = if (last > after) content.substring(after, last) else content.substring(after)
        return inner.trim()
    }

    /** 把内容里所有 ``` 围栏标记删掉（连带紧跟的语言标记 json / JSON / javascript） */
    internal fun removeFences(content: String): String {
        val sb = StringBuilder(content.length)
        var i = 0
        while (i < content.length) {
            if (content.startsWith(FENCE, i)) {
                i += FENCE.length
                while (i < content.length && content[i].isWhitespace()) i++
                while (i < content.length && content[i].isLetter()) i++
                continue
            }
            sb.append(content[i])
            i++
        }
        return sb.toString().trim()
    }

    /** 取第一个 `{` 到最后一个 `}` 之间的内容；取不到（没括号 / 顺序反了）就返回 null */
    internal fun pickJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    /** 文本里是否出现 {"answer" 这种结构（花括号与引号之间允许空白），纯字符串扫描 */
    fun hasAnswerKey(text: String): Boolean {
        var from = text.indexOf('{')
        while (from >= 0) {
            var i = from + 1
            while (i < text.length && text[i].isWhitespace()) i++
            if (text.startsWith("\"answer\"", i)) return true
            from = text.indexOf('{', from + 1)
        }
        return false
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
