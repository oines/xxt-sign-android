package com.xxt.sign.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选择账号」的过滤语义。
 *
 * 这段逻辑决定「立即签到」到底拉谁的签到任务，写错了会出现
 * 「只选了 2 个账号却把全公司的账号都签了」这种事故，所以单独测。
 */
class SignAccountSelectionTest {

    private fun acc(id: String, name: String) =
        Account(id = id, phone = "13800000000", name = name)

    private val all = listOf(
        acc("a", "张三"),
        acc("b", "李四"),
        acc("c", "王五")
    )

    /** 选中集合为空 = 全部账号（升级后老用户不掉签到的保护） */
    @Test
    fun `空选择集合表示全部账号`() {
        assertEquals(3, filterSelected(all, emptySet()).size)
    }

    /** 只选了两个 → 只处理这两个 */
    @Test
    fun `只处理选中的账号`() {
        val picked = filterSelected(all, setOf("a", "c"))
        assertEquals(2, picked.size)
        assertEquals(listOf("张三", "王五"), picked.map { it.name })
    }

    /** 选中的账号被删了 → 退回全部，不能让按钮变成「什么都没发生」 */
    @Test
    fun `选中的账号全被删除时退回全部`() {
        val picked = filterSelected(all, setOf("gone1", "gone2"))
        assertEquals(3, picked.size)
    }

    /** 部分失效 → 只保留还存在的 */
    @Test
    fun `部分账号被删除时只保留有效的`() {
        val picked = filterSelected(all, setOf("a", "gone"))
        assertEquals(1, picked.size)
        assertEquals("张三", picked.first().name)
    }

    /** 全选（选了所有 id）= 和全部等价 */
    @Test
    fun `全选等价于全部`() {
        val picked = filterSelected(all, setOf("a", "b", "c"))
        assertEquals(3, picked.size)
        assertTrue(picked.map { it.id }.containsAll(listOf("a", "b", "c")))
    }

    private fun filterSelected(all: List<Account>, ids: Set<String>): List<Account> {
        if (ids.isEmpty()) return all
        val picked = all.filter { it.id in ids }
        return picked.ifEmpty { all }
    }
}
