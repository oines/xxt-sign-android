package com.xxt.sign

import com.xxt.sign.study.ChapterAuditInput
import com.xxt.sign.study.ChapterRow
import com.xxt.sign.study.CardTask
import com.xxt.sign.study.CourseAuditCalculator
import com.xxt.sign.study.summarizeChapter
import com.xxt.sign.study.stateByTaskPoints
import com.xxt.sign.study.withPlatformTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这门课还差什么」的判定。
 *
 * 判据全部来自真实回包：任务点只有 video / document / workid 三种，
 * workid 的 `property.worktype == "workA"` 是随堂测验（能自动做），
 * `property.module == "work"`。标题不作数（随堂测试 / 章节测试 / 考核通关都有）。
 */
class CourseAuditTest {

    private fun video(jobId: String, passed: Boolean) =
        CardTask(key = jobId, type = "video", passed = passed)

    private fun document(jobId: String, passed: Boolean, jtoken: String = "jt-$jobId") =
        CardTask(key = jobId, type = "document", passed = passed, jtoken = jtoken, knowledgeId = "k-$jobId")

    private fun quiz(jobId: String, passed: Boolean = false, worktype: String = "workA") =
        CardTask(
            key = jobId, type = "workid", passed = passed,
            module = "work", worktype = worktype, name = "随堂测验"
        )

    private fun input(title: String, vararg tasks: CardTask) =
        ChapterAuditInput(title, summarizeChapter(tasks.toList()))

    // ------------------------------------------------ 类型判据（故障 C 的核心）

    @Test
    fun workAIsQuizNotJob() {
        val check = summarizeChapter(listOf(quiz("work-1")))
        assertEquals(1, check.quizTasks)
        assertEquals(0, check.humanLeft)
        assertTrue(check.pendingDocuments.isEmpty())
    }

    @Test
    fun workANotJudgedByTitle() {
        // 真实数据里 workA 的标题五花八门，甚至有直接写章名的
        val a = CardTask(
            key = "work-x", type = "workid", passed = false,
            module = "work", worktype = "workA", name = "太极拳的起源"
        )
        assertEquals(1, summarizeChapter(listOf(a)).quizTasks)
    }

    @Test
    fun otherWorktypeIsHumanAndKeepsRawValue() {
        // 本账号没有 workB，遇到其它 worktype 只记录原始值，不猜成作业
        val check = summarizeChapter(listOf(quiz("work-2", worktype = "workB")))
        assertEquals(0, check.quizTasks)
        assertEquals(1, check.humanLeft)
        assertEquals(mapOf("workB" to 1), check.otherWorks)
    }

    @Test
    fun documentsAreAutoNotHuman() {
        val check = summarizeChapter(listOf(document("d1", passed = false)))
        assertEquals(1, check.documentLeft)
        assertEquals(0, check.humanLeft)
        assertEquals(1, check.pendingDocuments.size)
        assertEquals("k-d1", check.pendingDocuments[0].knowledgeId)
    }

    @Test
    fun audioIsHumanForNow() {
        val check = summarizeChapter(listOf(CardTask(key = "a1", type = "audio", passed = false)))
        assertEquals(1, check.audioLeft)
        assertEquals(1, check.humanLeft)
    }

    // ------------------------------------------------ 整门课口径

    @Test
    fun allGreenWhenNothingLeft() {
        val a = CourseAuditCalculator.audit(
            "c1", "大学体育",
            listOf(
                input("第1章", video("v1", true), video("v2", true), document("d1", true)),
                input("第2章", video("v3", true), quiz("work-1", passed = true))
            )
        )
        assertTrue(a.allGreen)
        assertEquals(0, a.remaining)
        assertEquals(5, a.taskPoints)
        assertEquals(5, a.doneTaskPoints)
        assertEquals("大学体育：已全绿", a.headline())
    }

    @Test
    fun videoGapCountedAsPoints() {
        val a = CourseAuditCalculator.audit(
            "c1", "高数",
            listOf(input("第1章", video("v1", true), video("v2", false), video("v3", false)))
        )
        assertFalse(a.allGreen)
        assertEquals(2, a.remaining)
        assertEquals(2, a.videoPending)
    }

    @Test
    fun pendingQuizIsGapNotHuman() {
        // 体育课 2.1 章：视频做完 + 1 个 workA 测验 → 缺口是测验，不是「作业要你自己做」
        val a = CourseAuditCalculator.audit(
            "c1", "大学体育与健康",
            listOf(input("2.1 什么是健康?", video("v1", true), quiz("work-5cea97cf")))
        )
        assertFalse(a.allGreen)
        assertEquals(1, a.remaining)
        assertEquals(1, a.quizPending)
        assertEquals(0, a.humanPending)
        assertEquals(0, a.gaps.count { it.kind == com.xxt.sign.study.CourseAudit.Kind.HUMAN })
        assertTrue(a.headline().contains("测验 1"))
    }

    @Test
    fun documentGapIsAutoNotHuman() {
        val a = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(input("第1章", document("d1", false), document("d2", false)))
        )
        assertEquals(2, a.documentPending)
        assertEquals(0, a.humanPending)
        assertEquals(2, a.autoRemaining)
    }

    @Test
    fun closedChapterIsHumanNotFailure() {
        val a = CourseAuditCalculator.audit(
            "c1", "物理",
            listOf(ChapterAuditInput("第9章", com.xxt.sign.study.ChapterCheck(2, 0, 1, 0, notOpen = true)))
        )
        assertEquals(0, a.videoPending)
        assertTrue(a.humanPending > 0)
        assertEquals(ChapterRow.State.LOCKED, a.chapterRows[0].state)
    }

    @Test
    fun iconFinishedChapterCountsAsDoneWithoutScan() {
        // 体检器对 icon_yiwanc 的章节直接给 allTasks == allDone 的 check
        val a = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(ChapterAuditInput("1.1 导论", com.xxt.sign.study.ChapterCheck(4, 4, 2, 2, fromIcon = true)))
        )
        assertTrue(a.allGreen)
        assertEquals(4, a.taskPoints)
        assertEquals(ChapterRow.State.DONE, a.chapterRows[0].state)
    }

    @Test
    fun chapterRowsDescribeEachChapter() {
        val a = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(
                input("1.1 导论", document("d1", true)),
                input("2.1 什么是健康?", video("v1", false), quiz("work-1")),
                ChapterAuditInput("3.1 未开", null)
            )
        )
        assertEquals(3, a.chapterRows.size)
        assertEquals(ChapterRow.State.DONE, a.chapterRows[0].state)
        assertEquals(ChapterRow.State.TODO, a.chapterRows[1].state)
        assertEquals(ChapterRow.State.UNCHECKED, a.chapterRows[2].state)
        assertEquals(2, a.chapterRows[1].total)
        assertEquals(0, a.chapterRows[1].done)
    }

    @Test
    fun partialChapterIsMarkedPartialNotTodo() {
        val a = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(input("4.4.1 篮球", video("v1", true), video("v2", false)))
        )
        val row = a.chapterRows.single()
        assertEquals(ChapterRow.State.PARTIAL, row.state)
        assertEquals(2, row.total)
        assertEquals(1, row.done)
        assertEquals(1, row.videoDone)
        assertEquals(2, row.videoTasks)
    }

    /** 扫描失败的章：进度未知，不能当「未开始」，也不虚增任务点分母 */
    @Test
    fun scanFailedChapterIsUncheckedAndNotCounted() {
        val a = CourseAuditCalculator.audit(
            "c1", "体育",
            listOf(
                input("1.1 导论", document("d1", true)),
                ChapterAuditInput("2.1 没解析到", com.xxt.sign.study.ChapterCheck(1, 0, 1, 0, scanFailed = true))
            )
        )
        assertEquals(ChapterRow.State.UNCHECKED, a.chapterRows[1].state)
        assertEquals(1, a.taskPoints)
        assertEquals(2, a.chapterRows.size)
    }

    /** 旧版本存下的状态字符串（图标口径）不能污染界面：渲染时按 done/total 现算 */
    @Test
    fun rowStateIsRecomputedFromCounts() {
        fun row(done: Int, total: Int, state: ChapterRow.State) =
            ChapterRow("x", total, done, state)

        // 旧缓存里写的是 TODO，但任务点已经全完成 → 已完成
        assertEquals(ChapterRow.State.DONE, row(4, 4, ChapterRow.State.TODO).stateByTaskPoints())
        // 做了一部分 → 部分完成
        assertEquals(ChapterRow.State.PARTIAL, row(1, 4, ChapterRow.State.TODO).stateByTaskPoints())
        // 一个没做 → 未开始
        assertEquals(ChapterRow.State.TODO, row(0, 4, ChapterRow.State.UNCHECKED).stateByTaskPoints())
        // 没有任务点明细 → 没查到，不编「未开始」
        assertEquals(ChapterRow.State.UNCHECKED, row(0, 0, ChapterRow.State.TODO).stateByTaskPoints())
        // 未开放原样保留
        assertEquals(ChapterRow.State.LOCKED, row(0, 0, ChapterRow.State.LOCKED).stateByTaskPoints())
    }

    @Test
    fun headlineMentionsTaskPointsNotChapters() {
        val a = CourseAuditCalculator.audit(
            "c1", "英语",
            listOf(input("第1章", video("v1", false), video("v2", false), quiz("work-1")))
        )
        val line = a.headline()
        assertTrue(line.contains("还差 3 个任务点"))
        assertTrue(line.contains("视频 2"))
        assertTrue(line.contains("测验 1"))
        assertFalse("章节数不该混进任务点口径", line.contains("章"))
    }

    // ------------------------------------------------ 分母口径（392）

    @Test
    fun platformTotalsWinOverSelfCount() {
        // 图标直接判完成的章只有 knowledgeJobCount，自数会偏小（真机 304 vs 平台 392）
        val self = CourseAuditCalculator.audit(
            "c1", "大学体育与健康",
            listOf(input("2.1 什么是健康?", video("v1", true), quiz("work-1")))
        )
        assertEquals(2, self.taskPoints)

        val merged = self.withPlatformTotals(done = 11, total = 392)
        assertEquals(392, merged.taskPoints)
        assertEquals(11, merged.doneTaskPoints)
        assertEquals(381, merged.remaining)
        assertFalse(merged.allGreen)
    }

    @Test
    fun missingPlatformTotalsKeepsSelfCount() {
        val self = CourseAuditCalculator.audit(
            "c1", "体育", listOf(input("1.1 导论", video("v1", true)))
        )
        val merged = self.withPlatformTotals(done = -1, total = -1)
        assertEquals(self.taskPoints, merged.taskPoints)
        assertEquals(self.doneTaskPoints, merged.doneTaskPoints)
    }
}
