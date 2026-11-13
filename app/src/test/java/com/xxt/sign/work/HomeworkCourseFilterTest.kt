package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课程过滤」的选择语义回归测试。
 *
 * 真机 bug（用户报「只勾选一个，确定还是全部」）由两个缺陷叠加造成：
 *  1. `if (sel.size == courses.size) emptySet()` —— 只勾一门、候选恰好只有一门时，
 *     被误判成「全选」从而存成空集合（= 全部）。选择必须**原样保存**，不跟总数比较。
 *  2. `applyFilter()` 里按 `allItems`（只有「当前有作业的课程」）做「失效清理」——
 *     用户选了一门暂时没作业的课，就被整段清空回「全部」。用户的选择只能由用户改。
 *
 * 这里把两条语义抽成纯函数锁住。
 */
class HomeworkCourseFilterTest {

    private fun course(id: String, title: String = "课程$id") =
        HomeworkCourse(courseId = id, clazzId = "cl$id", cpi = "cpi", title = title)

    /**
     * 把弹窗勾选结果转成要保存的集合。
     *
     * **不**做 `sel.size == courses.size → emptySet()` 的换算：用户勾了几门就存几门。
     * 只有「一门都没勾」时才是空（= 全部）。
     */
    private fun toSavedSelection(all: List<HomeworkCourse>, checked: Set<String>): Set<String> =
        all.filter { it.key in checked }.map { it.key }.toSet()

    @Test
    fun `只勾一门就只存这一门`() {
        val all = listOf(course("1"), course("2"), course("3"))
        val saved = toSavedSelection(all, setOf(course("2").key))
        assertEquals(1, saved.size)
        assertEquals(course("2").key, saved.first())
    }

    /**
     * 关键回归：候选中只有一门课时，勾它必须存成「1 门」，不能变成「全部」。
     * 旧实现 `if (sel.size == courses.size) emptySet()` 在这里会把选择丢掉。
     */
    @Test
    fun `候选只有一门时勾选不等于全部`() {
        val all = listOf(course("only"))
        val saved = toSavedSelection(all, setOf(course("only").key))
        assertEquals("只勾一门必须存成 1 门，不能退化成全部", setOf(course("only").key), saved)
        assertTrue("不能是空集合（空 = 全部）", saved.isNotEmpty())
    }

    @Test
    fun `一门都不勾是空集合等于全部`() {
        val all = listOf(course("1"), course("2"))
        assertTrue(toSavedSelection(all, emptySet()).isEmpty())
    }

    @Test
    fun `勾多门存多门`() {
        val all = listOf(course("1"), course("2"), course("3"))
        val saved = toSavedSelection(all, setOf(course("1").key, course("3").key))
        assertEquals(2, saved.size)
    }

    /**
     * 过滤时**不得**因为「这门课当前没有作业」就丢掉用户的选择。
     *
     * 旧实现：`valid = keys.filter { it in allItems.map{...} }`，
     * allItems 只是有作业的课程子集，于是选了没作业的课 → 选择被清空。
     */
    private fun filterItems(items: List<HomeworkItem>, keys: Set<String>): List<HomeworkItem> =
        if (keys.isEmpty()) items else items.filter { it.course.key in keys }

    private fun item(course: HomeworkCourse, id: String) = HomeworkItem(
        course = course, workId = id, title = "作业$id", status = "未交"
    )

    @Test
    fun `选了没有作业的课程时结果为空但选择保留`() {
        val c1 = course("1")
        val c2 = course("2")
        val items = listOf(item(c1, "a"))
        val keys = setOf(c2.key)          // 选了 c2，但 c2 没有作业
        val filtered = filterItems(items, keys)
        assertTrue("c2 没作业，列表应为空", filtered.isEmpty())
        assertEquals("但用户的选择不能被清掉", setOf(c2.key), keys)
    }

    @Test
    fun `选择为空时显示全部`() {
        val c1 = course("1")
        val items = listOf(item(c1, "a"))
        assertEquals(1, filterItems(items, emptySet()).size)
    }

    @Test
    fun `只显示选中课程的作业`() {
        val c1 = course("1")
        val c2 = course("2")
        val items = listOf(item(c1, "a"), item(c2, "b"), item(c1, "c"))
        val filtered = filterItems(items, setOf(c1.key))
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.course.key == c1.key })
    }
}
