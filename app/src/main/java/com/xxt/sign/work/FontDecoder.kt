package com.xxt.sign.work

import java.text.Normalizer

/**
 * 字体反解。
 *
 * 页面的密文字体是「思源黑体改了 glyf 名」：字形本身没变，只是 cmap 把码位指到别的字形上
 * （`uni4E0B` → `uni5DD5`）。所以只能把字画出来，和参考字体画出来的候选字比相似度。
 *
 * 产品级要求（真机踩过坑）：
 *  - **单一参考字体不够**：有的卷子字体根本不是思源黑体同族，对着黑体比只有 0.73–0.79，
 *    解出来就是「吉年夹，中圆兵户党」这种部分字错的句子。所以把设备上能拿到的
 *    中文字体（系统黑体/宋体/等宽 + /system/fonts 里的 NotoSansCJK/NotoSerifCJK +
 *    assets/fonts）全部作为参考，**逐字取最优匹配**，并记录每个字是哪套字体赢的。
 *  - **阈值按卷自适应**：用胜出字体下所有字的相似度中位数减一个安全边际，
 *    只有明显离群的字才留空；固定阈值只作为兜底（字太少或字迹特别好时）。
 *    另外把「最优-次优间距」也统计出来：间距极小说明这个字本来就分不开，
 *    这类字单独计数（[Stats.gapTight]），先只记日志，等分布确认后再决定是否据此留空。
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
    /** 多套参考字体，逐字取最优（[perCharBest] = false 时退回「整卷选一套」的老逻辑） */
    private val references: List<ReferenceFont>,
    private val size: Int = 16,
    /** 固定阈值兜底（字太少或自适应结果更宽时使用） */
    private val fixedThreshold: Float = 0.86f,
    private val candidates: CharArray = CommonChars.CANDIDATES,
    /** 自适应阈值 = 中位数 - margin */
    private val margin: Float = 0.08f,
    /** 整卷中位数低于它 → 字体无法识别 */
    private val unreadableMedian: Float = 0.6f,
    /** true = 每个字在各套参考字体里取最优（原型验证过的做法） */
    private val perCharBest: Boolean = true,
    /**
     * 换字体需要领先多少分。低于这个差距就认定为「险胜」，仍用整卷基准字体。
     * 防止某套字体靠豆腐块/笔画巧合抢走正确结果（真机踩过，见 [prepare] 里注释）。
     */
    private val refSwitchMargin: Float = 0.02f,
    /**
     * 最优领先次优这么多 → 就算绝对分数不高也算「有把握」。
     * 用来区分「字体不像」和「这个字本来就笔画少、任何字体都像」。
     */
    private val confidentGap: Float = 0.06f,
    /** 「有把握」时的分数下限；低于它一律不认（防止两个候选都很像时瞎猜） */
    private val lowScoreFloor: Float = 0.55f
) {

    /** 一个字的结果：解出的字 + 相似度 */
    data class Hit(val char: Char?, val score: Float)

    /** 一个字在各套参考字体里胜出的那套 */
    private data class Pick(
        val char: Char?,
        val score: Float,
        /** 次优候选（同一套里的第二名 / 别的字体里的最高分，取较大的那个） */
        val second: Float,
        /**
         * [second] 是否真实存在。
         * 候选表只有一个字、参考字体也只有一套时，根本没有「次优」可比，
         * 这时不能把 gap 当成「领先很多」—— 那是没有证据，不是有把握。
         */
        val secondKnown: Boolean,
        val ref: String
    )

    /**
     * 整卷的字体统计，日志/UI 直接用。
     * [unreadable] 为 true 时不要答题。
     */
    data class Stats(
        /** 整卷胜出的那套（perCharBest 时是贡献字最多的那套） */
        val reference: String,
        /** 参与比对的参考字体套数 */
        val referenceCount: Int,
        /** 参与比对的参考字体名字 */
        val referenceNames: List<String>,
        /** 每个字赢家的分布：字体名 -> 字数 */
        val perRefHits: Map<String, Int>,
        val mean: Float,
        val median: Float,
        val min: Float,
        /** 每字最优相似度的 10/25/75/90 分位 */
        val p10: Float,
        val p25: Float,
        val p75: Float,
        val p90: Float,
        /** 最优-次优间距的中位数，越小越容易认错字 */
        val gapMedian: Float,
        /** 间距 < 0.02 的字数（这批就是「看着像所以猜错」的高危字） */
        val gapTight: Int,
        val weak: Int,
        /** 分数不高但明显领先次优、已放心采用的字数（只记账，不报警） */
        val uncertain: Int,
        /** 每套参考字体各自「认对」时的中位分（排障用：能看出哪套字体渲染出了问题） */
        val refMedians: Map<String, Float>,
        val total: Int,
        val threshold: Float,
        val unreadable: Boolean
    ) {
        /** 一行讲清楚：几套参考、谁胜出、间距多大、相似度分布、多少字存疑 */
        fun report(): String {
            val names = if (referenceNames.size > 6) {
                referenceNames.take(6).joinToString("、") + " 等 ${referenceNames.size} 套"
            } else {
                referenceNames.joinToString("、")
            }
            val hits = perRefHits.entries.sortedByDescending { it.value }.take(4)
                .joinToString(" / ") { "${it.key} ${it.value}" }
            return buildString {
                append("[字体] 参考字体 ").append(referenceCount).append(" 套（").append(names).append("）")
                append("｜胜出 ").append(reference)
                append("｜最优-次优差 ").append("%.2f".format(gapMedian))
                append("（<0.02 的 ").append(gapTight).append(" 字）")
                append("｜平均 ").append("%.2f".format(mean))
                append("｜中位 ").append("%.2f".format(median))
                append("｜最低 ").append("%.2f".format(min))
                append("（p10 ").append("%.2f".format(p10))
                append(" p90 ").append("%.2f".format(p90)).append("）")
                append("｜认不出 ").append(weak).append("/").append(total)
                if (uncertain > 0) append("（另 ").append(uncertain).append(" 字分数偏低但已确认）")
                append("｜阈值 ").append("%.2f".format(threshold))
                if (hits.isNotBlank()) append("｜逐字最优来源：").append(hits)
                if (refMedians.isNotEmpty()) {
                    append("｜各套中位：")
                    append(refMedians.entries.sortedByDescending { it.value }
                        .joinToString(" ") { "${it.key.take(12)} ${"%.3f".format(it.value)}" })
                }
            }
        }
    }

    /** 整段文本：weak 是没把握的字，reason 是给日志用的人话 */
    data class Decoded(
        val text: String,
        val weak: List<Char>,
        val minScore: Float,
        val reason: String = ""
    ) {
        val confident: Boolean get() = weak.isEmpty()
    }

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
        // 每套参考字体都把候选字渲染一遍（一份卷子一次）
        val vectorsByRef: List<Pair<ReferenceFont, List<Pair<Char, FloatArray>>>> =
            references.map { ref ->
                ref to pool.mapNotNull { c ->
                    runCatching { ref.render(c, size) }.getOrNull()?.let { c to it }
                }
            }.filter { it.second.isNotEmpty() }

        if (vectorsByRef.isEmpty()) return null

        // 每个字在每套参考字体下的**完整候选排序**。
        //
        // 关键：不是只留「这套字体的第一名」，而是把每套字体对每个候选字的打分都留着，
        // 因为判断「这个字认不认得出」必须看 **候选字之间** 的差距，而不是
        // 「这套字体的第一名」和「那套字体的第一名」的差距。
        //
        // 真机踩过：两套字体都认成「属」（0.95 / 0.94），差 0.01，
        // 老逻辑把这个差当成「次优」，于是把明明认对的字报成「存疑」。
        // 两套字体意见一致恰恰是**更强的证据**，不是歧义。
        val perCharScores = HashMap<Char, List<Pair<ReferenceFont, Map<Char, Float>>>>()
        for (ch in chars) {
            val probe = runCatching { source.renderSecret(ch, size) }.getOrNull() ?: continue
            val perRef = vectorsByRef.map { (ref, pool2) ->
                val map = HashMap<Char, Float>(pool2.size)
                for ((c, v) in pool2) map[c] = cosine(probe, v)
                ref to map
            }
            perCharScores[ch] = perRef
        }
        if (perCharScores.isEmpty()) return null

        val byRef = vectorsByRef.map { it.first }

        // 每套字体「把这个字认成它自己的第一名」时的分数中位数 → 用来挑基准字体
        val refMedian = byRef.map { ref ->
            ref to median(
                perCharScores.values.mapNotNull { list ->
                    list.firstOrNull { it.first == ref }?.second?.values?.maxOrNull()
                }
            )
        }.toMap()
        val bestRef = byRef.maxByOrNull { refMedian[it] ?: 0f } ?: byRef.first()

        // 逐字裁决：
        //
        // 1) 对每个**候选字**，取它在所有参考字体里的最高分（多字体投票取最好）；
        // 2) 按这个汇总分排序，第一名就是答案；
        // 3) 间距 = 第一名候选 − 第二名**候选字** 的汇总分。
        //
        // 这样「两套字体都认成属」只会让「属」的汇总分更高、间距更大，
        // 而不会像老逻辑那样凭空造出一个 0.01 的假歧义。
        data class Ranked(val char: Char, val score: Float, val ref: String)

        val picks: Map<Char, Pick> = perCharScores.mapValues { (_, perRef) ->
            // 汇总：候选字 -> (最高分, 该分来自哪套字体)
            val agg = LinkedHashMap<Char, Pair<Float, String>>()
            for ((ref, map) in perRef) {
                for ((c, s) in map) {
                    val cur = agg[c]
                    if (cur == null || s > cur.first) agg[c] = s to ref.name
                }
            }
            val ranked = agg.entries
                .map { Ranked(it.key, it.value.first, it.value.second) }
                .sortedByDescending { it.score }
            val first = ranked.firstOrNull()
            // 次优 = 第二名的**不同候选字**（同名候选不会重复出现，天然不同）
            val second = ranked.getOrNull(1)
            Pick(
                char = first?.char,
                score = first?.score ?: 0f,
                second = second?.score ?: -1f,
                secondKnown = second != null,
                ref = first?.ref ?: bestRef.name
            )
        }

        val allScores = picks.values.map { it.score }
        val med = median(allScores)
        val mean = allScores.average().toFloat()
        val minScore = allScores.minOrNull() ?: 0f
        val unreadable = med < unreadableMedian
        val sorted = allScores.sorted()
        fun pct(p: Float): Float =
            if (sorted.isEmpty()) 0f
            else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
        // 间距只统计「真的有两个候选可比」的字
        val knownGaps = picks.values.filter { it.secondKnown }
            .map { (it.score - it.second).coerceAtLeast(0f) }
        val gapMedian = median(knownGaps)
        val gapTight = knownGaps.count { it < 0.02f }

        // 自适应阈值：中位数减安全边际，但不超过固定阈值（固定阈值是兜底上限）
        val adaptive = (med - margin).coerceIn(0.5f, fixedThreshold)
        val effective = if (allScores.size < 3) fixedThreshold else adaptive
        winner = byRef.firstOrNull { it.name == bestRef.name } ?: bestRef
        threshold = effective
        preparedChars = picks.keys
        mapping.clear()
        var weak = 0
        var uncertain = 0
        for ((ch, pick) in picks) {
            // 「分数低」不等于「认错了」。
            //
            // 分数低有三种原因：①字体确实不是同一套；②这个字笔画太简单，任何字体都长得像；
            // ③参考字体和卷面字体有细微渲染差异。只有 ①③ 且**又分不出第二名**时才该报错。
            //
            // 真机踩过：整卷平均 0.92（题目全对），但阈值 0.83 仍把 11 个字标成「存疑」，
            // 用户看到「有 N 个字识别不确定」以为出错了，其实一个都没错。
            // 所以加一条：**只要最优明显领先次优（gap ≥ [confidentGap]），就算分数低也照样采用**。
            // 注意 secondKnown：没有次优可比时 gap 无意义，不能用它来「确认」。
            val gap = if (pick.secondKnown) (pick.score - pick.second).coerceAtLeast(0f) else 0f
            val clearWinner = pick.secondKnown &&
                gap >= confidentGap && pick.score >= lowScoreFloor
            val ok = !unreadable && pick.char != null && (pick.score >= effective || clearWinner)
            mapping[ch] = Hit(if (ok) normalize(pick.char!!) else null, pick.score)
            when {
                !ok -> weak++
                pick.score < effective -> uncertain++   // 分数不高但有把握，只记账不报警
            }
        }

        val perRefHits = picks.values.groupingBy { it.ref }.eachCount()
        val s = Stats(
            reference = perRefHits.maxByOrNull { it.value }?.key ?: bestRef.name,
            referenceCount = byRef.size,
            referenceNames = byRef.map { it.name },
            perRefHits = perRefHits,
            mean = mean,
            median = med,
            min = minScore,
            p10 = pct(0.1f),
            p25 = pct(0.25f),
            p75 = pct(0.75f),
            p90 = pct(0.9f),
            gapMedian = gapMedian,
            gapTight = gapTight,
            weak = weak,
            uncertain = uncertain,
            refMedians = refMedian.mapKeys { it.key.name },
            total = picks.size,
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
                append(weak.distinct().take(6).joinToString("")).append("）")
            }
        }
        return Decoded(sb.toString(), weak, min, reason)
    }

    /** prepare 没覆盖到的字：用胜出字体现场算一次（正常情况下不会走到） */
    private fun computeOnDemand(ch: Char): Hit {
        if (!isDecodable(ch)) return Hit(ch, 1f)
        val refs = if (references.isEmpty()) return Hit(null, 0f) else references
        val probe = runCatching { source.renderSecret(ch, size) }.getOrNull() ?: return Hit(null, 0f)
        var bestChar: Char? = null
        var best = -1f
        for (ref in refs) {
            for (c in candidates) {
                val v = runCatching { ref.render(c, size) }.getOrNull() ?: continue
                val sc = cosine(probe, v)
                if (sc > best) {
                    best = sc
                    bestChar = c
                }
            }
        }
        if (bestChar == null || best < threshold) return Hit(null, best.coerceAtLeast(0f))
        return Hit(normalize(bestChar), best)
    }

    /**
     * 文字识别是否算「合格」的唯一口径，UI 和日志都用它。
     *
     * 注意 [Stats.weak] 已经不再包含「分数偏低但明显领先次优」的字（那些字是真认得出来的），
     * 所以展示时不要再用 mean/median 自己算一遍，直接看 weak。
     */
    /**
     * 给日志用的字体质量摘要。
     *
     * **故意不提供「多少个字识别不出来」给 UI**：那个数字依赖相似度阈值，是统计量不是真值，
     * 真机上出现过「题干全对、却报 9 个字不确定」。UI 不展示任何字体质量指标，
     * 只有日志里 [Stats.report] 那一行是给排障用的。
     */
    fun confidenceText(): String {
        val s = stats ?: return ""
        return "字体统计：${s.total} 字，最低相似度 ${"%.2f".format(s.min)}"
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
