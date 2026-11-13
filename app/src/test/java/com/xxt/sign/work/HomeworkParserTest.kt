package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeworkParserTest {

    private val course = HomeworkCourse("222628761", "156257438", "574255802", "中国近现代史纲要")

    @Test
    fun parsesUnsubmittedAndSubmittedItems() {
        val html = """
            <html><body>
            <ul>
              <li onclick="goTask(this);" data="/work/phone/task-work?taskrefId=58194484" data1="58194484">
                <img src="x.png">
                <div class="xx"><p>第三章第一课时 课后练习</p>
                  <span>未交</span><span class="fr">剩余 2 小时 10 分钟</span></div>
              </li>
              <li data1="58145003">
                <div><p>已经交过的作业</p><span>待批阅</span><span class="fr"></span></div>
              </li>
              <li data1="not-a-number"><p>忽略</p><span>未交</span></li>
            </ul>
            </body></html>
        """.trimIndent()

        val items = HomeworkParser.parseList(html, course)

        assertEquals(2, items.size)
        assertEquals("58194484", items[0].workId)
        assertEquals("第三章第一课时 课后练习", items[0].title)
        assertEquals("未交", items[0].status)
        assertTrue(items[0].unsubmitted)
        assertEquals("剩余 2 小时 10 分钟", items[0].deadline)
        assertEquals("中国近现代史纲要", items[0].course.title)

        assertEquals("58145003", items[1].workId)
        assertFalse(items[1].unsubmitted)
    }

    @Test
    fun readsTotalPagesFromHiddenInput() {
        assertEquals(3, HomeworkParser.totalPages("""<input type="hidden" id="totalPage" value="3">"""))
        assertEquals(1, HomeworkParser.totalPages("<html></html>"))
    }

    @Test
    fun extractsDoHomeWorkUrlWithAmpEntity() {
        val html = """
            <a href="#" onclick="location.href='/work/phone/doHomeWork?courseId=222628761&amp;workId=57978007&amp;enc=abc'">去作答</a>
        """.trimIndent()
        val url = HomeworkParser.doHomeWorkUrl(html)
        assertEquals("/work/phone/doHomeWork?courseId=222628761&workId=57978007&enc=abc", url)
        assertNull(HomeworkParser.doHomeWorkUrl("<html>nothing here</html>"))
    }

    @Test
    fun parsesMultiChoiceQuestionWithFracAndOptionText() {
        val html = """
            <html><body>
            <input type="hidden" id="index" value="6">
            <input type="hidden" name="type401696900" value="1">
            <div class="wid750 singleQuesId" data="401696900">
              <h2 aria-hidden="true" class="titType">7. 多选题</h2>
              <div class="ans-cc timuStyle fontLabel workWrap"><span aria-label="题干"></span>
                <p>下列属于戊戌六君子的是（ ）。</p></div>
              <div class="Answer workTextWrap">
                <span data="answer401696900" class="dxcheck" aria-label="选项A.康有为" orichoice="A">A</span>
                <div aria-hidden="true" id="A" class="centerSpan workWrap">康有为</div>
              </div>
              <div class="Answer workTextWrap">
                <span data="answer401696900" class="dxcheck" aria-label="选项B.梁启超" orichoice="B">B</span>
                <div aria-hidden="true" id="B" class="centerSpan workWrap">梁启超</div>
              </div>
              <div class="Answer workTextWrap">
                <span data="answer401696900" class="dxcheck" aria-label="选项C.李鸿章" orichoice="C">C</span>
                <div aria-hidden="true" id="C" class="centerSpan workWrap">李鸿章</div>
              </div>
            </div>
            <b aria-hidden="true">7/10</b>答题卡
            </body></html>
        """.trimIndent()

        val q = HomeworkParser.parseQuestion(html, "57978007", 6)

        assertNotNull(q)
        q!!
        assertEquals("401696900", q.qid)
        assertEquals(7, q.number)
        assertEquals(10, q.total)
        assertEquals(1, q.type)
        assertEquals("多选题", q.typeName)
        assertTrue(q.stem.contains("戊戌六君子"))
        assertFalse(q.stem.contains("康有为"))
        assertEquals(listOf("A", "B", "C"), q.options.map { it.key })
        assertEquals(listOf("康有为", "梁启超", "李鸿章"), q.options.map { it.text })
        assertEquals("A. 康有为；B. 梁启超", q.prettyAnswer("AB"))
    }

    @Test
    fun parsesSingleQuestionWithoutFrac() {
        val html = """
            <html><body>
            <input type="hidden" id="index" value="0">
            <input type="hidden" name="type406162693" value="4">
            <div class="wid750 singleQuesId" data="406162693">
              <h2 aria-hidden="true" class="titType">1. 简答题</h2>
              <div class="ans-cc timuStyle fontLabel workWrap"><p>AI 视频创作作业 作业要求：创作一个 AI 数字名人智能体。</p></div>
            </div>
            </body></html>
        """.trimIndent()

        val q = HomeworkParser.parseQuestion(html, "58161401", 0)

        assertNotNull(q)
        q!!
        assertEquals("406162693", q.qid)
        assertEquals(1, q.number)
        assertEquals(1, q.total)
        assertEquals(4, q.type)
        assertEquals("简答题", q.typeName)
        assertTrue(q.options.isEmpty())
    }

    @Test
    fun singleChoiceOptionFallsBackToAriaLabel() {
        val html = """
            <html><body>
            <input type="hidden" id="index" value="0">
            <input type="hidden" name="type1" value="0">
            <div class="wid750 singleQuesId" data="1">
              <h2 class="titType">1. 单选题</h2>
              <div class="timuStyle"><p>题干</p></div>
              <div class="Answer"><span class="choose check" aria-label="选项A.资产阶级改良运动" orichoice="A">A</span></div>
            </div>
            </body></html>
        """.trimIndent()

        val q = HomeworkParser.parseQuestion(html, "1", 0)!!
        assertEquals(1, q.options.size)
        assertEquals("资产阶级改良运动", q.options[0].text)
        assertEquals("A", q.options[0].key)
    }

    @Test
    fun parsesSubmitFormFieldsAndSingleAnswerField() {
        val html = """
            <html><body>
            <div class="wid750 singleQuesId" data="401696891">
              <form id="submitTest" action="#" method="post">
                <input type="hidden" name="workRelationAnswerId" value="56538741"/>
                <input type="hidden" name="enc" value="adb1cbced0d72a9f88b5b566d9c362a5"/>
                <input type="hidden" name="encWork" value="09a163098aa1cbfd8bad178e2ab00ed5"/>
                <input type="hidden" name="courseId" value="222628761"/>
                <input type="hidden" name="workRelationId" value="57978007"/>
                <input type="hidden" name="classId" value="156257438"/>
                <input type="hidden" name="answer401696891" value="" class="answerInput"/>
                <input type="hidden" name="type401696891" value="0"/>
                <input type="hidden" name="score401696891" value="10.0"/>
                <input type="hidden" name="questionId" value="401696891"/>
                <input type="hidden" name="index" value="0"/>
                <input type="hidden" name="tempSave" value="true"/>
              </form>
              <h2 class="titType">1. 单选题</h2>
              <div class="timuStyle"><p>戊戌维新运动是一次（ ）。</p></div>
              <div class="Answer"><span class="choose check" aria-label="选项A.资产阶级改良运动" orichoice="A">A</span></div>
            </div>
            </body></html>
        """.trimIndent()

        val q = HomeworkParser.parseQuestion(html, "57978007", 0)!!
        assertEquals("answer401696891", q.answerField)
        assertEquals(0, q.pageIndex)
        assertEquals("56538741", q.formFields["workRelationAnswerId"])
        assertEquals("adb1cbced0d72a9f88b5b566d9c362a5", q.formFields["enc"])
        assertEquals("401696891", q.formFields["questionId"])
        assertEquals("0", q.formFields["index"])
        assertEquals("10.0", q.formFields["score401696891"])
        assertFalse(q.formFields.containsKey("answer401696891") && q.formFields["answer401696891"] != "")
    }

    @Test
    fun parsesMultiChoiceAnswerFieldWithAnswersPrefix() {
        val html = """
            <html><body>
            <div class="wid750 singleQuesId" data="401696900">
              <form id="submitTest" action="#" method="post">
                <input type="hidden" name="answers401696900" value=""/>
                <input type="hidden" name="type401696900" value="1"/>
                <input type="hidden" name="questionId" value="401696900"/>
                <input type="hidden" name="index" value="6"/>
              </form>
              <h2 class="titType">7. 多选题</h2>
              <div class="timuStyle"><p>下列属于戊戌六君子的是（ ）。</p></div>
              <div class="Answer"><span data="401696900" class="dxcheck" aria-label="选项A.康有为" orichoice="A">A</span></div>
            </div>
            <b aria-hidden="true">7/10</b>
            </body></html>
        """.trimIndent()

        val q = HomeworkParser.parseQuestion(html, "57978007", 6)!!
        assertEquals("answers401696900", q.answerField)
        assertEquals(1, q.type)
        assertEquals(6, q.pageIndex)
        assertEquals(7, q.number)
        assertEquals(10, q.total)
    }
}
