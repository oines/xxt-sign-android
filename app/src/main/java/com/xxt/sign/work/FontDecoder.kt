package com.xxt.sign.work

import java.text.Normalizer

/**
 * 字体反解。
 *
 * 页面的密文字体是「思源黑体改了 glyf 名」：字形本身没变，只是 cmap 把常用的码位
 * 指到别的字形上（所以 `uni4E0B` 变成了 `uni5DD5`）。因此没法从字体表里直接读出原文，
 * 只能把字**画出来**再和参考字体画出来的候选字比相似度。
 *
 * 流程：
 *  1. 用密文字体渲染一个密文字 → 归一化灰度特征向量
 *  2. 用系统思源黑体渲染候选字（GB2312 一级常用字 + ASCII + 标点）→ 同一套特征
 *  3. 余弦相似度取最近邻，低于阈值就标「待复核」，**不采信**
 *
 * 渲染交给 [GlyphSource]，Android 上用 Canvas 实现；单测里注入假实现，
 * 这样这整块逻辑都是纯 JVM 的，不依赖 Android 也不联网。
 */
interface GlyphSource {
    /** 用密文字体渲染一个字（可能因为字体缺字返回 null） */
    fun renderSecret(ch: Char, size: Int): FloatArray?

    /** 用参考字体（系统思源黑体）渲染一个字 */
    fun renderReference(ch: Char, size: Int): FloatArray?
}

/** 常用字表：GB2312 一级汉字 3755 个 + ASCII + 中文标点。系统自带字符集，不用额外资源文件 */
object CommonChars {

    val CANDIDATES: CharArray by lazy { build() }

    private fun build(): CharArray {
        val sb = StringBuilder(4096)
        for (c in '0'..'9') sb.append(c)
        for (c in 'a'..'z') sb.append(c)
        for (c in 'A'..'Z') sb.append(c)
        sb.append("，。、；：？！“”‘’（）《》〈〉【】…—·-+×÷=%")

        // GB2312 一级汉字区：高字节 B0-D7，低字节 A1-FE
        for (hi in 0xB0..0xD7) {
            for (lo in 0xA1..0xFE) {
                val s = runCatching {
                    String(byteArrayOf(hi.toByte(), lo.toByte()), charset("GB2312"))
                }.getOrNull()
                if (s != null && s.length == 1) sb.append(s[0])
            }
        }
        return sb.toString().toCharArray().distinct().toCharArray()
    }
}

class FontDecoder(
    private val source: GlyphSource,
    /** 特征图边长；16×16 已经够区分常用字，再大只是更慢 */
    private val size: Int = 16,
    /** 低于这个相似度判为「待复核」 */
    private val threshold: Float = 0.86f,
    private val candidates: CharArray = CommonChars.CANDIDATES
) {

    /** 一个字的结果：解出的字 + 相似度；不确定时 char 为 null */
    data class Hit(val char: Char?, val score: Float)

    /** 整段文本的结果；[weak] 是没把握的字（日志里要显式提示，不要当真） */
    data class Decoded(val text: String, val weak: List<Char>, val minScore: Float) {
        val confident: Boolean get() = weak.isEmpty()
    }

    private val mapping = HashMap<Char, Hit>()
    private var candidateVectors: List<Pair<Char, FloatArray>>? = null

    fun lookup(ch: Char): Hit {
        mapping[ch]?.let { return it }
        val hit = compute(ch)
        mapping[ch] = hit
        return hit
    }

    fun decode(input: String): Decoded {
        if (input.isEmpty()) return Decoded("", emptyList(), 1f)
        val sb = StringBuilder(input.length)
        val weak = mutableListOf<Char>()
        var min = 1f
        for (ch in input) {
            val hit = lookup(ch)
            sb.append(hit.char ?: ch)
            if (hit.score < min) min = hit.score
            if (hit.char == null && isDecodable(ch)) weak.add(ch)
        }
        return Decoded(sb.toString(), weak, min)
    }

    private fun compute(ch: Char): Hit {
        // ASCII / 标点 / 空白不参与反解
        if (!isDecodable(ch)) return Hit(ch, 1f)

        val probe = runCatching { source.renderSecret(ch, size) }.getOrNull()
            ?: return Hit(null, 0f)

        val pool = candidateVectors ?: buildCandidates().also { candidateVectors = it }
        if (pool.isEmpty()) return Hit(null, 0f)

        var bestChar: Char? = null
        var best = -1f
        for ((c, v) in pool) {
            val s = cosine(probe, v)
            if (s > best) {
                best = s
                bestChar = c
            }
        }
        if (bestChar == null || best < threshold) return Hit(null, best.coerceAtLeast(0f))

        return Hit(normalize(bestChar), best)
    }

    private fun buildCandidates(): List<Pair<Char, FloatArray>> {
        val out = ArrayList<Pair<Char, FloatArray>>(candidates.size)
        for (c in candidates) {
            val v = runCatching { source.renderReference(c, size) }.getOrNull() ?: continue
            out.add(c to v)
        }
        return out
    }

    companion object {

        /**
         * 只有汉字会被做字体反解（实测页面只替换 CJK 表意文字）。
         * 字母、数字、中英文标点、空白一律原样放过，避免把「，。」这类符号误判成密文。
         */
        fun isDecodable(ch: Char): Boolean {
            val c = ch.code
            return c in 0x2F00..0x2FD5 || // 康熙部首
                c in 0x3400..0x4DBF || // 扩展 A
                c in 0x4E00..0x9FFF || // 基本区
                c in 0xF900..0xFAFF    // 兼容表意
        }

        /**
         * 康熙部首区（U+2F00–U+2FD5）里的字要归一化回 CJK。
         * NFKC 就带兼容分解（⼀ → 一），不用自己维护 214 个部首的映射表。
         */
        fun normalize(ch: Char): Char {
            if (ch.code !in 0x2F00..0x2FD5) return ch
            val s = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC)
            val first = s.firstOrNull() ?: return ch
            // 万一 NFKC 没分解（平台实现差异），再按部首区的偏移兜一次底
            if (first.code in 0x2F00..0x2FD5) return (0x4E00 + (first.code - 0x2F00)).toChar()
            return first
        }

        /** 两个特征向量的余弦相似度 */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na <= 0f || nb <= 0f) return 0f
            return dot / (Math.sqrt(na.toDouble()) * Math.sqrt(nb.toDouble())).toFloat()
        }
    }
}
