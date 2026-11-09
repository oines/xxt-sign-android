package com.xxt.sign.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 挂机模式的判定规则：交卷与「低置信」提示的边界 */
class QuizResultTest {

    private fun result(answered: Int, total: Int) = QuizResult(
        title = "第一章",
        submitted = true,
        formal = true,
        answered = answered,
        total = total,
        message = "",
        hitRate = if (total > 0) answered.toFloat() / total else 0f
    )

    @Test
    fun lowConfidenceWhenHitRateBelowThreshold() {
        // 8/10 = 80%，阈值 0.8 → 不算低置信
        assertFalse(result(8, 10).isLowConfidence(0.8f))
        // 7/10 = 70% → 低置信
        assertTrue(result(7, 10).isLowConfidence(0.8f))
    }

    @Test
    fun noQuestionsIsNotLowConfidence() {
        assertFalse(result(0, 0).isLowConfidence(0.8f))
    }

    @Test
    fun submittedWithAnswersIsOk() {
        assertTrue(result(5, 10).ok)
        assertFalse(result(0, 10).copy(submitted = false).ok)
    }
}
