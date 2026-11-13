package com.xxt.sign.sign

import com.xxt.sign.net.CxApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「所有选中账号的签到任务是同一个 → 一次性签完」的语义锁。
 *
 * 这条规则直接决定签到码 / 二维码要不要问第二次，所以单独抽成 [SignPlanner.singleTask] 来测。
 */
class SignPlannerSingleTaskTest {

    private fun detected(aid: String, accounts: List<String> = listOf("a", "b")) = SignPlanner.Detected(
        aid = aid,
        courseId = "c1",
        classId = "cl1",
        courseName = "测试课程",
        otherId = CxApi.TYPE_CODE,
        isSignOut = false,
        label = "签到",
        accountIds = accounts,
        accountNames = accounts.map { "账号$it" },
        detail = JSONObject()
    )

    /** 多个账号都在同一个活动里 → detect 只产出一个元素 → 一次签完 */
    @Test
    fun `多个账号同一个活动时是单个任务`() {
        val list = listOf(detected("1001", listOf("a", "b", "c")))
        assertEquals("1001", SignPlanner.singleTask(list)?.aid)
    }

    /** 两个不同活动 → 不是单个任务，必须让用户先选 */
    @Test
    fun `不同活动时不是单个任务`() {
        val list = listOf(detected("1001"), detected("1002"))
        assertNull(SignPlanner.singleTask(list))
    }

    /** 什么都没检测到 → 也不是单个任务 */
    @Test
    fun `空列表不是单个任务`() {
        assertNull(SignPlanner.singleTask(emptyList()))
    }

    /** 只有一个账号、一个活动 → 同样是单个任务 */
    @Test
    fun `单账号单活动`() {
        val list = listOf(detected("1001", listOf("a")))
        assertEquals("1001", SignPlanner.singleTask(list)?.aid)
    }
}
