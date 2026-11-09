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

    // ---------------- extractAnswer 四种输入（真机修完 ICU 正则那波补的） ----------------

    /** ```json 包裹：各种围栏写法都得能抠出答案 */
    @Test
    fun extractAnswerHandlesFencedJson() {
        assertEquals("A", LlmFormat.extractAnswer("```json\n{\"answer\": \"A\"}\n```"))
        assertEquals("B", LlmFormat.extractAnswer("```JSON\n{\"answer\":\"B\"}\n```"))
        assertEquals("C", LlmFormat.extractAnswer("```\n{\"answer\":\"C\"}\n```"))
        // 围栏不成对也得能拿：只开了没关
        assertEquals("A", LlmFormat.extractAnswer("```json\n{\"answer\":\"A\"}"))
    }

    /** 前后带废话（日常模型行为） */
    @Test
    fun extractAnswerIgnoresSurroundingChatter() {
        assertEquals(
            "A",
            LlmFormat.extractAnswer("好的，答案如下：\n```json\n{\"answer\": \"A\"}\n```\n希望有帮助！")
        )
        assertEquals("B", LlmFormat.extractAnswer("分析：这题明显是 B。{\"answer\":\"B\"} 以上。"))
        assertEquals("D", LlmFormat.extractAnswer("   \n {\"answer\":\"D\"}  \n  "))
        assertEquals("A", LlmFormat.extractAnswer("前缀废话 {\"answer\":\"A\"} 后缀废话 {不是json}"))
    }

    /** 裸 JSON：没围栏、没废话 */
    @Test
    fun extractAnswerAcceptsBareJson() {
        assertEquals("A", LlmFormat.extractAnswer("{\"answer\":\"A\"}"))
        assertEquals("true", LlmFormat.extractAnswer("{\"answer\":true}"))
        assertEquals("ABC", LlmFormat.extractAnswer("{\"answer\":[\"A\",\"B\",\"C\"]}"))
    }

    /** 无 JSON：一律 null，绝不能瞎猜成 A */
    @Test
    fun extractAnswerReturnsNullWhenNoJson() {
        assertNull(LlmFormat.extractAnswer("我觉得选 A"))
        assertNull(LlmFormat.extractAnswer(""))
        assertNull(LlmFormat.extractAnswer("```json\n我觉得选 A\n```"))
        assertNull(LlmFormat.extractAnswer("{}"))
        assertNull(LlmFormat.extractAnswer("{\"answer\":\"\"}"))
        assertNull(LlmFormat.extractAnswer("{\"answer\":\"   \"}"))
        assertFalse(check("我觉得选 A", q(WorkQuestion.TYPE_SINGLE, "A", "B")).ok)
    }

    /** 畸形输入不抛异常（正则那版真机上直接炸了，这组输入就是回归用例） */
    @Test
    fun malformedInputNeverThrows() {
        val question = q(WorkQuestion.TYPE_SINGLE, "A", "B")
        val inputs = listOf(
            "", " ", "{", "}", "{}", "}{", "```", "``````", "```json",
            "{\"answer\"", "{\"answer\":", "{\"answer\":}", "{'answer':'A'}",
            "{\"answer\":null}", "{\"answer\":{\"x\":1}}", "{\"answer\":[",
            "[1,2,3]", "{{{}}}", "{\"answer\":\"A\"", "answer:A", "\u0000{}",
            "{\"answer\":\"\\u0041\"}", "```\n```", "\n\n\n"
        )
        inputs.forEach { raw ->
            val got = runCatching { LlmFormat.extractAnswer(raw) }
            assertTrue("extractAnswer(\"$raw\") 抛了 ${got.exceptionOrNull()}", got.isSuccess)
            val checked = runCatching { LlmFormat.check(raw, question) }
            assertTrue("check(\"$raw\") 抛了 ${checked.exceptionOrNull()}", checked.isSuccess)
            assertTrue("check(\"$raw\") 没结果", checked.getOrNull() != null)
        }

        // 超长垃圾也不能抛
        val junk = "x".repeat(200_000) + "{" + "y".repeat(50_000)
        assertTrue(runCatching { LlmFormat.extractAnswer(junk) }.isSuccess)
        assertNull(LlmFormat.extractAnswer(junk))
    }

    /**
     * 硬约束：解析器不许再依赖 java.util.regex。
     *
     * 真机（Pixel 5 / Android 14）上 LlmFormat 的静态初始化里抠 JSON 对象那条 Regex 被 ICU 抛了
     * PatternSyntaxException（JVM 上不抛，所以单测/编译都发现不了），抛出 ExceptionInInitializerError
     * 后类永久不可用，整卷 LLM 全挂。类初始化错误在 JVM 复现不了，那就用反射直接钉死
     * 「LlmFormat / LlmClient 里不允许出现任何 Regex」。
     */
    @Test
    fun parserHasNoRegexDependency() {
        listOf(LlmFormat::class.java, LlmClient::class.java).forEach { clazz ->
            clazz.declaredFields.forEach { f ->
                assertFalse(
                    "${clazz.simpleName}.${f.name} 不该是 Regex",
                    Regex::class.java.isAssignableFrom(f.type)
                )
            }
            clazz.declaredMethods.forEach { m ->
                assertFalse(
                    "${clazz.simpleName}.${m.name} 不该返回 Regex",
                    Regex::class.java.isAssignableFrom(m.returnType)
                )
                m.parameterTypes.forEach { p ->
                    assertFalse(
                        "${clazz.simpleName}.${m.name} 不该收 Regex 参数",
                        Regex::class.java.isAssignableFrom(p)
                    )
                }
            }
        }
    }

    /** 围栏 / JSON 切片的底层小函数，单独把边界定下来 */
    @Test
    fun fenceAndJsonSliceHelpers() {
        assertEquals("json\n{\"answer\":\"A\"}", LlmFormat.stripFence("```json\n{\"answer\":\"A\"}\n```"))
        assertEquals("{\"answer\":\"A\"}", LlmFormat.pickJsonObject("废话 {\"answer\":\"A\"} 废话"))
        assertNull(LlmFormat.pickJsonObject("没有花括号"))
        assertNull(LlmFormat.pickJsonObject("}{ 顺序反了"))
        assertTrue(LlmFormat.hasAnswerKey("{\"answer\":\"A\"}"))
        assertTrue(LlmFormat.hasAnswerKey("想到这儿 {  \n\"answer\" : \"B\" }"))
        assertFalse(LlmFormat.hasAnswerKey("{\"ans\":\"A\"}"))
        assertFalse(LlmFormat.hasAnswerKey("没有 JSON"))
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
