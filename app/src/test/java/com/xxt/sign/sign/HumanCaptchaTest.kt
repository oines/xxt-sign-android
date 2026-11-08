package com.xxt.sign.sign

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Collections

/**
 * 人工滑块串行闸门。
 *
 * 场景：A、B 两个账号都要滑，页面同时只弹一个；A 滑完（或取消/超时）之后才轮到 B，
 * 而且每个账号拿到的是自己那一次的 validate。
 */
class HumanCaptchaTest {

    private fun prompt(name: String) = SignFlow.CaptchaPrompt(
        accountId = name,
        accountName = name,
        activityLabel = "高数 · 二维码签到",
        aid = "1",
        reason = "服务端要求人机验证"
    )

    @Test
    fun promptTitleNamesAccountAndActivity() {
        assertEquals("张三 · 高数 · 二维码签到", prompt("张三").title)
    }

    /** 两段弹窗不能重叠：B 必须等 A 结束 */
    @Test
    fun promptsAreSerialized() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())

        val a = async {
            HumanCaptcha.ask(prompt("A")) {
                events.add("A-start")
                delay(120)
                events.add("A-end")
                "validate-A"
            }
        }
        val b = async {
            // 稍晚一点发起，模拟第二个账号在 A 还在滑的时候也要滑
            delay(20)
            HumanCaptcha.ask(prompt("B")) {
                events.add("B-start")
                delay(20)
                events.add("B-end")
                "validate-B"
            }
        }

        assertEquals("validate-A", a.await())
        assertEquals("validate-B", b.await())
        assertEquals(
            listOf("A-start", "A-end", "B-start", "B-end"),
            events.toList()
        )
    }

    /** 用户取消（返回 null）只结束自己，不能把后面的账号卡死 */
    @Test
    fun cancelledPromptDoesNotBlockNextAccount() = runBlocking {
        assertNull(HumanCaptcha.ask(prompt("A")) { null })
        assertEquals("validate-B", HumanCaptcha.ask(prompt("B")) { "validate-B" })
    }
}
