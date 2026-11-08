package com.xxt.sign.work

import java.text.Normalizer

/**
 * 字体反解。
 *
 * 页面的密文字体是「思源黑体改了 glyf 名」：字形本身没变，只是 cmap 把码位指到别的字形上
 * （`uni4E0B` → `uni5DD5`）。所以只能把字画出来，和参考字体画出来的候选字比相似度。
 *
 * 产品级要求（真机踩过坑）：
 *  - **单一参考字体不够**：有的卷子字体根本不是思源黑体同族，对着黑体比只有 0.73–0.79。
 *    所以同时用多套参考（系统黑体 / 系统宋体 / assets 里放的字体），
 *    对每个字都算一遍，再按**整卷中位数**选一套胜出的参考字体，一份卷子不混用。
 *  - **阈值按卷自适应**：用胜出字体下所有字的相似度中位数减一个安全边际，
 *    只有明显离群的字才留空；固定阈值只作为兜底（字太少或字迹特别好时）。
 *  - 整卷中位数都低于 0.6 时不硬答，直接报「这卷字体无法识别」。
 *
 * 渲染交给 [GlyphSource] / [ReferenceFont]，Android 上用 Canvas 实现；
 * 单测注入假实现，整块逻辑不依赖 Android 也不联网。
 */
interface GlyphSource {
    /** 用密文字体渲染一个字（字体缺字时返回 null） */
    fun renderSecret(ch: Char, size: Int): FloatArray?
}

/** 一套参考字体：名字用于日志/UI，render 返回归一化特征向量 */
class ReferenceFont(
    val name: String,
    val render: (Char, Int) -> FloatArray?
)

/** 常用字表：GB2312 一级汉字 3755 个 + ASCII + 中文标点 */
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
    /** 多套参考字体，按整卷中位数选一套胜出 */
    private val references: List<ReferenceFont>,
    private val size: Int = 16,
    /** 固定阈值兜底（字太少或自适应结果更宽时使用） */
    private val fixedThreshold: Float = 0.86f,
    private val candidates: CharArray = CommonChars.CANDIDATES,
    /** 自适应阈值 = 中位数 - margin */
    private val margin: Float = 0.08f,
    /** 整卷中位数低于它 → 字体无法识别 */
    private val unreadableMedian: Float = 0.6f
) {

    /** 一个字的结果：解出的字 + 相似度 */
    data class Hit(val char: Char?, val score: Float)

    /**
     * 整卷的字体统计，日志/UI 直接用。
     * [unreadable] 为 true 时不要答题。
     */
    data class Stats(
        val reference: String,
        val mean: Float,
        val median: Float,
        val min: Float,
        val weak: Int,
        val total: Int,
        val threshold: Float,
        val unreadable: Boolean
    )

    /** 整段文本：weak 是没把握的字，reason 是给日志用的人话 */
    data class Decoded(
        val text: String,
        val weak: List<Char>,
        val minScore: Float,
        val reason: String = ""
    ) {
        val confident: Boolean get() = weak.isEmpty()
    }

    private data class CharScore(val char: Char?, val score: Float)

    private var winner: ReferenceFont? = null
    private var threshold: Float = fixedThreshold
    private val mapping = HashMap<Char, Hit>()
    private var preparedChars: Set<Char> = emptySet()

    var stats: Stats? = null
        private set

    /**
     * 整卷预处理：把所有会被反解的文本传进来，选参考字体、算自适应阈值、建映射。
     * 必须在 [decode] 之前调一次，否则 decode 只能逐字现场算、拿不到整卷统计。
     */
    fun prepare(texts: List<String>): Stats? {
        val chars = texts.asSequence()
            .flatMap { it.asSequence() }
            .filter { isDecodable(it) }
            .distinct()
            .toList()
        if (chars.isEmpty()) return null

        val pool = candidates.toList()
        // 每套参考字体都把候选字渲染一遍（懒加载，一份卷子一次）
        val vectorsByRef: List<Pair<ReferenceFont, List<Pair<Char, FloatArray>>>> =
            references.map { ref ->
                ref to pool.mapNotNull { c ->
                    runCatching { ref.render(c, size) }.getOrNull()?.let { c to it }
                }
            }.filter { it.second.isNotEmpty() }

        if (vectorsByRef.isEmpty()) return null

        // 每个字在每套参考字体下的最佳匹配
        val perChar = HashMap<Char, List<Pair<ReferenceFont, CharScore>>>()
        for (ch in chars) {
            val probe = runCatching { source.renderSecret(ch, size) }.getOrNull() ?: continue
            val scores = vectorsByRef.map { (ref, pool2) ->
                var bestChar: Char? = null
                var best = -1f
                for ((c, v) in pool2) {
                    val s = cosine(probe, v)
                    if (s > best) {
                        best = s
                        bestChar = c
                    }
                }
                ref to CharScore(bestChar, best.coerceAtLeast(0f))
            }
            perChar[ch] = scores
        }
        if (perChar.isEmpty()) return null

        // 按「整卷中位数」选胜出的参考字体，避免同一卷混用不同参考
        val byRef = vectorsByRef.map { it.first }
        var bestRef = byRef.first()
        var bestMedian = -1f
        for (ref in byRef) {
            val scores = perChar.values.mapNotNull { list ->
                list.firstOrNull { it.first == ref }?.second?.score
            }
            val m = median(scores)
            if (m > bestMedian) {
                bestMedian = m
                bestRef = ref
            }
        }

        val chosen = perChar.mapNotNull { (ch, list) ->
            list.firstOrNull { it.first == bestRef }?.let { ch to it.second }
        }.toMap()

        val allScores = chosen.values.map { it.score }
        val med = median(allScores)
        val mean = allScores.average().toFloat()
        val minScore = allScores.minOrNull() ?: 0f
        val unreadable = med < unreadableMedian

        // 自适应阈值：中位数减安全边际，但不超过固定阈值（固定阈值是兜底上限）
        val adaptive = (med - margin).coerceIn(0.5f, fixedThreshold)
        val effective = if (allScores.size < 3) fixedThreshold else adaptive

        winner = bestRef
        threshold = effective
        preparedChars = chosen.keys
        mapping.clear()
        var weak = 0
        for ((ch, cs) in chosen) {
            val ok = !unreadable && cs.score >= effective && cs.char != null
            mapping[ch] = Hit(if (ok) normalize(cs.char!!) else null, cs.score)
            if (!ok) weak++
        }

        val s = Stats(
            reference = bestRef.name,
            mean = mean,
            median = med,
            min = minScore,
            weak = weak,
            total = chosen.size,
            threshold = effective,
            unreadable = unreadable
        )
        stats = s
        return s
    }

    fun lookup(ch: Char): Hit {
        mapping[ch]?.let { return it }
        val hit = computeOnDemand(ch)
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
        val reason = when {
            weak.isEmpty() -> ""
            stats?.unreadable == true -> "这卷字体无法识别"
            else -> buildString {
                append("有 ").append(weak.distinct().size).append(" 个字认不出来（")
                append(weak.distinct().take(6).joinToString("")).append("），怕答错，先留着")
            }
        }
        return Decoded(sb.toString(), weak, min, reason)
    }

    /** prepare 没覆盖到的字：用胜出字体现场算一次（正常情况下不会走到） */
    private fun computeOnDemand(ch: Char): Hit {
        if (!isDecodable(ch)) return Hit(ch, 1f)
        val ref = winner ?: references.firstOrNull() ?: return Hit(null, 0f)
        val probe = runCatching { source.renderSecret(ch, size) }.getOrNull() ?: return Hit(null, 0f)
        var bestChar: Char? = null
        var best = -1f
        for (c in candidates) {
            val v = runCatching { ref.render(c, size) }.getOrNull() ?: continue
            val sc = cosine(probe, v)
            if (sc > best) {
                best = sc
                bestChar = c
            }
        }
        if (bestChar == null || best < threshold) return Hit(null, best.coerceAtLeast(0f))
        return Hit(normalize(bestChar), best)
    }

    companion object {

        /**
         * 只有汉字会被做字体反解（页面只替换 CJK 表意文字）。
         * 字母、数字、中英文标点、空白一律原样放过。
         */
        fun isDecodable(ch: Char): Boolean {
            val c = ch.code
            return c in 0x2F00..0x2FD5 || // 康熙部首
                c in 0x3400..0x4DBF || // 扩展 A
                c in 0x4E00..0x9FFF || // 基本区
                c in 0xF900..0xFAFF    // 兼容表意
        }

        /** 康熙部首区（U+2F00–U+2FD5）归一化回 CJK；NFKC 自带兼容分解 */
        fun normalize(ch: Char): Char {
            if (ch.code !in 0x2F00..0x2FD5) return ch
            val s = Normalizer.normalize(ch.toString(), Normalizer.Form.NFKC)
            val first = s.firstOrNull() ?: return ch
            if (first.code in 0x2F00..0x2FD5) return (0x4E00 + (first.code - 0x2F00)).toChar()
            return first
        }

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

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }
    }
}
