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
