package com.xxt.sign.work

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LLM 请求体构造 + 假 HttpClient 下的重试/解析链路（不联网） */
class LlmClientTest {

    private class FakeHttp(private val responses: MutableList<String?>) : LlmHttp {
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
        ): String? {
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

    private fun response(content: String): String = JSONObject().apply {
        put(
            "choices",
            org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content))
            )
        )
    }.toString()

    private fun client(http: LlmHttp, quota: () -> Boolean = { true }) = LlmClient(
        apiKey = "sk-test",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
        http = http,
        timeoutMs = 20_000L,
        quota = quota,
        retryDelayMs = 0
    )

    @Test
    fun buildsRequestBodyWithModelTemperatureAndPrompt() {
        val http = FakeHttp(mutableListOf(response(ans("A"))))
        val c = client(http)
        val body = JSONObject(c.buildBody(question))

        assertEquals("deepseek-chat", body.getString("model"))
        assertEquals(0, body.getInt("temperature"))
        assertTrue(body.getInt("max_tokens") <= 64)

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
        val http = FakeHttp(mutableListOf(response(ans("B"))))
        val r = client(http).answer(question)
        assertEquals("B", r?.value)
        assertTrue(r!!.source.startsWith("LLM"))
        assertEquals(1, http.calls)
    }

    @Test
    fun retriesOnceOnNetworkFailure() = runBlocking {
        val http = FakeHttp(mutableListOf(null, response(ans("A"))))
        val c = client(http)
        assertEquals("A", c.answer(question)?.value)
        assertEquals(2, http.calls)
    }

    @Test
    fun givesUpAfterOneRetry() = runBlocking {
        val http = FakeHttp(mutableListOf(null, null, response(ans("A"))))
        val c = client(http)
        assertNull(c.answer(question))
        assertEquals(2, http.calls)
        assertTrue(c.lastError.contains("网络"))
    }

    @Test
    fun invalidFormatIsNotAdoptedAndNotRetried() = runBlocking {
        val http = FakeHttp(mutableListOf(response(ans("Z"))))
        val c = client(http)
        assertNull(c.answer(question))
        assertEquals(1, http.calls)
        assertTrue(c.lastError.contains("字母"))
    }

    @Test
    fun dailyQuotaStopsCalls() = runBlocking {
        val http = FakeHttp(mutableListOf(response(ans("A"))))
        val c = client(http) { false }
        assertNull(c.answer(question))
        assertEquals(0, http.calls)
        assertTrue(c.lastError.contains("上限"))
    }

    @Test
    fun paperBudgetIsCappedByQuestionCount() = runBlocking {
        val http = FakeHttp(mutableListOf(response(ans("A")), response(ans("A"))))
        val c = client(http)
        c.beginPaper(1)
        assertEquals("A", c.answer(question)?.value)
        assertNull(c.answer(question))
        assertEquals(1, http.calls)
    }
}
