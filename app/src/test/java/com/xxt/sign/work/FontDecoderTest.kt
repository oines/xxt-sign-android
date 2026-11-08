package com.xxt.sign.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字体反解的核心逻辑（不依赖 Android、不联网）：给假渲染器喂确定性的特征向量 */
class FontDecoderTest {

    private class FakeSource(
        private val secret: Map<Char, FloatArray>,
        private val reference: Map<Char, FloatArray>
    ) : GlyphSource {
        override fun renderSecret(ch: Char, size: Int): FloatArray? = secret[ch]
        override fun renderReference(ch: Char, size: Int): FloatArray? = reference[ch]
    }

    private val vA = floatArrayOf(1f, 0f, 0f, 0f)
    private val vB = floatArrayOf(0f, 1f, 0f, 0f)
    private val vNoise = floatArrayOf(0.45f, 0.45f, 0.45f, 0.45f)

    private fun decoder(secret: Map<Char, FloatArray>) = FontDecoder(
        source = FakeSource(secret, mapOf('A' to vA, 'B' to vB)),
        size = 2,
        threshold = 0.86f,
        candidates = charArrayOf('A', 'B')
    )

    @Test
    fun picksNearestCandidate() {
        val d = decoder(mapOf('丂' to vA, '丄' to vB))
        assertEquals('A', d.lookup('丂').char)
        assertEquals('B', d.lookup('丄').char)
        assertTrue(d.lookup('丂').score > 0.99f)
    }

    @Test
    fun lowSimilarityIsNotTrusted() {
        val d = decoder(mapOf('丂' to vNoise))
        assertNull(d.lookup('丂').char)
        val decoded = d.decode("丂")
        assertFalse(decoded.confident)
        assertEquals(listOf('丂'), decoded.weak)
    }

    @Test
    fun asciiAndPunctuationPassThrough() {
        val d = decoder(emptyMap())
        val decoded = d.decode("A-1 ，。")
        assertEquals("A-1 ，。", decoded.text)
        assertTrue(decoded.confident)
    }

    @Test
    fun missingGlyphIsWeakRatherThanWrong() {
        val d = decoder(emptyMap())
        val decoded = d.decode("丂")
        assertFalse(decoded.confident)
        assertEquals("丂", decoded.text)
    }

    @Test
    fun kangxiRadicalIsNormalizedToCjk() {
        // U+2F00 ⼀ 应该归一化成 U+4E00 一
        assertEquals('\u4E00', FontDecoder.normalize('\u2F00'))
        assertEquals('好', FontDecoder.normalize('好'))
    }

    @Test
    fun cosineBasics() {
        assertEquals(1f, FontDecoder.cosine(vA, vA), 0.0001f)
        assertEquals(0f, FontDecoder.cosine(vA, vB), 0.0001f)
    }

    @Test
    fun cachesPerCharAndBuildsCandidatePoolOnce() {
        var calls = 0
        val source = object : GlyphSource {
            override fun renderSecret(ch: Char, size: Int): FloatArray? {
                calls++
                return vA
            }

            override fun renderReference(ch: Char, size: Int): FloatArray? = vA
        }
        val d = FontDecoder(source, size = 2, candidates = charArrayOf('A'))
        d.lookup('丂')
        d.lookup('丂')
        d.decode("丂丂")
        // 候选池用 renderReference 建（不算在这里），密文字只有第一次会真渲染
        assertEquals(1, calls)
    }
}
