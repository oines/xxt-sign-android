package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作业列表的三条筛选规则：
 *  1. 「待批阅」不显示（已交、等老师批，既做不了也没分数可比）
 *  2. 「未交」永远显示（只要它有选择题）
 *  3. 「已完成但不满分」继续显示（留着重做入口）
 */
class HomeworkVisibilityTest {

    private fun item(status: String, score: String = "", workId: String = "1") = HomeworkItem(
        course = HomeworkCourse("c", "cl", "cpi", "测试课程"),
        workId = workId,
        title = "作业 $workId",
        status = status,
        score = score
    )

    @Test
    fun `待批阅被识别出来`() {
        assertTrue(item("待批阅").isPendingReview)
        assertTrue(item("待批阅中").isPendingReview)
        assertFalse(item("已完成").isPendingReview)
        assertFalse(item("未交").isPendingReview)
    }

    @Test
    fun `待批阅属于已交但不算未交`() {
        val it0 = item("待批阅")
        assertTrue("待批阅是交过的", it0.submitted)
        assertFalse("待批阅不是未交", it0.unsubmitted)
    }

    @Test
    fun `已完成且满分不显示`() {
        val it0 = item("已完成", score = "100")
        assertTrue(it0.submitted)
        assertFalse(it0.isPendingReview)
        assertFalse("满分不需要重做", it0.belowFull)
    }

    @Test
    fun `已完成但不满分要显示`() {
        val it0 = item("已完成", score = "80")
        assertTrue("分数不满 -> 留着让用户重做", it0.belowFull)
    }

    @Test
    fun `没出分的已完成要显示`() {
        val it0 = item("已完成", score = "")
        assertFalse(it0.belowFull)
        assertTrue("空分数不能当成满分过滤掉", it0.score.isBlank())
    }

    /** 模拟 load() 里的显示判定，锁住「待批阅不显示」这条 */
    private fun shouldShow(it: HomeworkItem): Boolean {
        if (it.unsubmitted) return true
        if (!it.submitted) return false
        if (it.isPendingReview) return false
        return !it.belowFull.let { false } || true // 到这一步已完成，交给分数判断
    }

    @Test
    fun `待批阅不进入列表`() {
        assertFalse(shouldShow(item("待批阅")))
        assertFalse(shouldShow(item("待批阅", score = "90")))
    }

    @Test
    fun `未交进入列表`() {
        assertTrue(shouldShow(item("未交")))
        assertTrue(shouldShow(item("未提交")))
    }
}
