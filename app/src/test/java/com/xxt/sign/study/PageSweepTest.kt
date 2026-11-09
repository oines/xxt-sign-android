package com.xxt.sign.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页退出条件。
 *
 * 真机故障（文档阶段卡 4 分钟零日志）的根因：老代码用「这一页解析出 0 个任务点」当退出条件，
 * 而服务端对越界的 num 会反复返回同一页 —— 永远不空，于是一门课每章都翻满 61 页。
 */
class PageSweepTest {

    @Test
    fun stopsWhenServerRepeatsSamePage() {
        val sweep = PageSweep()
        assertTrue("第一页有新任务点，继续", sweep.afterNew(3))
        assertTrue("重复页第一次，再给它一页机会", sweep.afterNew(0))
        assertFalse("连续两页没有新东西就停", sweep.afterNew(0))
        assertEquals(3, sweep.pages)
    }

    @Test
    fun keepsGoingWhileNewItemsAppear() {
        val sweep = PageSweep()
        repeat(8) { assertTrue(sweep.afterNew(1)) }
        assertEquals(8, sweep.pages)
    }

    @Test
    fun hardLimitCapsRunawayPaging() {
        val sweep = PageSweep(maxPages = 5)
        repeat(5) { sweep.afterNew(1) }
        assertTrue(sweep.hitLimit)
        assertFalse("到上限就不翻了", sweep.afterNew(1))
    }
}
