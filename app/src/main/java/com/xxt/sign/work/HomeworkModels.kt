package com.xxt.sign.work

/**
 * 「作业」模块的数据模型。
 *
 * 和章节测验（[WorkJob]）完全独立：作业是 `mooc1-api` 的 H5 页面
 * （`work/task-list` → `work/phone/task-work` → `work/phone/doHomeWork`），
 * 服务端渲染、一页一题；章节测验是 PC 端 `mooc-ans/api/work` 的整卷接口。
 *
 * 这里**只读**：只用于列表、打开题目和给出 AI 建议，不构造提交表单。
 * 用户明确要求不要用其账号提交任何东西，所以本模块没有任何写请求。
 */

/** 作业所属课程（来自刷课页的课程缓存，带 clazzId/cpi） */
data class HomeworkCourse(
    val courseId: String,
    val clazzId: String,
    val cpi: String,
    val title: String
) {
    val key: String get() = "$courseId:$clazzId"
}

/** 作业列表里的一条。状态实测为「未交 / 待批阅 / 已完成」 */
data class HomeworkItem(
    val course: HomeworkCourse,
    val workId: String,
    val title: String,
    val status: String,
    val deadline: String = "",
    /** 已完成作业的分数（task-work 页里的 `<p class="Number">`），未出分时为空 */
    val score: String = "",
    /** 服务端还允许「重做」时为 true（task-work 页里有 redo 入口） */
    val redoable: Boolean = false,
    /** 状态页里的 workAnswerId，重做时要带上 */
    val answerId: String = "",
    /** 扫描题型：选择题（单选/多选/判断）数量；未扫描为 0 */
    val choiceCount: Int = 0,
    /** 扫描题型：整份作业题数；未扫描为 0 */
    val questionCount: Int = 0,
    /** 整份作业是否全部为选择题（只有全选择题才允许在 App 里交卷） */
    val allChoice: Boolean = false,
    /** 是否已经扫描过题型 */
    val scanned: Boolean = false
) {
    /** 「未交」才是没做的作业；「待批阅」是已经交过了 */
    val unsubmitted: Boolean
        get() = status.contains("未交") || status.contains("未提交") || status.contains("待交")

    /** 已交卷（已完成或待批阅） */
    val submitted: Boolean
        get() = status.contains("已完成") || status.contains("待批阅")

    /**
     * 「待批阅」= 已经交了、等老师批改，还没有分数。
     *
     * 用户明确要求**不显示**：这种作业既做不了（已交），也没有分数可比较
     * （谈不上「分数不满要重做」），留在列表里只会干扰。
     */
    val isPendingReview: Boolean
        get() = status.contains("待批阅") || status.contains("待批改") || status.contains("批阅中")

    /** 分数不是满分（100）时需要继续显示在列表里，方便重做 */
    val belowFull: Boolean
        get() = score.toDoubleOrNull()?.let { it < 100.0 } ?: false

    /** 有选择题才值得显示（没有任何选择题的作业 App 里做不了） */
    val hasChoice: Boolean
        get() = if (scanned) choiceCount > 0 else true

    val key: String get() = "${course.key}:$workId"
}

/**
 * 作业里的一道题。
 *
 * [number] 从 1 开始；[total] 是这份作业的题数。
 * 单题作业（页面没有 `1/N` 标记）时 [total] = 1。
 */
data class HomeworkQuestion(
    val workId: String,
    val number: Int,
    val total: Int,
    val qid: String,
    val type: Int,
    val typeName: String,
    val stem: String,
    val options: List<WorkOption> = emptyList(),
    /** 页面里这道题所在的下标（0 起，即 `input#index`），提交时要原样带回去 */
    val pageIndex: Int = 0,
    /** `form#submitTest` 里的隐藏字段（不含答题字段），提交时原样带上 */
    val formFields: Map<String, String> = emptyMap(),
    /** 答题字段名：单选 `answer<qid>`，多选 `answers<qid>` */
    val answerField: String = ""
) {
    /** 选择题（单选 / 多选 / 判断）才能在这里作答 */
    val isChoice: Boolean get() = type == 0 || type == 1 || type == 3

    val isMulti: Boolean get() = type == 1

    /** 转成章节测验的题型：0 单选 / 1 多选 / 2 填空 / 3 判断 / 4 简答（题库/LLM 都用这套） */
    fun toWorkQuestion(): WorkQuestion =
        WorkQuestion(qid = qid, type = type, stem = stem, options = options)

    /** 把 "A" / "AB" 这种答案翻译成选项正文，方便阅读 */
    fun prettyAnswer(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        if (type != 0 && type != 1) return trimmed
        val keys = trimmed.uppercase().filter { it in 'A'..'Z' }.map { it.toString() }
        if (keys.isEmpty() || options.isEmpty()) return trimmed
        val parts = keys.mapNotNull { k ->
            options.firstOrNull { it.key.equals(k, ignoreCase = true) }?.let { "$k. ${it.text}" }
        }
        return parts.joinToString("；").ifBlank { trimmed }
    }
}
