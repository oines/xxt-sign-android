package com.xxt.sign

import com.xxt.sign.study.ChapterAuditInput
import com.xxt.sign.study.ChapterCheck
import com.xxt.sign.study.CourseAuditCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「这门课还差什么」的判定：全绿 = 没有待完成任务点 */
class CourseAuditTest {

    private fun check(all: Int, allDone: Int, video: Int, videoDone: Int, notOpen: Boolean = false) =
        ChapterCheck(allTasks = all, allDone = allDone, videoTasks = video, videoDone = videoDone, notOpen = notOpen)

    @Test
    fun allGreenWhenNothingLeft() {
        val a = CourseAuditCalculator.audit(
            "c1", "大学体育",
            listOf(
                ChapterAuditInput("第1章", check(3, 3, 2, 2), null),
                ChapterAuditInput("第2章", check(2, 2, 1, 1), null)
            )
        )
        assertTrue(a.allGreen)
        assertEquals(0, a.remaining)
        assertEquals(5, a.taskPoints)
        assertEquals(5, a.doneTaskPoints)
        assertEquals("大学体育：已全绿", a.headline())
    }

    @Test
    fun videoGapCounted() {
        val a = CourseAuditCalculator.audit(
            "c1", "高数",
            listOf(ChapterAuditInput("第1章", check(3, 1, 3, 1), null))
        )
        assertFalse(a.allGreen)
        assertEquals(2, a.remaining)
        assertEquals(1, a.videoPending)
        assertEquals(0, a.quizPending)
    }

    @Test
    fun videoDoneWithPendingQuizCountsAsQuizGap() {
        // 视频全做完，但还有 1 个任务点没做 → 那是章节测验，不能算「全绿」
        val a = CourseAuditCalculator.audit(
            "c1", "高数",
            listOf(ChapterAuditInput("第1章", check(3, 2, 2, 2), quizJobs = 1))
        )
        assertFalse(a.allGreen)
        assertEquals(1, a.remaining)
        assertEquals(0, a.videoPending)
        assertEquals(1, a.quizPending)
        assertEquals(0, a.humanPending)
    }

    @Test
    fun nonQuizTaskPointsAreHumanGaps() {
        // 3 个任务点：2 个视频做完 + 1 个既不是视频也没数到测验 → 得本人处理
        val a = CourseAuditCalculator.audit(
            "c1", "形势与政策",
            listOf(ChapterAuditInput("第1章", check(3, 2, 2, 2), quizJobs = 0))
        )
        assertFalse(a.allGreen)
        assertEquals(1, a.humanPending)
        assertEquals(1, a.gaps.count { it.kind == com.xxt.sign.study.CourseAudit.Kind.HUMAN })
    }

    @Test
    fun closedChapterIsHumanNotFailure() {
        val a = CourseAuditCalculator.audit(
            "c1", "物理",
            listOf(ChapterAuditInput("第9章", check(2, 0, 1, 0, notOpen = true), null))
        )
        assertEquals(0, a.videoPending)
        assertTrue(a.humanPending > 0)
        assertTrue(a.gaps.any { it.kind == com.xxt.sign.study.CourseAudit.Kind.HUMAN })
    }

    @Test
    fun headlineMentionsBreakdown() {
        val a = CourseAuditCalculator.audit(
            "c1", "英语",
            listOf(
                ChapterAuditInput("第1章", check(4, 1, 3, 1), null),
                ChapterAuditInput("第2章", check(3, 2, 1, 1), quizJobs = 1)
            )
        )
        val line = a.headline()
        assertTrue(line.contains("还差"))
        assertTrue(line.contains("视频"))
        assertTrue(line.contains("测验"))
    }
}
