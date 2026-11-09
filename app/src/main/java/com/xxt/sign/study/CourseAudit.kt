package com.xxt.sign.study

/**
 * 「这门课还差什么」的体检结果。
 *
 * 目标定义（用户原话）：**一门课丢给它，最后课程页的任务点应该全绿**。
 * 也就是视频任务点全完成、章节测验全部提交拿到分、文档任务点全完成。
 *
 * 统计口径是**任务点**（knowledge/cards 里带 jobid 且 job=true 的 attachment），
 * 不按章算 —— 用户看的是「还差几个任务点」。
 *
 * 能自己做完的：视频 / 文档 / workA 章节测验。
 * 必须本人出手的：非 workA 的 work（作业/考试/待确认的新类型）、音频、认不出的类型、未开放章节。
 * 分类只看原始字段 type + property.worktype，**不看标题**（实测标题叫什么的都有）。
 */
data class CourseAudit(
    val courseId: String,
    val courseTitle: String,
    /** 该课程检查到的任务点总数 */
    val taskPoints: Int = 0,
    /** 已完成任务点 */
    val doneTaskPoints: Int = 0,
    /** 待做视频任务点 */
    val videoPending: Int = 0,
    /** 待做文档任务点（它能自己刷掉） */
    val documentPending: Int = 0,
    /** 待做章节测验份数（workA） */
    val quizPending: Int = 0,
    /** 必须本人处理的任务点 */
    val humanPending: Int = 0,
    val gaps: List<Gap> = emptyList(),
    /** 逐章进度（课程页直接渲染，不另请求） */
    val chapterRows: List<ChapterRow> = emptyList(),
    val chapters: Int = 0,
    val checkedChapters: Int = 0,
    /** 逐章行状态的口径版本：2 = 按任务点算（done/total）；<2 是旧版图标口径，界面不得直接用 */
    val rowsVersion: Int = ROWS_VERSION,
    val auditedAt: Long = System.currentTimeMillis()
) {
    enum class Kind { VIDEO, DOCUMENT, QUIZ, HUMAN, UNKNOWN }

    data class Gap(
        val kind: Kind,
        val chapterTitle: String,
        val detail: String
    )

    /** 还差多少任务点（含需本人的，界面上的「还差」就是这个数） */
    val remaining: Int get() = (taskPoints - doneTaskPoints).coerceAtLeast(0)

    companion object {
        /** 逐章行状态口径版本：只有这个版本的缓存行才可以拿来渲染 */
        const val ROWS_VERSION = 2
    }

    /** 它自己还能做完的缺口 */
    val autoRemaining: Int get() = videoPending + documentPending + quizPending

    /** 全绿：没有待完成任务点 */
    val allGreen: Boolean get() = remaining == 0

    /** 只差本人出手了（自动能做的都做完了） */
    val onlyHumanLeft: Boolean get() = remaining == 0 && humanPending > 0

    /** 首页/通知用的一句话；两个数绝不混着报（章节数 ≠ 任务点数） */
    fun headline(): String = when {
        allGreen && humanPending == 0 -> "$courseTitle：已全绿"
        allGreen -> "$courseTitle：它能做的都做完了，还有 $humanPending 个任务点要你本人"
        else -> buildString {
            append(courseTitle).append("：还差 ").append(remaining).append(" 个任务点")
            val parts = mutableListOf<String>()
            if (videoPending > 0) parts.add("视频 $videoPending")
            if (documentPending > 0) parts.add("文档 $documentPending")
            if (quizPending > 0) parts.add("测验 $quizPending")
            if (humanPending > 0) parts.add("需你本人 $humanPending")
            if (parts.isNotEmpty()) append("（").append(parts.joinToString(" · ")).append("）")
        }
    }
}

/**
 * 用平台自报的「已完成任务点 X/Y」作为总数口径。
 *
 * 为什么要它：图标直接判完成的章节只有章节目录里的 `knowledgeJobCount` 可用，
 * 真机上自数出来 304、平台是 392 —— 分母不对，用户看到的进度永远对不上。
 * 平台解析不到（total <= 0）时保持自数。
 */
fun CourseAudit.withPlatformTotals(done: Int, total: Int): CourseAudit =
    if (total <= 0) this else copy(taskPoints = total, doneTaskPoints = done.coerceIn(0, total))

/** 课程页逐章列表的一行：数据全部来自体检，不额外请求 */
data class ChapterRow(
    val title: String,
    val total: Int,
    val done: Int,
    val state: State,
    /** 视频任务点完成情况，"部分完成（视频 3/5）" 用 */
    val videoDone: Int = 0,
    val videoTasks: Int = 0
) {
    enum class State {
        /** 已完成：该章所有带 jobid 的任务点都完成（不再只看 icon_yiwanc） */
        DONE,

        /** 做了一部分：完成数 0 < done < total */
        PARTIAL,

        /** 一个都没做 */
        TODO,

        /** 章节未开放 */
        LOCKED,

        /** 没检查到（扫描失败 / 体检没跑到） */
        UNCHECKED
    }
}

/**
 * 界面渲染用的状态：**按 done/total 现算**，不信任持久化里的旧状态字符串。
 *
 * 旧版本把逐章状态写成了图标口径（`icon_yiwanc`），存下来的 "TODO" 会让刷完的章
 * 永远显示「未开始」；所以渲染时一律根据任务点数字重算。
 */
fun ChapterRow.stateByTaskPoints(): ChapterRow.State = when {
    state == ChapterRow.State.LOCKED -> ChapterRow.State.LOCKED
    total > 0 && done >= total -> ChapterRow.State.DONE
    done > 0 -> ChapterRow.State.PARTIAL
    total > 0 -> ChapterRow.State.TODO
    // 没有任何任务点明细：就是没查到，不能编「未开始」
    else -> ChapterRow.State.UNCHECKED
}

/** 单章的检查输入：这章的任务点检查结果 */
data class ChapterAuditInput(
    val chapterTitle: String,
    val check: ChapterCheck?
)

/**
 * 把每章的检查结果聚合成整门课的结论。纯计算，方便单测。
 */
object CourseAuditCalculator {

    fun audit(
        courseId: String,
        courseTitle: String,
        chapters: List<ChapterAuditInput>,
        auditedAt: Long = System.currentTimeMillis()
    ): CourseAudit {
        var taskPoints = 0
        var donePoints = 0
        var videoPending = 0
        var documentPending = 0
        var quizPending = 0
        var humanPending = 0
        var checked = 0
        val gaps = mutableListOf<CourseAudit.Gap>()
        val rows = mutableListOf<ChapterRow>()

        for (ch in chapters) {
            val check = ch.check
            if (check == null) {
                rows.add(ChapterRow(ch.chapterTitle, 0, 0, ChapterRow.State.UNCHECKED))
                gaps.add(CourseAudit.Gap(CourseAudit.Kind.UNKNOWN, ch.chapterTitle, "这章没检查到，可能没开放"))
                continue
            }
            if (check.notOpen) {
                checked++
                rows.add(ChapterRow(ch.chapterTitle, check.allTasks, check.allDone, ChapterRow.State.LOCKED))
                gaps.add(CourseAudit.Gap(CourseAudit.Kind.HUMAN, ch.chapterTitle, "章节未开放"))
                humanPending += (check.allTasks - check.allDone).coerceAtLeast(1)
                taskPoints += check.allTasks
                donePoints += check.allDone
                continue
            }

            // 卡片没解析到但章节标了有任务点：不知道真实进度，别当「未开始」也不虚增分母
            if (check.scanFailed) {
                rows.add(ChapterRow(ch.chapterTitle, 0, 0, ChapterRow.State.UNCHECKED))
                gaps.add(
                    CourseAudit.Gap(CourseAudit.Kind.UNKNOWN, ch.chapterTitle, "这章任务点没解析到，进度未知")
                )
                continue
            }

            checked++
            taskPoints += check.allTasks
            donePoints += check.allDone
            // 逐章状态按任务点算（icon_yiwanc 只会在全完成时亮，不能拿来当唯一依据）
            val state = when {
                check.allTasks > 0 && check.allComplete -> ChapterRow.State.DONE
                check.allDone > 0 -> ChapterRow.State.PARTIAL
                else -> ChapterRow.State.TODO
            }
            rows.add(
                ChapterRow(
                    title = ch.chapterTitle,
                    total = check.allTasks,
                    done = check.allDone,
                    state = state,
                    videoDone = check.videoDone,
                    videoTasks = check.videoTasks
                )
            )
            if (check.allComplete) continue

            if (check.videoLeft > 0) {
                videoPending += check.videoLeft
                gaps.add(CourseAudit.Gap(CourseAudit.Kind.VIDEO, ch.chapterTitle, "视频还差 ${check.videoLeft} 个"))
            }
            if (check.documentLeft > 0) {
                documentPending += check.documentLeft
                gaps.add(
                    CourseAudit.Gap(CourseAudit.Kind.DOCUMENT, ch.chapterTitle, "文档还差 ${check.documentLeft} 个")
                )
            }
            if (check.quizLeft > 0) {
                quizPending += check.quizLeft
                gaps.add(CourseAudit.Gap(CourseAudit.Kind.QUIZ, ch.chapterTitle, "章节测验 ${check.quizLeft} 份"))
            }
            val human = check.humanLeft
            if (human > 0) {
                humanPending += human
                gaps.add(
                    CourseAudit.Gap(
                        CourseAudit.Kind.HUMAN, ch.chapterTitle,
                        buildString {
                            append(human).append(" 个任务点它不代做")
                            val kinds = mutableListOf<String>()
                            check.otherWorks.forEach { (t, n) -> kinds.add("worktype=$t × $n") }
                            check.unknownTasks.forEach { (t, n) -> kinds.add("type=$t × $n") }
                            if (check.audioLeft > 0) kinds.add("音频 × ${check.audioLeft}")
                            if (kinds.isNotEmpty()) append("（").append(kinds.joinToString("，")).append("）")
                        }
                    )
                )
            }
        }

        return CourseAudit(
            courseId = courseId,
            courseTitle = courseTitle,
            taskPoints = taskPoints,
            doneTaskPoints = donePoints,
            videoPending = videoPending,
            documentPending = documentPending,
            quizPending = quizPending,
            humanPending = humanPending,
            gaps = gaps,
            chapterRows = rows,
            chapters = chapters.size,
            checkedChapters = checked,
            auditedAt = auditedAt
        )
    }
}
