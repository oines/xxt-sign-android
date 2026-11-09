package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 题库答案 → 提交格式的归一化，纯函数 */
class AnswerNormalizerTest {

    private fun q(type: Int, vararg keys: String) = WorkQuestion(
        qid = "1",
        type = type,
        stem = "题",
        options = keys.map { WorkOption(it, "选项$it") }
    )

    @Test
    fun singleTakesFirstValidLetter() {
        val choice = q(WorkQuestion.TYPE_SINGLE, "A", "B", "C")
        assertEquals("B", AnswerNormalizer.normalize("B", choice))
        assertEquals("C", AnswerNormalizer.normalize("答案：C", choice))
        assertNull(AnswerNormalizer.normalize("Z", choice))
    }

    @Test
    fun multiIsSortedAndDeduped() {
        val multi = q(WorkQuestion.TYPE_MULTI, "A", "B", "C", "D")
        assertEquals("ABC", AnswerNormalizer.normalize("CAB", multi))
        assertEquals("ABD", AnswerNormalizer.normalize("d,a,b", multi))
        assertNull(AnswerNormalizer.normalize("E", multi))
    }

    @Test
    fun judgeMapsChineseAndEnglish() {
        val judge = q(WorkQuestion.TYPE_JUDGE, "true", "false")
        assertEquals("true", AnswerNormalizer.normalize("正确", judge))
        assertEquals("true", AnswerNormalizer.normalize("对", judge))
        assertEquals("true", AnswerNormalizer.normalize("true", judge))
        assertEquals("false", AnswerNormalizer.normalize("错误", judge))
        assertEquals("false", AnswerNormalizer.normalize("false", judge))
        assertEquals("false", AnswerNormalizer.normalize("错", judge))
    }

    @Test
    fun blankKeepsOriginalText() {
        val blank = q(WorkQuestion.TYPE_BLANK)
        assertEquals("光合作用", AnswerNormalizer.normalize(" 光合作用 ", blank))
        assertEquals("a;b", AnswerNormalizer.normalize("a;b", blank))
    }
}
