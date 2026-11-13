package com.xxt.sign.work

import com.xxt.sign.core.LogStore
import kotlinx.coroutines.CancellationException
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
    val hitThreshold: Float = 0.8f,
    /**
     * **字体识别不出来时的 LLM 兜底**（独立开关，默认关）。
     *
     * 和 [quizLlmEnabled] 完全无关：
     *  · [quizLlmEnabled]（在 Settings 里）= 「用大模型做题」主开关，用户按需开
     *  · 这个 = 「字体解不出来时，额外允许大模型猜一把」，默认关
     */
    val llmFallback: Boolean = false
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
    private val onStep: (Int, Int) -> Unit = { _, _ -> },
    /** 每题答完、进入拟人停留前回调；做题页用它显示题干/选项/大模型输出 */
    private val onQuestion: (QuizQuestionLive) -> Unit = {},
    /** 提交前等待开始；参数是秒数 */
    private val onSubmitWait: (Int) -> Unit = {},
    /** 用户点了「提前交卷」：中断剩余题目/等待，直接用当前答案提交 */
    private val shouldSubmitNow: () -> Boolean = { false },
    /** 用户手改的答案；提交前按 qid 覆盖自动答案 */
    private val overrideAnswer: (String) -> String? = { null }
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

        // 整卷预处理：多参考字体里逐字取最优，并按本卷相似度分布算自适应阈值
        val fontStats = decoder?.prepare(
            page.questions.flatMap { q -> listOf(q.stem) + q.options.map { it.text } }
        )
        if (fontStats != null) {
            // 只写日志，不再往详情页推「多少个字识别不确定」。
            //
            // 原因：这个数字依赖相似度阈值，而阈值是统计量，不是「错了几个字」的真值。
            // 真机上出现过「题干全对、却报 9 个字不确定」的情况，提示反而制造焦虑。
            // 字体质量只留日志给排障用。
            log(fontStats.report(), true)
        }

        val outcomes = mutableListOf<QuestionOutcome>()
        val submitAnswers = LinkedHashMap<String, String>()
        val provider = answers

        val totalQuestions = page.questions.size
        for ((index, q) in page.questions.withIndex()) {
            if (shouldSubmitNow()) break

            runCatching { onStep(index + 1, totalQuestions) }
            val stem = decode(decoder, q.stem)
            val opts = q.options.map { it.copy(text = decode(decoder, it.text).text) }
            val decodedQ = q.copy(stem = stem.text, options = opts)
            val no = index + 1

            // 识别质量不再逐题提示（用户明确要求去掉），题干/选项照常展示。
            val weakNote = ""
            val warning = ""

            // 拟人节奏：每题停留 8–20 秒。先在识别完题目时把题干/选项推给详情页，
            // 再去查题库/大模型；这样详情页不用等答案返回才出现。
            val waitMs = randomBetween(config.perQuestionMinSec, config.perQuestionMaxSec)
            val waitSec = ((waitMs + 999) / 1000).toInt()
            emitQuestion(
                job = job, q = q, stem = stem.text, options = opts,
                answer = "", source = "识别中", llmRaw = "", llmError = "",
                warning = warning,
                correctedStem = "", correctedOptions = emptyList(), searchSummary = "",
                index = no, total = totalQuestions, waitSeconds = 0
            )
            if (shouldSubmitNow()) break

            provider?.clearLlmRaw()
            // 大模型有两个**互相独立**的开关，别混：
            //
            //  · Settings.quizLlmEnabled（+ 填了 key）= 「用大模型做题」主开关。
            //    用户要用的就是这个，开了就正常参与答题（题库优先、没命中再问模型）。
            //  · config.llmFallback = 「字体识别不出来时的纠错兜底」，**默认关**。
            //    打开后，字体解不出/认不准的题会让大模型**优先**作答并按上下文纠错；
            //    关闭时完全不受字体质量影响 —— 这正是用户要的：
            //    之前「题干全对却被 LLM 纠错改错」就是因为它默认开着。
            val fontUnsure = stem.weak.isNotEmpty()
            val preferLlm = fontUnsure && config.llmFallback
            val llmHint = if (preferLlm) {
                "题干或选项可能因字体反解出现少量错字，请结合上下文还原题意后再作答。"
            } else {
                ""
            }
            if (preferLlm && provider?.configured == true) {
                log("[测验] 第${no}题 字体识别不确定 + 已开兜底，交大模型优先作答", false)
            }
            val answer: QuizAnswer? = when {
                provider == null || !provider.configured -> null
                // catch Throwable：类初始化错误（ExceptionInInitializerError / NoClassDefFoundError）
                // 不是 Exception，用 runCatching 会静默吞掉，日志里只剩「都没命中」没有原因
                else -> try {
                    // allowLlm 恒为 true：只要用户开了大模型做题，题库没命中就该问模型，
                    // 不受字体质量开关影响。llmFallback 只决定要不要「优先」问。
                    provider.search(decodedQ, preferLlm = preferLlm, hint = llmHint, allowLlm = true)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    log("[测验] 第${no}题 ${WorkQuestion.typeName(q.type)} 查答案异常：${describeThrowable(t)}，留空", false)
                    null
                }
            }

            when {
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

            if (answer != null &&
                (answer.correctedStem.isNotBlank() || answer.correctedOptions.isNotEmpty())
            ) {
                log(
                    "[LLM] 第${no}题已自动纠正题干/选项" +
                        if (answer.correctedStem.isNotBlank()) "（题干 ${answer.correctedStem.length} 字）" else "",
                    true
                )
            }

            // 查完答案再推一次详情，带上题库/LLM 结果；等待倒计时从这时开始算。
            val sourceText = when {
                answer != null -> answer.source + weakNote
                else -> "未命中" + weakNote
            }
            emitQuestion(
                job = job, q = q, stem = stem.text, options = opts,
                answer = answer?.value.orEmpty(),
                source = sourceText,
                llmRaw = if (answer?.source?.startsWith("LLM") == true) provider?.lastLlmRaw.orEmpty() else "",
                llmError = if (answer == null && provider?.configured == true) provider?.lastLlmError.orEmpty() else "",
                warning = warning,
                correctedStem = answer?.correctedStem.orEmpty(),
                correctedOptions = answer?.correctedOptions.orEmpty(),
                searchSummary = answer?.searchSummary.orEmpty(),
                index = no, total = totalQuestions, waitSeconds = waitSec
            )
            if (shouldSubmitNow()) break

            sleeper(waitMs)
            if (shouldSubmitNow()) break
        }

        val total = page.questions.size
        fun manualAnswer(q: WorkQuestion): String? =
            runCatching { overrideAnswer(q.qid) }.getOrNull()?.takeIf { it.isNotBlank() }

        val hasManualAnswer = page.questions.any { manualAnswer(it) != null }
        val manualSubmit = shouldSubmitNow()

        if (submitAnswers.isEmpty() && !hasManualAnswer) {
            log("[测验] ${job.chapterTitle} · ${page.title} 一题都没答上，不交白卷", false)
            return result(
                job, false, 0, total,
                "$total 题一题都没答上（题库没答案 ${outcomes.count { it.source == "未命中" }} 题），这卷没交",
                outcomes,
                hitRate = 0f
            )
        }

        // 提交前先记一次「已作答次数」基线：提交后回查没增加，就说明服务端其实没落库。
        // 只是辅助判定，网络失败不阻止提交。
        val beforeTimes = if (!config.storeOnly) {
            runCatching { api.recordList(job).times }.getOrNull()
        } else {
            null
        }

        // 答完到提交之间再等一段；这段期间用户还可以在详情里改选项。
        // 用户点「提前交卷」时不等待，也不显示提交前倒计时。
        if (!manualSubmit) {
            val submitWaitMs = randomBetween(config.submitWaitMinSec, config.submitWaitMaxSec)
            runCatching { onSubmitWait(((submitWaitMs + 999) / 1000).toInt()) }
            sleeper(submitWaitMs)
        }

        // 用户手改的答案覆盖自动答案（多选/判断也按页面需要的值存）
        val finalAnswers = LinkedHashMap(submitAnswers)
        page.questions.forEach { q ->
            manualAnswer(q)?.let { finalAnswers[q.qid] = it }
        }
        if (finalAnswers.isEmpty()) {
            log("[测验] ${job.chapterTitle} · ${page.title} 没有可提交的答案，不交白卷", false)
            return result(job, false, 0, total, "没有可提交的答案，这卷没交", outcomes)
        }
        if (finalAnswers.size > submitAnswers.size) {
            log("[测验] 提交前采用用户手改答案 ${finalAnswers.size - submitAnswers.size} 题", true)
        }
        val hitRate = if (total > 0) finalAnswers.size.toFloat() / total else 0f

        val submit = try {
            api.submit(job, page, finalAnswers, config.storeOnly)
        } catch (e: Exception) {
            lastFinishAt = System.currentTimeMillis()
            return result(
                job, false, finalAnswers.size, total,
                "提交异常：${e.message}", outcomes, hitRate = hitRate
            )
        }
        lastFinishAt = System.currentTimeMillis()

        // 服务端可能返回 status=true 但实际没落库；提交后回查一次，次数没增加就改判失败。
        var submitOk = submit.ok
        var submitMessage = submit.message
        var score = ""
        if (submitOk && !config.storeOnly) {
            val rec = runCatching { api.recordList(job) }.getOrNull()
            if (rec != null) {
                val baseline = beforeTimes ?: 0
                if (!rec.seen || rec.times <= baseline) {
                    submitOk = false
                    submitMessage = "服务端未记录本次作答（回查次数 ${rec.times}，提交前 $baseline）"
                    log("[测验] ${job.chapterTitle} · ${page.title} 提交返回成功但回查不到，判定提交失败", false)
                } else {
                    score = rec.score
                    val tail = if (rec.score.isBlank()) "" else "，最高 ${rec.score} 分"
                    log("[测验] 回查：共 ${rec.times} 次$tail", true)
                }
            }
        }

        val mode = when {
            config.storeOnly -> "已暂存（未交卷）"
            submitOk -> "已提交"
            else -> "提交失败"
        }
        val rateText = "命中 ${finalAnswers.size}/$total（${(hitRate * 100).toInt()}%）"
        val low = hitRate < config.hitThreshold
        val blank = (total - finalAnswers.size).coerceAtLeast(0)
        val msg = buildString {
            if (submitOk) append(mode).append("：").append(rateText)
            else append("提交失败：").append(submitMessage).append("｜").append(rateText)
            append("｜").append(total).append(" 题：").append(finalAnswers.size).append(" 题答上")

            if (blank > 0) append("、").append(blank).append(" 题没答案/留空")
            if (low) append("｜低置信，建议回看")
        }
        log("[测验] ${job.chapterTitle} · ${page.title} $msg", submitOk && !low)

        return result(
            job, submitOk, finalAnswers.size, total, msg, outcomes,
            hitRate = hitRate, score = score,
            fontReference = fontStats?.reference.orEmpty(),
            fontMean = fontStats?.mean ?: 0f,
            fontMin = fontStats?.min ?: 0f,
            fontWeak = fontStats?.weak ?: 0
        )
    }

    private fun emitQuestion(
        job: WorkJob,
        q: WorkQuestion,
        stem: String,
        options: List<WorkOption>,
        answer: String,
        source: String,
        llmRaw: String,
        llmError: String,
        warning: String,
        correctedStem: String,
        correctedOptions: List<WorkOption>,
        searchSummary: String,
        index: Int,
        total: Int,
        waitSeconds: Int
    ) {
        runCatching {
            onQuestion(
                QuizQuestionLive(
                    courseId = job.courseId,
                    courseTitle = job.courseTitle,
                    chapterId = job.chapterId,
                    chapterTitle = job.chapterTitle,
                    qid = q.qid,
                    type = q.type,
                    stem = stem,
                    options = options,
                    answer = answer,
                    source = source,
                    llmRaw = llmRaw,
                    llmError = llmError,
                    warning = warning,
                    correctedStem = correctedStem,
                    correctedOptions = correctedOptions,
                    searchSummary = searchSummary,
                    index = index,
                    total = total,
                    waitSeconds = waitSeconds
                )
            )
        }
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
        /** 上一份卷子结束的时间：两卷间隔 ≥ gapSeconds，避免连续交卷 */
        @Volatile
        private var lastFinishAt = 0L

        fun resetPacer() {
            lastFinishAt = 0L
        }
    }
}
