package com.xxt.sign

import com.xxt.sign.study.PopQuizBridge
import com.xxt.sign.study.PopQuizDecision
import com.xxt.sign.study.PopQuizPolicy
import com.xxt.sign.study.PopQuizQuestion
import com.xxt.sign.study.ConfiguredPopQuizBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹题处置：解出来就答、答不上 60 秒跳过、绝不能让整门课卡死。
 */
class PopQuizTest {

    private val q = PopQuizQuestion("q1", 0, "2+2=?", listOf("3", "4"))

    private val configured = object : PopQuizBridge {
        override val configured = true
        override suspend fun poll(job: com.xxt.sign.study.VideoJob, atSecond: Int) = emptyList<PopQuizQuestion>()
        override suspend fun submit(
            job: com.xxt.sign.study.VideoJob,
            question: PopQuizQuestion,
            answer: String
        ) = true
    }

    @Test
    fun answersAllContinues() {
        val out = PopQuizPolicy.decide(configured, listOf(q), listOf("4"), timedOut = false, stalledRounds = 0)
        assertTrue(out.decision is PopQuizDecision.Continue)
        assertEquals(1, out.result.total)
        assertEquals(1, out.result.answered)
        assertFalse(out.result.needsHuman)
        assertEquals("中途弹题 1 道，全答上了", out.result.summary())
    }

    @Test
    fun unansweredButNotYetTimedOutKeepsTrying() {
        val out = PopQuizPolicy.decide(configured, listOf(q), listOf(""), timedOut = false, stalledRounds = 0)
        assertTrue("还没超时就该继续重试，而不是立刻放弃", out.decision is PopQuizDecision.Continue)
    }

    @Test
    fun timedOutGivesUpWithNeedsHuman() {
        val out = PopQuizPolicy.decide(configured, listOf(q), listOf(""), timedOut = true, stalledRounds = 0)
        val d = out.decision
        assertTrue(d is PopQuizDecision.GiveUp)
        assertTrue((d as PopQuizDecision.GiveUp).needsHuman)
        assertTrue(out.result.summary().contains("超时"))
    }

    @Test
    fun stalledWithoutQuestionsIsNotQuizButPlaybackStall() {
        // 真机反馈：连续 4 次「卡在弹题」、第 5 次同一个视频直接完成 —— 其实是播放停滞
        val out = PopQuizPolicy.decide(
            PopQuizBridge.NONE, emptyList(), emptyList(),
            timedOut = false, stalledRounds = PopQuizPolicy.STALL_ROUNDS
        )
        val d = out.decision
        assertTrue("没读到题就不能叫弹题", d is PopQuizDecision.Stalled)
        val reason = (d as PopQuizDecision.Stalled).reason
        assertTrue(reason.contains("播放"))
        assertFalse(reason.contains("弹题"))
        assertFalse("卡住不是需要人工", out.result.needsHuman)
    }

    @Test
    fun stalledWithBridgeConfiguredStillNotCalledQuiz() {
        val out = PopQuizPolicy.decide(
            configured, emptyList(), emptyList(),
            timedOut = false, stalledRounds = PopQuizPolicy.STALL_ROUNDS
        )
        val d = out.decision
        assertTrue(d is PopQuizDecision.Stalled)
        assertFalse((d as PopQuizDecision.Stalled).reason.contains("弹题"))
    }

    @Test
    fun readingRealQuestionsIsTheOnlyWayToSayQuiz() {
        val out = PopQuizPolicy.decide(configured, listOf(q), listOf(""), timedOut = true, stalledRounds = 9)
        val d = out.decision
        assertTrue("真读到题才能说弹题", d is PopQuizDecision.GiveUp)
        assertTrue((d as PopQuizDecision.GiveUp).reason.contains("弹题"))
    }

    @Test
    fun noBridgeAndProgressMovingKeepsPlaying() {
        val out = PopQuizPolicy.decide(
            PopQuizBridge.NONE, emptyList(), emptyList(), timedOut = false, stalledRounds = 0
        )
        assertTrue(out.decision is PopQuizDecision.Continue)
    }

    @Test
    fun parsesCommonResponseShapes() {
        val a = ConfiguredPopQuizBridge.parseQuestions(
            """{"questions":[{"questionId":"12","type":0,"stem":"下列说法","options":[{"content":"甲"},{"content":"乙"}]}]}"""
        )
        assertEquals(1, a.size)
        assertEquals("12", a[0].id)
        assertEquals("下列说法", a[0].stem)
        assertEquals(listOf("甲", "乙"), a[0].options)

        val b = ConfiguredPopQuizBridge.parseQuestions("""[{"id":"3","title":"1+1","type":"1"}]""")
        assertEquals(1, b.size)
        assertEquals("3", b[0].id)
        assertEquals(1, b[0].type)

        assertTrue("乱七八糟的返回不能抛异常", ConfiguredPopQuizBridge.parseQuestions("<html>login</html>").isEmpty())
    }
}
