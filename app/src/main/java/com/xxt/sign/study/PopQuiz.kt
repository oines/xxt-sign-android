package com.xxt.sign.study

/**
 * 视频中途弹题（随堂弹题 / 插播题）。
 *
 * 现状：学习通播放器会在某个时间点暂停视频弹一道题，**答不对视频就不往下播**。
 * 这条链路不解决，「一门课刷到全绿」就不可能——视频会永远停在弹题那一刻。
 *
 * 但仓库里目前**没有真机抓包**能确定接口（见 docs/弹题.md），所以这里做的是骨架 +
 * 可插拔：真机抓到接口后，只要在设置里填两个 URL 模板（或改 [ConfiguredPopQuizBridge]
 * 的解析），不用动播放循环。
 *
 * 三层职责：
 *  - [PopQuizQuestion] / [PopQuizResult]  数据
 *  - [PopQuizBridge]                      怎么问、怎么交（可插拔）
 *  - [PopQuizPolicy]                      拿到什么情况该怎么处置（纯逻辑，可单测）
 */
data class PopQuizQuestion(
    val id: String,
    val type: Int,
    val stem: String,
    val options: List<String> = emptyList()
) {
    /** 0 单选 / 1 多选 / 2 填空 / 3 判断 */
    val typeName: String
        get() = when (type) {
            0 -> "单选"
            1 -> "多选"
            2 -> "填空"
            3 -> "判断"
            else -> "未知题型"
        }
}

/** 一道弹题的处理结果 */
data class PopQuizAttempt(
    val question: PopQuizQuestion,
    val answer: String,
    val ok: Boolean,
    val reason: String = ""
)

/** 一次视频里弹题的整体结果 */
data class PopQuizResult(
    val total: Int = 0,
    val answered: Int = 0,
    val skipped: Int = 0,
    val timedOut: Boolean = false,
    val needsHuman: Boolean = false,
    val attempts: List<PopQuizAttempt> = emptyList()
) {
    val failed: Int get() = total - answered

    /** 任务卡片/日志里的一句话 */
    fun summary(): String = when {
        total == 0 -> ""
        answered == total -> "中途弹题 $total 道，全答上了"
        timedOut -> "中途弹题 $total 道，答上 $answered 道，剩下的超时跳过（视频可能停在这里）"
        else -> "中途弹题 $total 道，答上 $answered 道"
    }
}

/**
 * 弹题接口层。真机抓到之后填这里。
 *
 * 约定：
 *  - [poll] 在播放过程中被反复调用，返回「当前这个时间点待答的题」，没有就返回空。
 *  - [submit] 返回 true 表示服务端接受，播放器可以继续。
 */
interface PopQuizBridge {

    /** 这个桥有没有被配置过；没配置时调用方应该走「检测 + 标需要人工」而不是发请求 */
    val configured: Boolean

    suspend fun poll(job: VideoJob, atSecond: Int): List<PopQuizQuestion>

    suspend fun submit(job: VideoJob, question: PopQuizQuestion, answer: String): Boolean

    companion object {
        /** 没配置时的空实现：永远没有题，也永远不会提交 */
        val NONE: PopQuizBridge = object : PopQuizBridge {
            override val configured = false
            override suspend fun poll(job: VideoJob, atSecond: Int) = emptyList<PopQuizQuestion>()
            override suspend fun submit(
                job: VideoJob,
                question: PopQuizQuestion,
                answer: String
            ) = false
        }
    }
}

/** 播放循环里的处置结果 */
sealed interface PopQuizDecision {
    /** 继续播 */
    data object Continue : PopQuizDecision

    /** 答不上 / 没配置 / 超时：这视频八成停在这了，记录下来交给上层 */
    data class GiveUp(val reason: String, val needsHuman: Boolean) : PopQuizDecision
}

/**
 * 弹题处置策略：纯逻辑，方便单测。
 *
 * 规则（来自需求）：
 *  - 解出来就答，全答上 → 继续播
 *  - 答不上 → 60 秒超时，跳过并在日志里标「这道弹题没答上，视频可能停在这里」
 *  - **绝不能让整门课卡死**：超时后不再死等，把任务标成需要人工，继续下一个任务点
 *  - 没有接口（没抓包 / 没配置）时，靠「进度不前进」判断卡在弹题，同样标需要人工
 */
object PopQuizPolicy {

    /** 同一个时间点连续多久没动就认为卡在弹题 */
    const val STALL_ROUNDS = 3

    data class Outcome(val decision: PopQuizDecision, val result: PopQuizResult)

    /**
     * @param bridge 接口层
     * @param questions 这次 poll 拿到的待答题（没配置时为空）
     * @param answers 每道题的作答（空字符串 = 没答上）
     * @param timedOut 这次轮询是否已经超过超时时间
     * @param stalledRounds 播放进度连续没前进的轮数
     */
    fun decide(
        bridge: PopQuizBridge,
        questions: List<PopQuizQuestion>,
        answers: List<String>,
        timedOut: Boolean,
        stalledRounds: Int
    ): Outcome {
        if (questions.isEmpty()) {
            // 没有题：检查是不是卡住了（没接口时这是我们唯一的信号）
            return if (stalledRounds >= STALL_ROUNDS) {
                Outcome(
                    PopQuizDecision.GiveUp(
                        "播放进度不动了，疑似卡在弹题（没配弹题接口，无法自动作答）",
                        needsHuman = true
                    ),
                    PopQuizResult(needsHuman = true)
                )
            } else {
                Outcome(PopQuizDecision.Continue, PopQuizResult())
            }
        }

        val attempts = questions.mapIndexed { i, q ->
            val ans = answers.getOrNull(i).orEmpty()
            PopQuizAttempt(q, ans, ok = ans.isNotBlank())
        }
        val answered = attempts.count { it.ok }
        val result = PopQuizResult(
            total = questions.size,
            answered = answered,
            skipped = questions.size - answered,
            timedOut = timedOut,
            needsHuman = timedOut || answered < questions.size,
            attempts = attempts
        )

        return when {
            answered == questions.size ->
                Outcome(PopQuizDecision.Continue, result)

            timedOut -> Outcome(
                PopQuizDecision.GiveUp(
                    "弹题 ${questions.size} 道只答上 $answered 道，超时跳过（视频可能停在这里）",
                    needsHuman = true
                ),
                result
            )

            // 没超时：还有机会重试，先继续播，下一轮再 poll
            else -> Outcome(PopQuizDecision.Continue, result)
        }
    }
}
