package com.xxt.sign.study

/**
 * 「这门课还差什么」的体检结果。
 *
 * 目标定义（用户原话）：**一门课丢给它，最后课程页的任务点应该全绿**。
 * 也就是视频任务点全完成、章节测验全部提交拿到分、列表里没有红色未完成任务点。
 *
 * 所以这里不按「交了几份测验」统计，而按**任务点**统计：
 *  - 能自己做完的（视频 / 章节测验）算缺口，做完就减少
 *  - 必须本人出手的（人脸、滑块、位置签到、考试、要上传的作业、未开放章节）单独列出，
 *    不算失败，但也不假装做完了
 */
data class CourseAudit(
    val courseId: String,
    val courseTitle: String,
    /** 该课程检查到的任务点总数 */
    val taskPoints: Int = 0,
    /** 已完成任务点 */
    val doneTaskPoints: Int = 0,
    /** 视频待刷（章节数） */
    val videoPending: Int = 0,
    /** 章节测验待做（估计份数） */
    val quizPending: Int = 0,
    /** 必须本人处理的任务点 */
    val humanPending: Int = 0,
    val gaps: List<Gap> = emptyList(),
    val chapters: Int = 0,
    val checkedChapters: Int = 0,
    val auditedAt: Long = System.currentTimeMillis()
) {
    enum class Kind { VIDEO, QUIZ, HUMAN, UNKNOWN }

    data class Gap(
        val kind: Kind,
        val chapterTitle: String,
        val detail: String
    )

    /** 还差多少任务点（只算它自己能做完的） */
    val remaining: Int get() = (taskPoints - doneTaskPoints).coerceAtLeast(0)

    /** 全绿：没有待完成任务点 */
    val allGreen: Boolean get() = remaining == 0

    /** 只差本人出手了（自动能做的都做完了） */
    val onlyHumanLeft: Boolean get() = remaining == 0 && humanPending > 0

    /** 首页/通知用的一句话 */
    fun headline(): String = when {
        allGreen && humanPending == 0 -> "$courseTitle：已全绿"
        allGreen -> "$courseTitle：它能做的都做完了，还有 $humanPending 处要你本人"
        else -> buildString {
            append(courseTitle).append("：还差 ").append(remaining).append(" 个任务点")
            val parts = mutableListOf<String>()
            if (videoPending > 0) parts.add("视频 $videoPending 章")
            if (quizPending > 0) parts.add("测验约 $quizPending 份")
            if (humanPending > 0) parts.add("需你本人 $humanPending")
            if (parts.isNotEmpty()) append("（").append(parts.joinToString(" · ")).append("）")
        }
    }
}

/** 单章的检查输入：任务点检查结果 + 这章里找到的测验份数 */
data class ChapterAuditInput(
    val chapterTitle: String,
    val check: ChapterCheck?,
    /** null = 还没去数测验 */
    val quizJobs: Int?
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
        var quizPending = 0
        var humanPending = 0
        var checked = 0
        val gaps = mutableListOf<CourseAudit.Gap>()

        for (ch in chapters) {
            val check = ch.check
            if (check == null) {
                gaps.add(CourseAudit.Gap(CourseAudit.Kind.UNKNOWN, ch.chapterTitle, "这章没检查到，可能没开放"))
            } else {
                checked++
                taskPoints += check.allTasks
                donePoints += check.allDone

                if (check.notOpen) {
                    gaps.add(CourseAudit.Gap(CourseAudit.Kind.HUMAN, ch.chapterTitle, "章节未开放"))
                    humanPending += (check.allTasks - check.allDone).coerceAtLeast(0).coerceAtLeast(1)
                    continue
                }
                if (check.allComplete) continue

                val videoLeft = (check.videoTasks - check.videoDone).coerceAtLeast(0)
                if (videoLeft > 0) {
                    videoPending++
                    gaps.add(CourseAudit.Gap(CourseAudit.Kind.VIDEO, ch.chapterTitle, "视频还差 $videoLeft 个"))
                }

                // 非视频的任务点：章节测验 / 作业 / 考试 / 要上传的东西
                val otherLeft = (check.allTasks - check.allDone) - videoLeft
                if (otherLeft > 0) {
                    val quiz = (ch.quizJobs ?: 0).coerceAtMost(otherLeft)
                    if (quiz > 0) {
                        quizPending += quiz
                        gaps.add(CourseAudit.Gap(CourseAudit.Kind.QUIZ, ch.chapterTitle, "章节测验 $quiz 份"))
                    }
                    val rest = otherLeft - quiz
                    if (rest > 0) {
                        humanPending += rest
                        gaps.add(
                            CourseAudit.Gap(
                                CourseAudit.Kind.HUMAN, ch.chapterTitle,
                                "$rest 个任务点它做不了（作业/考试/上传）"
                            )
                        )
                    }
                }
            }
        }

        return CourseAudit(
            courseId = courseId,
            courseTitle = courseTitle,
            taskPoints = taskPoints,
            doneTaskPoints = donePoints,
            videoPending = videoPending,
            quizPending = quizPending,
            humanPending = humanPending,
            gaps = gaps,
            chapters = chapters.size,
            checkedChapters = checked,
            auditedAt = auditedAt
        )
    }
}
