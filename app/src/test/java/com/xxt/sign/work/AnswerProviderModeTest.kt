package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「答案来源」语义回归测试。
 *
 * 真机上用户报过两条：
 *  1. 「不查题库」选了却不走大模型 —— 实际是**会**走的，但文案没写清它依赖
 *     「用大模型做题」主开关，所以看起来像坏了。
 *  2. 「纯大模型」选项多余 —— 它和「不查题库」行为完全一致（都是 LLM_ONLY），
 *     已从 UI 移除，这里锁住「两者等价」这个事实，避免以后又加回来。
 */
class AnswerProviderModeTest {

    private fun llm(key: String = "sk-test") = LlmClient(apiKey = key)

    private fun provider(value: String, withLlm: Boolean) = AnswerProvider(
        provider = value,
        endpoint = "",
        token = "",
        llm = if (withLlm) llm() else null
    )

    /** 大模型可用时，「不查题库」就是纯大模型模式 */
    @Test
    fun `不查题库在大模型可用时走 LLM_ONLY`() {
        assertEquals(AnswerProvider.Mode.LLM_ONLY, provider("off", true).mode)
        assertTrue(provider("off", true).configured)
    }

    /** 大模型不可用时，「不查题库」就是什么都不查 */
    @Test
    fun `不查题库在大模型不可用时是 OFF`() {
        assertEquals(AnswerProvider.Mode.OFF, provider("off", false).mode)
        assertFalse("OFF 不应该被当成已配置", provider("off", false).configured)
    }

    /** 关键：被删掉的「纯大模型」选项，和「不查题库」行为完全一样 */
    @Test
    fun `llm 与 off 行为等价`() {
        assertEquals(provider("off", true).mode, provider("llm", true).mode)
        assertEquals(provider("off", false).mode, provider("llm", false).mode)
        assertEquals(provider("off", true).configured, provider("llm", true).configured)
        assertEquals(provider("off", false).configured, provider("llm", false).configured)
    }

    /** 题库类选项都是 BANK_FIRST，没命中才问大模型 */
    @Test
    fun `题库选项是 BANK_FIRST`() {
        for (v in listOf("auto", "yanxi", "lemon", "tikuadapter")) {
            assertEquals("provider=$v", AnswerProvider.Mode.BANK_FIRST, provider(v, true).mode)
        }
    }

    /** 大模型没配 key 时 enabled=false，即使选了纯大模型也不该有答案 */
    @Test
    fun `没 key 时大模型不可用`() {
        val p = AnswerProvider(
            provider = "llm",
            endpoint = "",
            token = "",
            llm = LlmClient(apiKey = "")
        )
        assertFalse(p.configured)
    }
}
