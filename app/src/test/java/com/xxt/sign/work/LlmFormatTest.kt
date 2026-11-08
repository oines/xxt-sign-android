package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LLM 回包的格式校验：任何一条不满足都必须留空，不能猜 */
class LlmFormatTest {

    private fun q(type: Int, vararg keys: String) = WorkQuestion(
        qid = "1",
        type = type,
        stem = "题干",
        options = keys.map { WorkOption(it, "选项$it") }
    )

    private fun check(content: String, question: WorkQuestion) =
        LlmFormat.check(content, question)

    private fun ans(v: String) = "{\"answer\":\"$v\"}"

    @Test
    fun singleAcceptsOneLetterInOptions() {
        val r = check("{\"answer\":\"B\"}", q(WorkQuestion.TYPE_SINGLE, "A", "B", "C"))
        assertTrue(r.ok)
        assertEquals("B", r.value)
    }

    @Test
    fun singleRejectsUnknownLetterAndMultipleLetters() {
        val question = q(WorkQuestion.TYPE_SINGLE, "A", "B")
        assertFalse(check("{\"answer\":\"Z\"}", question).ok)
        assertFalse(check("{\"answer\":\"AB\"}", question).ok)
        assertFalse(check("{\"answer\":\"A或B\"}", question).ok)
    }

    @Test
    fun multiSortsDedupesAndChecksSubset() {
        val question = q(WorkQuestion.TYPE_MULTI, "A", "B", "C", "D")
        val ok = check("{\"answer\":\"CAB\"}", question)
        assertTrue(ok.ok)
        assertEquals("ABC", ok.value)

        assertEquals("ABD", check("{\"answer\":\"d,a,b\"}", question).value)
        assertFalse(check("{\"answer\":\"E\"}", question).ok)
        assertFalse(check("{\"answer\":\"\"}", question).ok)
    }

    @Test
    fun judgeOnlyAcceptsTrueOrFalse() {
        val question = q(WorkQuestion.TYPE_JUDGE, "true", "false")
        assertEquals("true", check("{\"answer\":\"true\"}", question).value)
        assertEquals("false", check("{\"answer\":\"False\"}", question).value)
        assertFalse(check("{\"answer\":\"正确\"}", question).ok)
        assertFalse(check("{\"answer\":\"对\"}", question).ok)
    }

    @Test
    fun blankAcceptsTextButRejectsRefusals() {
        val question = q(WorkQuestion.TYPE_BLANK)
        assertEquals("光合作用", check("{\"answer\":\"光合作用\"}", question).value)
        assertFalse(check("{\"answer\":\"无法确定\"}", question).ok)
        assertFalse(check("{\"answer\":\"不知道\"}", question).ok)
        assertFalse(check("{\"answer\":\"\"}", question).ok)
        assertFalse(check("{\"answer\":\"${"长".repeat(201)}\"}", question).ok)
    }

    @Test
    fun toleratesMarkdownFenceAndNoise() {
        val question = q(WorkQuestion.TYPE_SINGLE, "A", "B")
        val fenced = "好的，答案如下：\n```json\n{\"answer\": \"A\"}\n```"
        assertEquals("A", LlmFormat.extractAnswer(fenced))
        assertTrue(check(fenced, question).ok)
    }

    @Test
    fun rejectsNonJson() {
        val question = q(WorkQuestion.TYPE_SINGLE, "A", "B")
        assertNull(LlmFormat.extractAnswer("我觉得选 A"))
        assertFalse(check("我觉得选 A", question).ok)
    }

    /** 三种 provider 模式的映射不能搞反 */
    @Test
    fun providerModes() {
        fun mode(p: String) = AnswerProvider(p, "", "").mode
        assertEquals(AnswerProvider.Mode.BANK_FIRST, mode("auto"))
        assertEquals(AnswerProvider.Mode.BANK_FIRST, mode("yanxi"))
        assertEquals(AnswerProvider.Mode.BANK_FIRST, mode("tikuadapter"))
        assertEquals(AnswerProvider.Mode.LLM_ONLY, mode("llm"))
        assertEquals(AnswerProvider.Mode.LLM_ONLY, mode("off+llm"))
        assertEquals(AnswerProvider.Mode.OFF, mode("off"))
        assertEquals(AnswerProvider.Mode.OFF, mode(""))
    }
}
