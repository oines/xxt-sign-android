package com.xxt.sign.work

/**
 * 章节测验（work）相关模型。
 *
 * 纯数据 + 纯解析：不持有 Android / OkHttp 的任何东西，单测可以直接构造。
 */

/** 一个章节测验任务点（来自 knowledge/cards 的 mArg.attachments，type == "workid"） */
data class WorkJob(
    val courseId: String,
    val clazzId: String,
    val cpi: String,
    val chapterId: String,
    val chapterTitle: String,
    /** work-123456 */
    val jobId: String,
    /** 123456（jobId 去掉 work- 前缀） */
    val workId: String,
    val enc: String,
    /** mArg.defaults.ktoken */
    val ktoken: String,
    val name: String = "",
    val courseTitle: String = ""
) {
    val key: String get() = "$courseId:$jobId"
}

/** 一个选项 */
data class WorkOption(val key: String, val text: String)

/**
 * 一道题。
 * [type] 与页面 `input#answertype<qid>` 的 value 一致：
 * 0 单选 / 1 多选 / 2 填空 / 3 判断 / 4 简答
 */
data class WorkQuestion(
    val qid: String,
    val type: Int,
    val stem: String,
    val options: List<WorkOption>
) {
    val isChoice: Boolean get() = type == TYPE_SINGLE || type == TYPE_MULTI
    val isBlank: Boolean get() = type == TYPE_BLANK
    val isJudge: Boolean get() = type == TYPE_JUDGE

    companion object {
        const val TYPE_SINGLE = 0
        const val TYPE_MULTI = 1
        const val TYPE_BLANK = 2
        const val TYPE_JUDGE = 3
        const val TYPE_ESSAY = 4

        fun typeName(type: Int): String = when (type) {
            TYPE_SINGLE -> "单选"
            TYPE_MULTI -> "多选"
            TYPE_BLANK -> "填空"
            TYPE_JUDGE -> "判断"
            TYPE_ESSAY -> "简答"
            else -> "未知($type)"
        }
    }
}

/** 解析出来的一份卷子 */
data class WorkPage(
    val title: String,
    val questionCount: Int,
    val questions: List<WorkQuestion>,
    /** form 里 name 不以 answer 开头的字段，提交时原样带回 */
    val formFields: Map<String, String>,
    /** 已交卷（服务端直接给了 record 页） */
    val submitted: Boolean,
    /** style#cxSecretStyle 里内嵌的 TTF（base64），没有就是明文 */
    val fontBase64: String?
)

/** 一次答题的答案 */
data class QuizAnswer(val value: String, val source: String, val confidence: Float = 1f)

/** 单题处理结果，用于日志与回查 */
data class QuestionOutcome(
    val qid: String,
    val type: Int,
    val stemForLog: String,
    val answer: String,
    val source: String,
    /** 字体反解是否可信（有字低于阈值时为 false） */
    val decodeOk: Boolean = true
)

/** 一份卷子的最终结果 */
data class QuizResult(
    val title: String,
    val submitted: Boolean,
    val formal: Boolean,
    val answered: Int,
    val total: Int,
    val message: String,
    val questionOutcomes: List<QuestionOutcome> = emptyList(),
    /** 命中率（题库答上的题 / 总题数），用于低置信提示 */
    val hitRate: Float = 0f,
    /** 硬阻塞（人脸 / 滑块 / 签到码），只能人工处理 */
    val needsHuman: Boolean = false,
    /** 课程名，通知里用 */
    val courseTitle: String = "",
    /** 回查到的分数（没查就是空） */
    val score: String = ""
) {
    val ok: Boolean get() = submitted && answered > 0

    /** 题库命中 / LLM 兜底 / 留空 的题数，用于日志与通知 */
    val bankHits: Int
        get() = questionOutcomes.count { it.answer.isNotBlank() && it.source.startsWith("题库") }

    val llmHits: Int
        get() = questionOutcomes.count { it.answer.isNotBlank() && it.source.startsWith("LLM") }

    val blanks: Int get() = (total - answered).coerceAtLeast(0)

    /** 命中率低于阈值：照样交，但要提示「低置信，建议回看」 */
    fun isLowConfidence(threshold: Float = 0.8f): Boolean = total > 0 && hitRate < threshold
}
