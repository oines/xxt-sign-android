package com.xxt.sign.work

import com.xxt.sign.core.LogStore
import kotlinx.coroutines.delay
import kotlin.random.Random

/** 风控节奏，全部可以在设置页改；默认值来自实测（单题 8–20s、提交前 30–60s、两卷间隔 ≥90s） */
data class QuizConfig(
    /** true = 只暂存（pyFlag=1）。默认 false：挂机要的是自己交完 */
    val storeOnly: Boolean = false,
    val perQuestionMinSec: Int = 8,
    val perQuestionMaxSec: Int = 20,
    val submitWaitMinSec: Int = 30,
    val submitWaitMaxSec: Int = 60,
    val gapSeconds: Int = 90,
    val fontThreshold: Float = 0.86f,
    /** 命中率低于它 → 照交，但标「低置信，建议回看」 */
    val hitThreshold: Float = 0.8f
)

/**
 * 章节测验自动答题：拉题 → 字体反解 → 查题库 → 填 → 等 → 提交 → 回查。
 *
 * 挂机模式的约定：
 *  - 能答的题都答，**答不上的留空，不猜**
 *  - 命中率低于阈值也照交（用户要的是完成），只是日志/通知标「低置信」
 *  - 硬阻塞（人脸 / 滑块 / 签到码）→ 只标「需人工」，不抛异常、不中断别的卷
 *  - 一题都没答上就不提交（避免交白卷）
 *  - 风控节奏：单题 8–20s、提交前 30–60s、两卷 ≥90s
 */
class QuizEngine(
    private val api: WorkApi,
    private val answers: AnswerProvider?,
    private val config: QuizConfig,
    /** base64 TTF → 解码器；Android 端注入，测试里可以传 null */
    private val decoderFactory: (String) -> FontDecoder? = { null },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val log: (String, Boolean) -> Unit = { m, ok -> LogStore.add(m, ok) },
    /** 每道题的进度回调：第 index 题 / 共 total 题 */
    private val onStep: (Int, Int) -> Unit = { _, _ -> }
) {

    /** 页面里出现这些字样又没解析出题目 → 大概率是硬阻塞 */
    private val hardBlocks = listOf("人脸", "人脸识别", "captcha", "滑块", "签到码", "扫码")

    suspend fun run(job: WorkJob): QuizResult {
        awaitGap()

        val html = try {
            api.fetch(job)
        } catch (e: Exception) {
            return result(job, false, 0, 0, "拉取失败：${e.message ?: e.javaClass.simpleName}")
        }

        val page = WorkParser.parse(html)
        if (page.submitted) {
            log("[测验] ${job.chapterTitle} · ${page.title} 已经交过卷，跳过", true)
            return result(job, false, 0, page.questionCount, "已经交过卷，跳过")
        }
        if (page.questions.isEmpty()) {
            val blocked = hardBlocks.firstOrNull { html.contains(it, ignoreCase = true) }
            if (blocked != null) {
                log("[测验] ${job.chapterTitle} · ${page.title} 卡在「$blocked」，标需人工", false)
                return result(
                    job, false, 0, page.questionCount, "需人工：页面要求$blocked",
                    needsHuman = true
                )
            }
            log("[测验] ${job.chapterTitle} · ${page.title} 没解析到题目", false)
            return result(job, false, 0, page.questionCount, "没解析到题目")
        }

        val decoder = page.fontBase64?.let { b64 -> runCatching { decoderFactory(b64) }.getOrNull() }
        if (page.fontBase64 != null && decoder == null) {
            log("[测验] ${job.chapterTitle} 字体解不开，按明文处理（大概率答错，建议人工）", false)
        }

        // 整卷预处理：多参考字体里选一套胜出的，并按本卷相似度分布算自适应阈值
        val fontStats = decoder?.prepare(
            page.questions.flatMap { q -> listOf(q.stem) + q.options.map { it.text } }
        )
        if (fontStats != null) {
            log(
                "[测验] 字体识别：${fontStats.reference}｜平均 ${"%.2f".format(fontStats.mean)}" +
                    "｜中位 ${"%.2f".format(fontStats.median)}｜最低 ${"%.2f".format(fontStats.min)}" +
                    "｜存疑 ${fontStats.weak}/${fontStats.total} 字｜阈值 ${"%.2f".format(fontStats.threshold)}",
                true
            )
            if (fontStats.unreadable) {
                log(
                    "[测验] ${job.chapterTitle} 这卷字体无法识别（中位 ${"%.2f".format(fontStats.median)}），不硬答",
                    false
                )
                return result(
                    job, false, 0, page.questionCount,
                    "这卷字体无法识别，未作答", needsHuman = true
                )
            }
        }

        val outcomes = mutableListOf<QuestionOutcome>()
        val submitAnswers = LinkedHashMap<String, String>()
        val provider = answers

        page.questions.forEachIndexed { index, q ->
            runCatching { onStep(index + 1, page.questions.size) }
            val stem = decode(decoder, q.stem)
            val opts = q.options.map { it.copy(text = decode(decoder, it.text).text) }
            val decodedQ = q.copy(stem = stem.text, options = opts)
            val weak = (stem.weak + q.options.flatMap { decode(decoder, it.text).weak }).distinct()
            val no = index + 1

            // 容错：整题就那么两三个字认不准时，仍然送去猜，只是结果要标「有 N 个字不确定」。
            // 真机上出现过「差 2 个字整卷留空」——那是把整份卷判死的过严策略。
            val totalChars = (stem.text.length + opts.sumOf { it.text.length }).coerceAtLeast(1)
            val weakRatioOk = weak.size * 100 <= totalChars * MAX_WEAK_PERCENT
            val tolerableWeak = weak.size <= MAX_WEAK_CHARS && weakRatioOk
            val weakNote = if (weak.isNotEmpty() && tolerableWeak) "（有 ${weak.size} 个字不确定）" else ""

            val answer: QuizAnswer? = when {
                weak.isNotEmpty() && !tolerableWeak -> null
                provider == null || !provider.configured -> null
                else -> runCatching { provider.search(decodedQ) }.getOrNull()
            }

            when {
                weak.isNotEmpty() && !tolerableWeak -> {
                    val why = stem.reason.ifBlank {
                        "有 ${weak.size} 个字认不出来（占这道题 ${weak.size * 100 / totalChars}%），怕答错，先留着"
                    }
                    log("[测验] 第${no}题 ${WorkQuestion.typeName(q.type)}：$why", false)
                    outcomes.add(
                        QuestionOutcome(q.qid, q.type, WorkParser.shortStem(stem.text), "", "待复核", false)
                    )
                }
                answer == null -> {
                    val why = provider?.lastLlmError?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                    log("[测验] 第${no}题 ${WorkQuestion.typeName(q.type)} 题库/LLM 都没命中$why，留空", false)
                    outcomes.add(
                        QuestionOutcome(q.qid, q.type, WorkParser.shortStem(stem.text), "", "未命中", true)
                    )
                }
                else -> {
                    submitAnswers[q.qid] = answer.value
                    log(
                        "[测验] 第${no}题 ${WorkQuestion.typeName(q.type)} ← ${answer.source}：${answer.value}$weakNote",
                        true
                    )
                    outcomes.add(
                        QuestionOutcome(
                            q.qid, q.type, WorkParser.shortStem(stem.text), answer.value,
                            answer.source + weakNote
                        )
                    )
                }
            }

            // 拟人节奏：每题停留 8–20 秒
            sleeper(randomBetween(config.perQuestionMinSec, config.perQuestionMaxSec))
        }

        val total = page.questions.size
        val hitRate = if (total > 0) submitAnswers.size.toFloat() / total else 0f

        if (submitAnswers.isEmpty()) {
            log("[测验] ${job.chapterTitle} · ${page.title} 一题都没答上，不交白卷", false)
            return result(
                job, false, 0, total,
                "$total 题一题都没答上（字认不准 ${outcomes.count { it.source == "待复核" }} 题，" +
                    "题库没答案 ${outcomes.count { it.source == "未命中" }} 题），这卷没交",
                outcomes,
                hitRate = 0f
            )
        }

        // 答完到提交之间再等一段
        sleeper(randomBetween(config.submitWaitMinSec, config.submitWaitMaxSec))

        val submit = try {
            api.submit(job, page, submitAnswers, config.storeOnly)
        } catch (e: Exception) {
            lastFinishAt = System.currentTimeMillis()
            return result(
                job, false, submitAnswers.size, total,
                "提交异常：${e.message}", outcomes, hitRate = hitRate
            )
        }
        lastFinishAt = System.currentTimeMillis()

        val mode = if (config.storeOnly) "已暂存（未交卷）" else "已提交"
        val rateText = "命中 ${submitAnswers.size}/$total（${(hitRate * 100).toInt()}%）"
        val low = hitRate < config.hitThreshold
        val bank = outcomes.count { it.answer.isNotBlank() && it.source.startsWith("题库") }
        val byLlm = outcomes.count { it.answer.isNotBlank() && it.source.startsWith("LLM") }
        val blank = (total - submitAnswers.size).coerceAtLeast(0)
        // 留空的题分两类：字认不准、题库/大模型没答案。别混成一句「没把握」
        val weakSkipped = outcomes.count { it.source == "待复核" }
        val missSkipped = (blank - weakSkipped).coerceAtLeast(0)
        val msg = buildString {
            if (submit.ok) append(mode).append("：").append(rateText)
            else append("提交失败：").append(submit.message).append("｜").append(rateText)
            append("｜").append(total).append(" 题：").append(submitAnswers.size).append(" 题答上")
            if (weakSkipped > 0) append("、").append(weakSkipped).append(" 题因字认不准没敢答")
            if (missSkipped > 0) append("、").append(missSkipped).append(" 题题库没答案")
            if (low) append("｜低置信，建议回看")
        }
        log("[测验] ${job.chapterTitle} · ${page.title} $msg", submit.ok && !low)

        var score = ""
        if (submit.ok && !config.storeOnly) {
            runCatching {
                val rec = api.recordList(job)
                if (rec.seen) {
                    score = rec.score
                    val tail = if (rec.score.isBlank()) "" else "，最高 ${rec.score} 分"
                    log("[测验] 回查：共 ${rec.times} 次$tail", true)
                }
            }
        }

        return result(
            job, submit.ok, submitAnswers.size, total, msg, outcomes,
            hitRate = hitRate, score = score,
            fontReference = fontStats?.reference.orEmpty(),
            fontMean = fontStats?.mean ?: 0f,
            fontMin = fontStats?.min ?: 0f,
            fontWeak = fontStats?.weak ?: 0
        )
    }

    private fun decode(decoder: FontDecoder?, text: String): FontDecoder.Decoded =
        if (decoder == null) FontDecoder.Decoded(text, emptyList(), 1f) else decoder.decode(text)

    private fun result(
        job: WorkJob,
        submitted: Boolean,
        answered: Int,
        total: Int,
        message: String,
        outcomes: List<QuestionOutcome> = emptyList(),
        hitRate: Float = 0f,
        needsHuman: Boolean = false,
        score: String = "",
        fontReference: String = "",
        fontMean: Float = 0f,
        fontMin: Float = 0f,
        fontWeak: Int = 0
    ) = QuizResult(
        title = "${job.chapterTitle} · ${job.name.ifBlank { job.workId }}",
        submitted = submitted,
        formal = !config.storeOnly,
        answered = answered,
        total = total,
        message = message,
        questionOutcomes = outcomes,
        hitRate = hitRate,
        needsHuman = needsHuman,
        courseTitle = job.courseTitle.ifBlank { job.courseId },
        score = score,
        fontReference = fontReference,
        fontMean = fontMean,
        fontMin = fontMin,
        fontWeak = fontWeak
    )

    private fun randomBetween(min: Int, max: Int): Long {
        val lo = min.coerceAtLeast(0)
        val hi = max.coerceAtLeast(lo)
        if (hi == lo) return lo * 1000L
        return Random.nextLong(lo.toLong(), hi.toLong() + 1) * 1000L
    }

    private suspend fun awaitGap() {
        val last = lastFinishAt
        if (last <= 0L) return
        val gapMs = config.gapSeconds.coerceAtLeast(0) * 1000L
        val wait = gapMs - (System.currentTimeMillis() - last)
        if (wait > 0) {
            log("[测验] 距上一份卷子不足 ${config.gapSeconds} 秒，等 ${wait / 1000} 秒再开始", true)
            sleeper(wait)
        }
    }

    companion object {
        /** 一道题里最多容忍几个认不准的字（超过就留空） */
        const val MAX_WEAK_CHARS = 3

        /** 认不准的字最多占整题多少百分比（超过就留空） */
        const val MAX_WEAK_PERCENT = 10

        /** 上一份卷子结束的时间：两卷间隔 ≥ gapSeconds，避免连续交卷 */
        @Volatile
        private var lastFinishAt = 0L

        fun resetPacer() {
            lastFinishAt = 0L
        }
    }
}
