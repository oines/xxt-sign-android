package com.xxt.sign.work

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LLM 请求体构造 + 假 HttpClient 下的重试/解析链路（不联网） */
class LlmClientTest {

    private class FakeHttp(private val responses: MutableList<LlmHttpResult?>) : LlmHttp {
        var calls = 0
            private set
        var lastBody: String = ""
            private set
        var lastUrl: String = ""
            private set

        override fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
            timeoutMs: Long
        ): LlmHttpResult? {
            calls++
            lastUrl = url
            lastBody = body
            return if (responses.isNotEmpty()) responses.removeAt(0) else null
        }
    }

    private val question = WorkQuestion(
        qid = "1",
        type = WorkQuestion.TYPE_SINGLE,
        stem = "违反安全保障义务责任属于（）",
        options = listOf(
            WorkOption("A", "公平责任"),
            WorkOption("B", "特殊侵权责任")
        )
    )

    /** {"answer":"X"} 的小工具，避免三引号踩到 JSON 里的引号 */
    private fun ans(v: String) = "{\"answer\":\"$v\"}"

    private fun ok(body: String) = LlmHttpResult(200, body)

    private fun response(content: String): String = JSONObject().apply {
        put(
            "choices",
            org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content))
            )
        )
    }.toString()

    private fun client(http: LlmHttp) = LlmClient(
        apiKey = "sk-test",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        http = http,
        timeoutMs = 20_000L,
        retryDelayMs = 0
    )

    @Test
    fun buildsRequestBodyWithModelTemperatureAndPrompt() {
        val http = FakeHttp(mutableListOf(ok(response(ans("A")))))
        val c = client(http)
        val body = JSONObject(c.buildBody(question))

        assertEquals("deepseek-flash", body.getString("model"))
        assertEquals(0, body.getInt("temperature"))
        assertTrue(!body.has("max_tokens"))
        assertEquals("low", body.getString("reasoning_effort"))

        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        val user = messages.getJSONObject(1).getString("content")
        assertTrue(user.contains("题干："))
        assertTrue(user.contains("违反安全保障义务"))
        assertTrue(user.contains("A. 公平责任"))
        assertTrue(user.contains("单选"))
        assertTrue(user.contains("JSON"))

        runBlocking { c.answer(question) }
        assertEquals("https://api.deepseek.com/chat/completions", http.lastUrl)
        assertTrue(http.lastBody.contains("违反安全保障义务"))
    }

    @Test
    fun validResponseBecomesAnswerWithLlmSource() = runBlocking {
        val http = FakeHttp(mutableListOf(ok(response(ans("B")))))
        val r = client(http).answer(question)
        assertEquals("B", r?.value)
        assertTrue(r!!.source.startsWith("LLM"))
        assertEquals(1, http.calls)
    }

    @Test
    fun retriesOnceOnNetworkFailure() = runBlocking {
        val http = FakeHttp(mutableListOf(null, ok(response(ans("A")))))
        val c = client(http)
        assertEquals("A", c.answer(question)?.value)
        assertEquals(2, http.calls)
    }

    @Test
    fun givesUpAfterOneRetry() = runBlocking {
        val http = FakeHttp(mutableListOf(null, null, ok(response(ans("A")))))
        val c = client(http)
        assertNull(c.answer(question))
        assertEquals(2, http.calls)
        assertTrue(c.lastError.contains("网络"))
    }

    @Test
    fun invalidFormatIsNotAdoptedAndNotRetried() = runBlocking {
        val http = FakeHttp(mutableListOf(ok(response(ans("Z")))))
        val c = client(http)
        assertNull(c.answer(question))
        assertEquals(1, http.calls)
        assertTrue(c.lastError.contains("字母"))
    }

    // ---------------- 真机实测出来的两类失败 ----------------

    /** 实测：max_tokens 传小了推理把额度吃光，content 为空、finish_reason=length */
    @Test
    fun truncatedResponseIsReportedWithoutRetry() = runBlocking {
        val truncated = JSONObject().apply {
            put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put("message", JSONObject().put("content", ""))
                    .put("finish_reason", "length")
            ))
        }.toString()
        val http = FakeHttp(mutableListOf(ok(truncated), ok(response(ans("A")))))
        val c = client(http)

        assertNull(c.answer(question))
        assertEquals("不传 max_tokens 之后不该再有改额度重试", 1, http.calls)
        assertTrue(c.lastError, c.lastError.contains("截断"))
    }

    /** 只有推理、没有正文：要能读出来并明确说明 */
    @Test
    fun reasoningOnlyResponseIsReported() = runBlocking {
        val body = JSONObject().apply {
            put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put("message", JSONObject().put("content", "").put("reasoning_content", "让我想想…"))
                    .put("finish_reason", "stop")
            ))
        }.toString()
        val c = client(FakeHttp(mutableListOf(ok(body))))
        assertNull(c.answer(question))
        assertTrue(c.lastError, c.lastError.contains("推理"))
    }

    /** 推理段里带 JSON 答案：可以用，别浪费 */
    @Test
    fun answerInsideReasoningIsUsable() = runBlocking {
        val body = JSONObject().apply {
            put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put(
                        "message",
                        JSONObject().put("content", "")
                            .put("reasoning_content", "分析后我觉得是 {\"answer\":\"B\"}")
                    )
                    .put("finish_reason", "stop")
            ))
        }.toString()
        val r = client(FakeHttp(mutableListOf(ok(body)))).answer(question)
        assertEquals("B", r?.value)
    }

    /** 服务端报错（例如模型不存在）：要能看懂，而不是「没有 content」 */
    @Test
    fun serverErrorBodyIsReadable() = runBlocking {
        val c = client(FakeHttp(mutableListOf(LlmHttpResult(400, """{"error":{"message":"Model Not Exist"}}"""))))
        assertNull(c.answer(question))
        assertTrue(c.lastError, c.lastError.contains("Model Not Exist"))
    }

    @Test
    fun unauthorizedIsReadable() = runBlocking {
        val c = client(FakeHttp(mutableListOf(LlmHttpResult(401, """{"error":{"message":"invalid api key"}}"""))))
        assertNull(c.answer(question))
        assertTrue(c.lastError, c.lastError.contains("401"))
    }

    /** 测试连接：正常返回要说通 */
    @Test
    fun probeReportsSuccess() = runBlocking {
        val probe = client(FakeHttp(mutableListOf(ok(response("ok"))))).probe()
        assertTrue(probe.ok)
        assertTrue(probe.detail, probe.detail.contains("finish_reason"))
    }

    @Test
    fun probeReportsTruncationAsFailure() = runBlocking {
        val truncated = JSONObject().apply {
            put("choices", org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", "")).put("finish_reason", "length")
            ))
        }.toString()
        val probe = client(FakeHttp(mutableListOf(ok(truncated)))).probe()
        assertTrue(!probe.ok)
        assertTrue(probe.detail, probe.detail.contains("截断") || probe.detail.contains("推理"))
    }
}
