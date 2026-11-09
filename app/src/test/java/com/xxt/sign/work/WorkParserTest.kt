package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 章节测验页面解析（纯 JVM，喂 HTML 即可） */
class WorkParserTest {

    private val html = """
        <html><head>
        <style id="cxSecretStyle">@font-face{font-family:cxSecret;src:url(data:font/ttf;base64,AAECAwQFBgc=) format("truetype");}</style>
        </head><body>
        <form id="workForm" action="/mooc-ans/work/addStudentWorkNew" method="post">
          <input type="hidden" name="enc" value="abc123">
          <input type="hidden" name="cpi" value="1234">
          <input type="hidden" name="ktoken" value="tk-9">
          <input type="hidden" name="workAnswerId" value="98765">
          <input type="hidden" name="pyFlag" value="">
          <input type="submit" name="submitBtn" value="提交">
          <div class="ceyan_name"><h1>第一章 章节测验</h1><span>题量: 2</span></div>

          <div class="singleQuesId" id="question111">
            <div class="Zy_TItle">1.（单选题）以下哪个是密文字？</div>
            <input type="hidden" id="answertype111" value="0">
            <ul>
              <li><span class="num_option" data="A|false|false">选项甲</span></li>
              <li><span class="num_option" data="B|true|false">选项乙</span></li>
            </ul>
          </div>

          <div class="singleQuesId" id="question222">
            <div class="Zy_TItle">2.（判断题）这句话是对的。</div>
            <input type="hidden" id="answertype222" value="3">
            <ul>
              <li><span class="num_option_dx" data="true|true|false">对</span></li>
              <li><span class="num_option_dx" data="false|false|false">错</span></li>
            </ul>
          </div>
        </form></body></html>
    """.trimIndent()

    /**
     * 新版 mooc2 页面（真机 0604 上那份）：form id=form1、action 带 token/totalQuestionNum、
     * 多选/判断用 `num_option_dx`、填空题只有 `answerEditor<qid><空号>` 没有 `answer<qid>`。
     */
    private val newPage = """
        <html><body>
        <form action="addStudentWorkNewWeb?_classId=1533&amp;courseid=2551&amp;token=tok-1&amp;totalQuestionNum=fa7b&amp;workid=5811" method="post" name="form1" id="form1">
          <input type="hidden" name="pyFlag" id="pyFlag">
          <input type="hidden" name="courseId" value="2551">
          <input type="hidden" name="classId" value="1533">
          <input type="hidden" name="api" value="1">
          <input type="hidden" name="workAnswerId" value="5551">
          <input type="hidden" name="answerId" value="5551">
          <input type="hidden" name="totalQuestionNum" value="fa7b">
          <input type="hidden" name="enc_work" value="tok-1">
          <input type="hidden" name="answerwqbid" value="">
          <input type="hidden" name="answer900001" value="">
          <input type="hidden" name="answertype900001" value="1">
          <input type="hidden" name="answerEditor9000021" value="">
          <input type="hidden" name="answertype900002" value="2">
          <div class="ceyan_name"><h1>课堂测验</h1><span>题量: 2</span></div>
          <div class="singleQuesId" id="question900001">
            <div class="Zy_TItle"><span class="newZy_TItle">【多选题】</span>以下哪些正确？</div>
            <ul>
              <li qid="900001" qtype="1"><span class="num_option_dx choice900001" data="A">甲</span></li>
              <li qid="900001" qtype="1"><span class="num_option_dx choice900001" data="B">乙</span></li>
            </ul>
          </div>
          <div class="singleQuesId" id="question900002">
            <div class="Zy_TItle"><span class="newZy_TItle">【填空题】</span>（ ）是基本方略。</div>
            <input type="hidden" id="answertype900002" value="2">
          </div>
        </form></body></html>
    """.trimIndent()

    @Test
    fun parsesQuestionCountAndBlocks() {
        val page = WorkParser.parse(html)
        assertEquals(2, page.questionCount)
        assertEquals(2, page.questions.size)
        assertFalse(page.submitted)
    }

    @Test
    fun parsesQuestionTypesFromAnswertypeInput() {
        val page = WorkParser.parse(html)
        assertEquals(WorkQuestion.TYPE_SINGLE, page.questions[0].type)
        assertEquals(WorkQuestion.TYPE_JUDGE, page.questions[1].type)
    }

    @Test
    fun parsesOptionKeysFromDataAttr() {
        val page = WorkParser.parse(html)
        assertEquals(listOf("A", "B"), page.questions[0].options.map { it.key })
        assertEquals("选项甲", page.questions[0].options[0].text)
        assertEquals(listOf("true", "false"), page.questions[1].options.map { it.key })
    }

    /** 新版页面多选/判断的选项是 num_option_dx，旧解析器只认 num_option 会一个选项都拿不到 */
    @Test
    fun parsesOptionsFromNumOptionDx() {
        val page = WorkParser.parse(newPage)
        assertEquals(listOf("A", "B"), page.questions[0].options.map { it.key })
        assertEquals("甲", page.questions[0].options[0].text)
    }

    /**
     * 提交字段必须原样全带：answer 前缀的字段是服务端点数的，删了就「参数异常」。
     * 老代码在这里把 name.startsWith("answer") 全过滤掉，是这次 bug 的根因。
     */
    @Test
    fun keepsAllNamedFormFieldsIncludingAnswerPrefixed() {
        val page = WorkParser.parse(html)
        assertEquals("abc123", page.formFields["enc"])
        assertEquals("1234", page.formFields["cpi"])
        assertEquals("tk-9", page.formFields["ktoken"])
        assertEquals("98765", page.formFields["workAnswerId"])
        assertFalse(page.formFields.containsKey("submitBtn"))
    }

    @Test
    fun keepsAnswerIdAndPerQuestionAnswerFieldsVerbatim() {
        val page = WorkParser.parse(newPage)
        assertEquals("5551", page.formFields["answerId"])
        assertEquals("fa7b", page.formFields["totalQuestionNum"])
        assertTrue(page.formFields.containsKey("answer900001"))
        assertEquals("1", page.formFields["answertype900001"])
        assertEquals("2", page.formFields["answertype900002"])
    }

    @Test
    fun readsFormActionWithTokens() {
        val page = WorkParser.parse(newPage)
        assertTrue(page.formAction.startsWith("addStudentWorkNewWeb?"))
        assertTrue(page.formAction.contains("token=tok-1"))
        assertTrue(page.formAction.contains("totalQuestionNum=fa7b"))
    }

    /** 填空题没有 answer<qid>，真实字段是 answerEditor<qid><空号> */
    @Test
    fun findsBlankEditorFieldsPerQuestion() {
        val page = WorkParser.parse(newPage)
        assertEquals(listOf("answerEditor9000021"), page.blankFields["900002"])
        assertNull(page.blankFields["900001"])
        assertTrue(page.questions[1].isRichText)
        assertFalse(page.questions[0].isRichText)
    }

    @Test
    fun extractsEmbeddedFont() {
        val page = WorkParser.parse(html)
        assertNotNull(page.fontBase64)
        assertEquals("AAECAwQFBgc=", page.fontBase64)
    }

    @Test
    fun detectsSubmittedRecordPage() {
        val page = WorkParser.parse("<html><body><div>已交卷</div><div>查看详情</div></body></html>")
        assertTrue(page.submitted)
        assertEquals(0, page.questions.size)
    }

    @Test
    fun parsesRecordSummary() {
        val list = """
            <a href="/mooc-ans/work/record-detail?times=1">第1次</a>
            <a href="/mooc-ans/work/record-detail?times=2">第2次</a>
            <span>最高分 88 分</span>
        """.trimIndent()
        val summary = RecordParser.parseList(list)
        assertEquals(2, summary.times)
        assertEquals("88", summary.score)
    }

    /** 真正的回查页（selectWorkQuestionYiPiYue）结构：第1次作答 + 本次成绩<i>80</i>分 */
    @Test
    fun parsesReviewPageScore() {
        val review = """
            <div class="dividerCon">
              <span class="ans_number">第1次作答<i></i></span>
              <span class="achievement">本次成绩<i>80</i>分</span>
            </div>
        """.trimIndent()
        val summary = RecordParser.parseList(review)
        assertEquals(1, summary.times)
        assertEquals("80", summary.score)
        assertTrue(summary.seen)
    }

    @Test
    fun stemDoesNotSwallowOptions() {
        val stem = WorkParser.parse(html).questions[0].stem
        assertTrue(stem.contains("以下哪个是密文字"))
        assertFalse(stem.contains("选项甲"))
    }

    @Test
    fun plainPageHasNoFont() {
        val page = WorkParser.parse("<html><body><div class='ceyan_name'>题量: 0</div></body></html>")
        assertNull(page.fontBase64)
    }
}
