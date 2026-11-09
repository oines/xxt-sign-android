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
              {"jobid":"1","job":true,"type":"video","objectId":"obj1","playTime":1500,
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
              {"jobid":"v1","job":true,"type":"video","objectId":"obj1","property":{"name":"视频"},"isPassed":true},
              {"jobid":"work-1","job":true,"type":"workid","property":{"title":"章节测验"},"isPassed":false}
            ]}
        """.trimIndent()
        val tasks = StudyParser.parseCardTasks("<script>mArg=$json;</script>")
        assertEquals(2, tasks.size)
        assertEquals(1, tasks.count { it.isVideo })
        assertEquals(1, tasks.count { it.isVideo && it.passed })
        assertEquals(1, tasks.count { it.passed })
    }

    /** 只有 jobid + job==true 才是任务点；资料/附件不算，不能进「还差什么」 */
    @Test
    fun parseCardTasks_ignoresAttachmentsWithoutJobFlag() {
        val json = """
            {"attachments":[
              {"jobid":"v1","job":true,"type":"video","property":{"name":"视频"},"isPassed":false},
              {"jobid":"doc1","type":"document","property":{"name":"资料"},"isPassed":false},
              {"mid":"m1","type":"document","property":{"name":"附件"},"isPassed":false}
            ]}
        """.trimIndent()
        val tasks = StudyParser.parseCardTasks("<script>mArg=$json;</script>")
        assertEquals(1, tasks.size)
        assertEquals("v1", tasks[0].key)
    }

    /** 文档任务点：type=document + jtoken，一次 GET 就能完成 */
    @Test
    fun parseDocumentJobs_readsJtoken() {
        val json = """
            {"attachments":[
              {"jobid":"doc-1","job":true,"type":"document","jtoken":"jt-abc",
               "otherInfo":"nodeId_8899-rt_1d",
               "property":{"name":"第一章 文档"}},
              {"jobid":"v-1","job":true,"type":"video","objectId":"o1",
               "property":{"name":"视频"}},
              {"jobid":"doc-2","type":"document","jtoken":"jt-x",
               "property":{"name":"非任务点"}}
            ]}
        """.trimIndent()
        val docs = StudyParser.parseDocumentJobs("<script>mArg=$json;</script>")
        assertEquals(1, docs.size)
        assertEquals("doc-1", docs[0].jobId)
        assertEquals("jt-abc", docs[0].jtoken)
        assertEquals("第一章文档", docs[0].name)  // mArg 是去空格后解析的
        assertEquals("8899", docs[0].knowledgeId)  // 完成接口要的 knowledgeid 来自 otherInfo
    }

    /** 早就做完的文档不要再请求一遍（真机上 157 个文档全绿还被逐条 GET） */
    @Test
    fun parseDocumentJobs_skipsFinished() {
        val json = """
            {"attachments":[
              {"jobid":"doc-1","job":true,"type":"document","jtoken":"jt-abc","isPassed":true,
               "property":{"name":"已完成的文档"}},
              {"jobid":"doc-2","job":true,"type":"document","jtoken":"jt-two","isPassed":false,
               "property":{"name":"待做文档"}}
            ]}
        """.trimIndent()
        val docs = StudyParser.parseDocumentJobs("<script>mArg=$json;</script>")
        assertEquals(1, docs.size)
        assertEquals("doc-2", docs[0].jobId)
    }

    /** 测验类型只能看 property.module + property.worktype，不能看标题 */
    @Test
    fun parseCardTasks_readsWorktypeNotTitle() {
        val json = """
            {"attachments":[
              {"jobid":"work-5cea97cf3be1429fb3e4bf231cc0524d","job":true,"type":"workid",
               "property":{"module":"work","worktype":"workA","title":"随堂测验",
                 "workid":"5cea97cf3be1429fb3e4bf231cc0524d"},"isPassed":false},
              {"jobid":"work-b","job":true,"type":"workid",
               "property":{"module":"work","worktype":"workB","title":"随堂测验"},"isPassed":false}
            ]}
        """.trimIndent()
        val tasks = StudyParser.parseCardTasks("<script>mArg=$json;</script>")
        assertEquals(2, tasks.size)
        val quiz = tasks.first { it.key.startsWith("work-5") }
        assertTrue("workA 就是章节测验", quiz.isQuiz)
        assertEquals("work", quiz.module)
        assertEquals("workA", quiz.worktype)
        val other = tasks.first { it.key == "work-b" }
        assertFalse("非 workA 不猜成测验", other.isQuiz)
        assertTrue(other.isOtherWork)
    }

    /** 章节完成状态看 icon_yiwanc，不看 isPassed */
    @Test
    fun parseChapters_usesIconYiwancForFinished() {
        val html = """
            <div class="chapter_unit"><ul>
              <li><div id="cur1001"><a class="clicktitle">第一章</a>
                  <input class="knowledgeJobCount" value="3"><i class="icon_yiwanc"></i></div></li>
              <li><div id="cur1002"><a class="clicktitle">第二章</a>
                  <input class="knowledgeJobCount" value="2"><i class="icon_yiwanc icon_yiwanctwo"></i></div></li>
            </ul></div>
        """.trimIndent()
        val chapters = StudyParser.parseChapters(html)
        assertEquals(2, chapters.size)
        assertTrue("有 icon_yiwanc 的是已完成", chapters[0].finished)
        assertFalse("icon_yiwanctwo 是未完成", chapters[1].finished)
    }

    /** 「无视频任务」必须能区分：本来就做完 vs 解析没匹配上 */
    @Test
    fun parseCards_reportsVideoTotalsAndMalformed() {
        val json = """
            {"attachments":[
              {"jobid":"v1","job":true,"type":"video","objectId":"o1","isPassed":true,
               "property":{"name":"已完成的视频"}},
              {"jobid":"v2","job":true,"type":"video","objectId":"o2","isPassed":false,
               "property":{"name":"待刷视频"}},
              {"jobid":"v3","job":true,"type":"video","isPassed":false,
               "property":{"name":"缺 objectId 的视频"}}
            ]}
        """.trimIndent()
        val r = StudyParser.parseCards("<script>mArg=$json;</script>")
        assertEquals(1, r.jobs.size)
        assertEquals(3, r.videoTotal)
        assertEquals(1, r.videoDone)
        assertEquals(1, r.malformed)
    }

    /** 平台自报的「已完成任务点 X/Y」：真机原样 HTML（数字后面紧跟 </span>，不能靠 >数字<） */
    @Test
    fun parseTaskPointTotals_readsRealMarkup() {
        val real = """
            <h2 class="xs_head_name fl" tabindex="0"><i class="catalog_points_yi fl"></i>

            已完成任务点: <span style="color:#00B368">11</span>/392
                    <div class="catalog_jindu">
                        <div class="catalog_ressbar"><div class="catalog_ressbar_width" style="width:2%"></div></div>
                    </div>
            </h2>
        """.trimIndent()
        assertEquals(11 to 392, StudyParser.parseTaskPointTotals(real))
    }

    @Test
    fun parseTaskPointTotals_toleratesPlainTextAndRejectsGarbage() {
        assertEquals(11 to 392, StudyParser.parseTaskPointTotals("已完成任务点: 11/392"))
        assertEquals(392 to 392, StudyParser.parseTaskPointTotals("已完成任务点：392/392"))
        assertEquals(null, StudyParser.parseTaskPointTotals("<html>没有这行</html>"))
        assertEquals(null, StudyParser.parseTaskPointTotals("已完成任务点: 0/0"))
    }
}
