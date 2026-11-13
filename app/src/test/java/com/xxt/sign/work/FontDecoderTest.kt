package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * 字体反解：多参考字体选择 + 按卷自适应阈值（不依赖 Android、不联网）。
 *
 * 构造方式：密文字的特征向量放在角度 α 上，候选字放在 0° 上，
 * 余弦相似度就是 cos α，于是可以精确指定「这个字在这套参考字体下得几分」。
 */
class FontDecoderTest {

    private class FakeSource(private val probes: Map<Char, FloatArray>) : GlyphSource {
        override fun renderSecret(ch: Char, size: Int): FloatArray? = probes[ch]
    }

    private fun unit(angleDeg: Double): FloatArray {
        val r = Math.toRadians(angleDeg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
    }

    /** 让任意候选字在这套参考字体下都得 [score] 分 */
    private fun refAt(name: String, score: Float) = ReferenceFont(name) { _, _ ->
        unit(Math.toDegrees(acos(score.toDouble())))
    }

    /**
     * 带「次优」的参考字体：把 [top] 给候选 A、[second] 给其余候选。
     * 用来精确控制「最优-次优间距」。
     */
    private fun refAt(name: String, top: Float, second: Float) = ReferenceFont(name) { c, _ ->
        unit(Math.toDegrees(acos((if (c == 'A') top else second).toDouble())))
    }

    /**
     * 按角度摆候选：A 在 [angleA]°、B 在 [angleB]°。
     *
     * 密文字向量放在 p° 时，A 得 cos(p-angleA)、B 得 cos(p-angleB)，
     * 于是可以精确造出「某个字分数低但领先次优很多」这种真机上出现过的组合。
     * （[refAt] 只能让所有密文字得同一组分，做不出分数有高有低的整卷。）
     */
    private fun refAngled(name: String, angleA: Double, angleB: Double) = ReferenceFont(name) { c, _ ->
        unit(if (c == 'A') angleA else angleB)
    }

    /** 两个候选字的解码器：多数断言需要 A/B 两个字才能谈「间距」 */
    private fun decoder2(
        probes: Map<Char, FloatArray>,
        refs: List<ReferenceFont>,
        fixed: Float = 0.86f
    ) = FontDecoder(
        source = FakeSource(probes),
        references = refs,
        size = 2,
        fixedThreshold = fixed,
        candidates = charArrayOf('A', 'B'),
        margin = 0.08f,
        unreadableMedian = 0.6f
    )

    private fun probeFor(score: Float): FloatArray =
        floatArrayOf(score, kotlin.math.sqrt((1f - score * score).coerceAtLeast(0f)))

    private fun decoder(
        probes: Map<Char, FloatArray>,
        refs: List<ReferenceFont> = listOf(refAt("黑体", 1f)),
        fixed: Float = 0.86f
    ) = FontDecoder(
        source = FakeSource(probes),
        references = refs,
        size = 2,
        fixedThreshold = fixed,
        candidates = charArrayOf('A'),
        margin = 0.08f,
        unreadableMedian = 0.6f
    )

    /** 两份参考字体，只有 B 和密文字形对得上 → 自动选 B */
    @Test
    fun picksTheReferenceFontThatMatchesThePaper() {
        val probes = mapOf('丂' to unit(0.0), '丄' to unit(0.0), '丅' to unit(0.0))
        val refs = listOf(refAt("黑体", 0.52f), refAt("宋体", 0.97f))
        val d = decoder(probes, refs)
        val stats = d.prepare(listOf("丂丄丅"))
        assertNotNull(stats)
        assertEquals("宋体", stats!!.reference)
        assertTrue(stats.mean > 0.9f)
    }

    /** 整卷 0.76–0.80：按中位数减边际，0.86 的固定阈值不能一刀切 */
    @Test
    fun adaptiveThresholdKeepsAConsistentMediumQualityPaper() {
        val probes = mapOf(
            '丂' to probeFor(0.78f),
            '丄' to probeFor(0.80f),
            '丅' to probeFor(0.76f),
            '丆' to probeFor(0.79f),
            '丩' to probeFor(0.77f)
        )
        val d = decoder(probes)
        val stats = d.prepare(listOf("丂丄丅丆丩"))!!
        assertTrue("阈值应当低于 0.86，实际 ${stats.threshold}", stats.threshold < 0.86f)
        assertFalse(stats.unreadable)
        assertEquals(0, stats.weak)

        val decoded = d.decode("丂丄丅")
        assertTrue(decoded.confident)
        assertEquals("AAA", decoded.text)
    }

    /** 明显离群的字才留空 */
    @Test
    fun onlyOutliersAreLeftBlank() {
        val probes = mapOf(
            '丂' to probeFor(0.80f),
            '丄' to probeFor(0.79f),
            '丅' to probeFor(0.78f),
            '丆' to probeFor(0.45f) // 离群
        )
        val d = decoder(probes)
        val stats = d.prepare(listOf("丂丄丅丆"))!!
        assertEquals(1, stats.weak)
        val decoded = d.decode("丂丆")
        assertFalse(decoded.confident)
        assertEquals(listOf('丆'), decoded.weak)
        assertTrue(decoded.reason.contains("认不出来"))
    }

    /** 整卷中位数 0.45：明确报字体无法识别，不硬答 */
    @Test
    fun veryLowMedianMeansUnreadable() {
        val probes = mapOf('丂' to probeFor(0.45f), '丄' to probeFor(0.44f), '丅' to probeFor(0.46f))
        val d = decoder(probes)
        val stats = d.prepare(listOf("丂丄丅"))!!
        assertTrue(stats.unreadable)
        val decoded = d.decode("丂丄丅")
        assertFalse(decoded.confident)
        assertEquals("这卷字体无法识别", decoded.reason)
        assertNull(decoded.text.firstOrNull()?.takeIf { it == 'A' })
    }

    /** 字太少时退回固定阈值兜底 */
    @Test
    fun fewCharsFallBackToFixedThreshold() {
        val probes = mapOf('丂' to probeFor(0.78f))
        val d = decoder(probes)
        val stats = d.prepare(listOf("丂"))!!
        assertEquals(0.86f, stats.threshold, 0.0001f)
        assertEquals(1, stats.weak)
    }

    /** 同一套卷子第二次 decode 不会重复计算（走已建好的映射） */
    @Test
    fun decodeUsesPreparedMapping() {
        var calls = 0
        val source = object : GlyphSource {
            override fun renderSecret(ch: Char, size: Int): FloatArray? {
                calls++
                return floatArrayOf(1f, 0f)
            }
        }
        val d = FontDecoder(
            source = source,
            references = listOf(refAt("黑体", 1f)),
            size = 2,
            candidates = charArrayOf('A')
        )
        d.prepare(listOf("丂丄丅"))
        val before = calls
        d.prepare(listOf("丂丄丅"))
        d.decode("丂丄丅")
        // prepare 会重新算一遍（3 次），decode 不再触发渲染
        assertEquals(before + 3, calls)
    }

    /** 设备上有几套参考字体、每字赢了哪套，都要能在统计里看到（真机排障用） */
    @Test
    fun statsReportReferenceInventory() {
        val probes = mapOf('丂' to unit(0.0), '丄' to unit(0.0), '丅' to unit(0.0))
        val refs = listOf(refAt("系统黑体", 0.90f), refAt("系统宋体", 0.95f))
        val d = decoder(probes, refs)
        val stats = d.prepare(listOf("丂丄丅"))!!

        assertEquals(2, stats.referenceCount)
        assertEquals(listOf("系统黑体", "系统宋体"), stats.referenceNames)
        // 每字在各字体里的最高分都是 0.95（宋体）→ 三个字都由宋体贡献
        assertEquals(3, stats.perRefHits["系统宋体"])
        assertEquals(1, stats.perRefHits.size)
        assertEquals(0.95f, stats.mean, 0.01f)
        // 候选表里只有一个字 A，没有第二名候选 → gap 无定义，不参与统计
        assertEquals(0f, stats.gapMedian, 0.01f)

        val line = stats.report()
        assertTrue(line.contains("参考字体 2 套"))
        assertTrue(line.contains("系统黑体"))
        assertTrue(line.contains("最优-次优差"))
        assertTrue(line.contains("认不出 0/3"))
    }

    /**
     * 两套字体都认成同一个字、只差分毫 → **不是歧义**，恰恰是更强的证据。
     *
     * 这是真机上误报「认不出」的主因：系统黑体猜「属」0.95、思源黑体也猜「属」0.94，
     * 老逻辑拿这两个数相减得到 0.01，判成「分不开」。
     */
    @Test
    fun sameAnswerFromTwoFontsIsNotAmbiguous() {
        val probes = mapOf('丂' to unit(0.0), '丄' to unit(0.0))
        // 两套字体都把 A 认成 1.0、B 认成 0（意见一致）
        val refs = listOf(
            refAngled("系统黑体", 0.0, 90.0),
            refAngled("系统宋体", 0.0, 90.0)
        )
        val stats = FontDecoder(
            source = FakeSource(probes),
            references = refs,
            size = 2,
            fixedThreshold = 0.86f,
            candidates = charArrayOf('A', 'B'),
            margin = 0.08f,
            unreadableMedian = 0.6f
        ).prepare(listOf("丂丄"))!!
        // 候选 A 得分 1.0、候选 B 得分 0 → 间距 1.0，完全分得开
        assertTrue("两套字体意见一致不该有歧义，gap=${stats.gapMedian}", stats.gapMedian > 0.9f)
        assertEquals(0, stats.gapTight)
        assertEquals(0, stats.weak)
    }

    /** 候选字之间真的分不开（A 和 B 同分）→ 才算高危 */
    @Test
    fun gapTightCountsAmbiguousChars() {
        val probes = mapOf('丂' to unit(0.0), '丄' to unit(0.0))
        // A / B 完全重合：候选之间没有差距
        val refs = listOf(refAngled("系统黑体", 0.0, 0.0))
        val stats = FontDecoder(
            source = FakeSource(probes),
            references = refs,
            size = 2,
            fixedThreshold = 0.86f,
            candidates = charArrayOf('A', 'B'),
            margin = 0.08f,
            unreadableMedian = 0.6f
        ).prepare(listOf("丂丄"))!!
        assertEquals(2, stats.gapTight)
        assertTrue(stats.report().contains("<0.02 的 2 字"))
    }

    /**
     * 真机回归：整卷平均 0.92、题目全对，但旧逻辑按阈值报了 11 个字「存疑」。
     *
     * 这类字的特点是「绝对分数不高但明显领先次优」—— 它其实认得出来，
     * 分数低只是因为这个字笔画简单、任何字体画出来都差不多。不能再报警。
     */
    @Test
    fun lowScoreButClearWinnerIsNotReportedAsWeak() {
        // 三个高分字把中位数抬到 0.945，自适应阈值 0.865；
        // 第四个字只有 0.70（低于阈值），但次优只有 0（对手在 180° 反向）→ 有把握
        val probes = mapOf(
            '丂' to probeFor(0.99f),
            '丄' to probeFor(0.99f),
            '丅' to probeFor(0.99f),
            '丆' to probeFor(0.70f)
        )
        val d = FontDecoder(
            source = FakeSource(probes),
            references = listOf(refAngled("黑体", 0.0, 180.0)),
            size = 2,
            fixedThreshold = 0.95f,
            candidates = charArrayOf('A', 'B'),
            margin = 0.08f,
            unreadableMedian = 0.6f
        )
        val stats = d.prepare(listOf("丂丄丅丆"))!!
        assertEquals("分数低但领先明显，不该报认不出", 0, stats.weak)
        assertEquals("但要单独记账", 1, stats.uncertain)
        assertTrue("这个字应当被解出来", d.decode("丆").confident)
        assertTrue(stats.report().contains("认不出 0/4"))
        assertTrue(stats.report().contains("另 1 字分数偏低但已确认"))
    }

    /** 分数低、又分不出次优 → 真的认不出，必须留空 */
    @Test
    fun lowScoreAndNoClearWinnerStaysWeak() {
        val probes = mapOf(
            '丂' to probeFor(0.99f),
            '丄' to probeFor(0.99f),
            '丅' to probeFor(0.99f),
            '丆' to probeFor(0.70f)   // 低于阈值
        )
        // A / B 完全重合：第一名和第二名同分，gap = 0 → 分不出
        val d = FontDecoder(
            source = FakeSource(probes),
            references = listOf(refAngled("黑体", 0.0, 0.0)),
            size = 2,
            fixedThreshold = 0.95f,
            candidates = charArrayOf('A', 'B'),
            margin = 0.08f
        )
        val stats = d.prepare(listOf("丂丄丅丆"))!!
        assertEquals(1, stats.weak)
        assertFalse(d.decode("丆").confident)
    }

    @Test
    fun kangxiRadicalIsNormalizedToCjk() {
        assertEquals('\u4E00', FontDecoder.normalize('\u2F00'))
        assertEquals('好', FontDecoder.normalize('好'))
    }

    @Test
    fun cosineAndMedianBasics() {
        assertEquals(1f, FontDecoder.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 0.0001f)
        assertEquals(0f, FontDecoder.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.0001f)
        // 偶数个取中间两个的平均
        assertEquals(0.35f, FontDecoder.median(listOf(0.2f, 0.5f, 0.9f, 0.1f)), 0.0001f)
        // 奇数个取正中
        assertEquals(0.4f, FontDecoder.median(listOf(0.2f, 0.4f, 0.6f)), 0.0001f)
    }
}
