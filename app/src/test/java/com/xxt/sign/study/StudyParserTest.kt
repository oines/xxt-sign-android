package com.xxt.sign.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyParserTest {

    @Test
    fun parseCourses_readsIdsCpiAndSkipsClosed() {
        val html = """
            <div class="course" id="course_1">
              <input class="clazzId" value="111">
              <input class="courseId" value="222">
              <a href="https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=222&clazzid=111&cpi=333&ut=s">
                <span class="course-name" title="高等数学"></span>
              </a>
              <p class="color3" title="张老师"></p>
            </div>
            <div class="course">
              <a class="not-open-tip">未开放</a>
              <input class="clazzId" value="999">
              <input class="courseId" value="888">
            </div>
        """.trimIndent()

        val courses = StudyParser.parseCourses(html)
        assertEquals(1, courses.size)
        assertEquals("222", courses[0].courseId)
        assertEquals("111", courses[0].clazzId)
        assertEquals("333", courses[0].cpi)
        assertEquals("高等数学", courses[0].title)
        assertEquals("张老师", courses[0].teacher)
        assertEquals("222:111", courses[0].key)
    }

    @Test
    fun parseChapters_readsFinishedAndUnlockFlags() {
        val html = """
            <div class="chapter_unit">
              <ul>
                <li>
                  <div id="cur123">
                    <a class="clicktitle">第一章 绪论</a>
                    <input class="knowledgeJobCount" value="2">
                    <span class="bntHoverTips">已完成</span>
                  </div>
                </li>
                <li>
                  <div id="cur124">
                    <a class="clicktitle">第二章 极限</a>
                    <span class="bntHoverTips">需解锁</span>
                  </div>
                </li>
              </ul>
            </div>
        """.trimIndent()

        val chapters = StudyParser.parseChapters(html)
        assertEquals(2, chapters.size)
        assertEquals("123", chapters[0].id)
        assertTrue(chapters[0].finished)
        assertEquals(2, chapters[0].jobCount)
        assertEquals("124", chapters[1].id)
        assertFalse(chapters[1].finished)
        assertTrue(chapters[1].needUnlock)
        assertEquals(1, chapters[1].jobCount)
    }

    @Test
    fun parseCards_keepsOnlyUnfinishedVideoJobs() {
        val json = """
            {"attachments":[
              {"jobid":"1","type":"video","objectId":"obj1","playTime":1500,
               "otherInfo":"nodeId_1-rt_1d&extra=1",
               "property":{"name":"视频一","rt":"1"},
               "isPassed":false},
              {"jobid":"2","type":"video","objectId":"obj2","isPassed":true,
               "property":{"name":"已完成"}},
              {"jobid":"3","type":"document","objectId":"obj3",
               "property":{"name":"文档"}}
            ]}
        """.trimIndent()
        val html = "<script>mArg=$json;</script>"

        val result = StudyParser.parseCards(html)
        assertFalse(result.notOpen)
        assertEquals(1, result.jobs.size)
        val job = result.jobs[0]
        assertEquals("1", job.jobId)
        assertEquals("obj1", job.objectId)
        assertEquals(1500L, job.playTimeMs)
        assertEquals("1", job.rt)
        assertEquals("nodeId_1-rt_1d", job.otherInfo)
    }

    @Test
    fun parseCards_detectsClosedChapter() {
        val result = StudyParser.parseCards("<html>章节未开放</html>")
        assertTrue(result.notOpen)
        assertTrue(result.jobs.isEmpty())
    }

    @Test
    fun parseCardTasks_keepsQuizPending() {
        val json = """
            {"attachments":[
              {"jobid":"v1","type":"video","objectId":"obj1","property":{"name":"视频"},"isPassed":true},
              {"jobid":"work-1","type":"workid","property":{"title":"章节测验"},"isPassed":false}
            ]}
        """.trimIndent()
        val tasks = StudyParser.parseCardTasks("<script>mArg=$json;</script>")
        assertEquals(2, tasks.size)
        assertEquals(1, tasks.count { it.isVideo })
        assertEquals(1, tasks.count { it.isVideo && it.passed })
        assertEquals(1, tasks.count { it.passed })
    }
}
