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
              <li><span class="num_option" data="true|true|false">对</span></li>
              <li><span class="num_option" data="false|false|false">错</span></li>
            </ul>
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

    @Test
    fun keepsHiddenFormFieldsButDropsAnswerPrefixedAndSubmit() {
        val page = WorkParser.parse(html)
        assertEquals("abc123", page.formFields["enc"])
        assertEquals("1234", page.formFields["cpi"])
        assertEquals("tk-9", page.formFields["ktoken"])
        assertEquals("98765", page.formFields["workAnswerId"])
        assertFalse(page.formFields.containsKey("submitBtn"))
        assertTrue(page.formFields.keys.none { it.startsWith("answer") })
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
