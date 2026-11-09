package com.xxt.sign.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体检结果落盘/读取的格式：真机出现过「存的是 v0，读端以为旧数据，整屏没查到」。
 * 存一轮 → 读回来必须带当前口径版本，行明细（done/total、视频 x/y）不能丢。
 */
class CourseAuditJsonTest {

    private val audit = CourseAudit(
        courseId = "248139270",
        courseTitle = "大学体育与健康",
        taskPoints = 392,
        doneTaskPoints = 210,
        videoPending = 147,
        quizPending = 59,
        chapters = 91,
        checkedChapters = 56,
        chapterRows = listOf(
            ChapterRow("1.1 导论", total = 1, done = 1, state = ChapterRow.State.DONE),
            ChapterRow(
                "4.4.1 运球技术", total = 4, done = 1, state = ChapterRow.State.PARTIAL,
                videoDone = 1, videoTasks = 1
            ),
            ChapterRow("5.1.1 起跑", total = 0, done = 0, state = ChapterRow.State.UNCHECKED)
        )
    )

    @Test
    fun saveWritesCurrentRowsVersion() {
        val back = CourseAuditJson.decode(CourseAuditJson.encode(listOf(audit))).single()
        assertEquals(CourseAudit.ROWS_VERSION, back.rowsVersion)
        assertEquals(2, back.rowsVersion)
    }

    @Test
    fun roundTripKeepsRowsAndDetail() {
        val back = CourseAuditJson.decode(CourseAuditJson.encode(listOf(audit))).single()
        assertEquals("248139270", back.courseId)
        assertEquals(392, back.taskPoints)
        assertEquals(210, back.doneTaskPoints)
        assertEquals(3, back.chapterRows.size)

        val done = back.chapterRows[0]
        assertEquals(1, done.total)
        assertEquals(1, done.done)
        assertEquals(ChapterRow.State.DONE, done.stateByTaskPoints())

        val partial = back.chapterRows[1]
        assertEquals(4, partial.total)
        assertEquals(1, partial.done)
        assertEquals(1, partial.videoDone)
        assertEquals(1, partial.videoTasks)
        assertEquals(ChapterRow.State.PARTIAL, partial.stateByTaskPoints())

        // 没有明细的行：读回来是 0/0，渲染时会变成「没查到」
        assertEquals(ChapterRow.State.UNCHECKED, back.chapterRows[2].stateByTaskPoints())
    }

    /** 老版本存的数据没有 rowsVersion，但只要有 done/total 明细，仍能按任务点算出状态 */
    @Test
    fun legacyJsonWithoutVersionStillHasUsableDetail() {
        val legacy = """
            [{"courseId":"1","courseTitle":"旧课","taskPoints":10,"doneTaskPoints":6,
              "rows":[
                {"t":"第1章","d":3,"n":3,"s":"TODO"},
                {"t":"第2章","d":1,"n":3,"s":"TODO"},
                {"t":"第3章","d":0,"n":0,"s":"TODO"}
              ]}]
        """.trimIndent()
        val back = CourseAuditJson.decode(legacy).single()
        assertEquals(0, back.rowsVersion)
        assertTrue(back.chapterRows.any { it.total > 0 })
        assertEquals(ChapterRow.State.DONE, back.chapterRows[0].stateByTaskPoints())
        assertEquals(ChapterRow.State.PARTIAL, back.chapterRows[1].stateByTaskPoints())
        assertEquals(ChapterRow.State.UNCHECKED, back.chapterRows[2].stateByTaskPoints())
    }

    @Test
    fun blankOrBrokenJsonIsEmptyNotCrash() {
        assertTrue(CourseAuditJson.decode("").isEmpty())
        assertTrue(CourseAuditJson.decode("not json").isEmpty())
        assertNotNull(CourseAuditJson.decode("[]"))
    }
}
