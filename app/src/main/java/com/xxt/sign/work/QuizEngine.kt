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
    private val log: (String, Boolean) -> Unit = { m, ok -> LogStore.add(m, ok) }
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

        val outcomes = mutableListOf<QuestionOutcome>()
        val submitAnswers = LinkedHashMap<String, String>()
        val provider = answers
        // 成本保护：一卷里 LLM 最多调「题量」次
        provider?.beginPaper(page.questions.size)

        page.questions.forEachIndexed { index, q ->
            val stem = decode(decoder, q.stem)
            val opts = q.options.map { it.copy(text = decode(decoder, it.text).text) }
            val decodedQ = q.copy(stem = stem.text, options = opts)
            val weak = stem.weak + q.options.flatMap { decode(decoder, it.text).weak }
            val no = index + 1

            val answer: QuizAnswer? = when {
                weak.isNotEmpty() -> null
                provider == null || !provider.configured -> null
                else -> runCatching { provider.search(decodedQ) }.getOrNull()
            }

            when {
                weak.isNotEmpty() -> {
                    log(
                        "[测验] 第${no}题 ${WorkQuestion.typeName(q.type)} 字体反解存疑" +
                            "（${weak.distinct().joinToString("")}，最低 ${"%.2f".format(stem.minScore)}），留空待复核",
                        false
                    )
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
                    log("[测验] 第${no}题 ${WorkQuestion.typeName(q.type)} ← ${answer.source}：${answer.value}", true)
                    outcomes.add(
                        QuestionOutcome(q.qid, q.type, WorkParser.shortStem(stem.text), answer.value, answer.source)
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
                job, false, 0, total, "没有把握的题，未提交", outcomes,
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
        val msg = buildString {
            if (submit.ok) append(mode).append("：").append(rateText)
            else append("提交失败：").append(submit.message).append("｜").append(rateText)
            append("｜题库 ").append(bank).append(" / LLM ").append(byLlm).append(" / 留空 ").append(blank)
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
            hitRate = hitRate, score = score
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
        score: String = ""
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
        score = score
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
        /** 上一份卷子结束的时间：两卷间隔 ≥ gapSeconds，避免连续交卷 */
        @Volatile
        private var lastFinishAt = 0L

        fun resetPacer() {
            lastFinishAt = 0L
        }
    }
}
