package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提交表单拼装：真机上「答案全对但交卷被拒」的回归测试。
 *
 * 背景（2026-11 真账号验过 6 次真实提交）：
 *  - 删掉未答题的 `answer<qid>`（哪怕只是空的）→ 服务端回「无效的提交，参数异常！」
 *  - 填空题写 `answer<qid>` / `answerEditor<qid>`（缺空号）→ 「无效的参数：code-2！」
 *  - 用页面 form 的 action + 完整字段 → success，回查 80/100 分
 */
class SubmitFormTest {

    private val page = WorkParser.parse(
        """
        <html><body>
        <form action="addStudentWorkNewWeb?_classId=1533&amp;courseid=2551&amp;token=tok-1&amp;totalQuestionNum=fa7b" method="post" id="form1">
          <input type="hidden" name="pyFlag" value="">
          <input type="hidden" name="courseId" value="2551">
          <input type="hidden" name="classId" value="1533">
          <input type="hidden" name="api" value="1">
          <input type="hidden" name="workAnswerId" value="5551">
          <input type="hidden" name="answerId" value="5551">
          <input type="hidden" name="totalQuestionNum" value="fa7b">
          <input type="hidden" name="enc_work" value="tok-1">
          <input type="hidden" name="answerwqbid" value="">
          <input type="hidden" name="answer900001" value="">
          <input type="hidden" name="answertype900001" value="0">
          <input type="hidden" name="answer900002" value="">
          <input type="hidden" name="answertype900002" value="0">
          <input type="hidden" name="answerEditor9000031" value="">
          <input type="hidden" name="answertype900003" value="2">
          <div class="ceyan_name"><h1>测验</h1><span>题量: 3</span></div>
          <div class="singleQuesId" id="question900001">
            <div class="Zy_TItle">第1题</div><input type="hidden" id="answertype900001" value="0">
            <span class="num_option" data="A">甲</span>
          </div>
          <div class="singleQuesId" id="question900002">
            <div class="Zy_TItle">第2题</div><input type="hidden" id="answertype900002" value="0">
            <span class="num_option" data="A">甲</span>
          </div>
          <div class="singleQuesId" id="question900003">
            <div class="Zy_TItle">第3题（填空）</div><input type="hidden" id="answertype900003" value="2">
          </div>
        </form></body></html>
        """.trimIndent()
    )

    /** 只答了第 1 题：第 2 题的 answer<qid> / answertype<qid> 必须还在，只是值为空 */
    @Test
    fun keepsEmptyAnswerFieldsForUnansweredQuestions() {
        val form = SubmitForm.build(page, mapOf("900001" to "A"), storeOnly = false)
        assertTrue("未答题的 answer 字段不能被删", form.containsKey("answer900002"))
        assertEquals("", form["answer900002"])
        assertEquals("0", form["answertype900002"])
        // answerId 也必须在（老代码把它按 answer 前缀误删了）
        assertEquals("5551", form["answerId"])
        assertEquals("5551", form["workAnswerId"])
    }

    @Test
    fun fillsAnsweredChoiceAndKeepsTypeField() {
        val form = SubmitForm.build(page, mapOf("900001" to "A", "900002" to "B"), storeOnly = false)
        assertEquals("A", form["answer900001"])
        assertEquals("B", form["answer900002"])
        assertEquals("0", form["answertype900001"])
    }

    /** 填空题答案必须写进 answerEditor<qid><空号>，不能写 answer<qid> */
    @Test
    fun writesBlankAnswerIntoEditorSlot() {
        val form = SubmitForm.build(page, mapOf("900003" to "依法治国"), storeOnly = false)
        assertEquals("依法治国", form["answerEditor9000031"])
        assertFalse(form.containsKey("answer900003"))
        assertFalse(form.containsKey("answerEditor900003"))
    }

    /** 老版页面填空题没有 answerEditor，只有 answer<qid> 的 textarea，就写回它 */
    @Test
    fun writesBlankAnswerIntoLegacyAnswerField() {
        val legacy = WorkParser.parse(
            """
            <html><body><form action="addStudentWorkNewWeb?_classId=1">
              <input type="hidden" name="answerwqbid" value="">
              <div class="ceyan_name">题量: 1</div>
              <div class="singleQuesId" id="question700001">
                <div class="Zy_TItle">填空题</div>
                <input type="hidden" id="answertype700001" value="2">
                <textarea name="answer700001"></textarea>
              </div>
            </form></body></html>
            """.trimIndent()
        )
        val form = SubmitForm.build(legacy, mapOf("700001" to "依法治国"), storeOnly = false)
        assertEquals("依法治国", form["answer700001"])
        assertFalse(form.containsKey("answerEditor7000011"))
    }

    @Test
    fun buildsAnswerQidListWithTrailingComma() {
        val form = SubmitForm.build(page, mapOf("900001" to "A"), storeOnly = false)
        assertEquals("900001,900002,900003,", form["answerwqbid"])
    }

    @Test
    fun pyFlagIsOneOnlyWhenStoring() {
        assertEquals("1", SubmitForm.build(page, emptyMap(), storeOnly = true)["pyFlag"])
        assertEquals("", SubmitForm.build(page, emptyMap(), storeOnly = false)["pyFlag"])
    }

    /** 提交地址用页面 form 的 action（带 token/totalQuestionNum），并补浏览器那 4 个参数 */
    @Test
    fun submitUrlUsesFormAction() {
        val url = SubmitForm.url(page)
        assertTrue(url.startsWith("https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNewWeb?"))
        assertTrue(url.contains("token=tok-1"))
        assertTrue(url.contains("totalQuestionNum=fa7b"))
        assertTrue(url.endsWith("&ua=pc&formType=post&saveStatus=1&version=1"))
    }

    /** 页面没给 action 时退回老地址，不能拼出半个 URL */
    @Test
    fun submitUrlFallsBackToLegacyEndpoint() {
        val legacy = WorkParser.parse("<html><body><div class='ceyan_name'>题量: 0</div></body></html>")
        assertEquals(
            "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew" +
                "?ua=pc&formType=post&saveStatus=1&version=1",
            SubmitForm.url(legacy)
        )
    }

    /** 绝对路径的 action（老版页面是 /mooc-ans/work/addStudentWorkNewWeb?...） */
    @Test
    fun submitUrlHandlesAbsolutePathAction() {
        val legacy = WorkParser.parse(
            "<html><body><form action=\"/mooc-ans/work/addStudentWorkNewWeb?_classId=1\">" +
                "<div class='ceyan_name'>题量: 0</div></form></body></html>"
        )
        assertEquals(
            "https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNewWeb?_classId=1" +
                "&ua=pc&formType=post&saveStatus=1&version=1",
            SubmitForm.url(legacy)
        )
    }
}
