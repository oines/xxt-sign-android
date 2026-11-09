package com.xxt.sign.study

import com.xxt.sign.core.LogStore
import kotlinx.coroutines.CancellationException

/**
 * 真机上跑一遍「这门课还差什么」。
 *
 * 三条硬规则（都是真机踩出来的）：
 *  1. **不截断章节**：之前 `maxChapters = 40` 把体育课 91 章砍成 40 章，分母 150 ≠ 真的 392，
 *     所以现在按传入的章节全扫（只有 [MAX_CHAPTERS] 的防御上限），并打日志说明扫了多少章。
 *  2. **章节图标优先**：章节目录里带 `icon_yiwanc` 的章节直接算全绿，**不再逐卡扫描**
 *     （体育课 157 个文档早就做完了，`isPassed` 却回 false，不信图标就会永远差 157 个）。
 *  3. **不再为「数测验」额外请求**：一章的 cards 页里就有 workid 卡片，类型信息一次到手；
 *     原来每章额外拉一遍 getWorkJobs 既慢又把文档误判成人工作业。
 */
class CourseAuditor(
    private val studyApi: StudyApi,
    private val log: (String, Boolean) -> Unit = { m, ok -> LogStore.add(m, ok) }
) {

    /** 一个待做的文档任务点 + 它所属章节 */
    data class DocTarget(val chapter: StudyChapter, val job: DocumentJob)

    data class Result(
        val audit: CourseAudit,
        /** 需要自己刷视频的章节（直接喂给刷课流水线） */
        val videoChapters: List<StudyChapter>,
        /** 还有 workA 测验要做的章节 */
        val quizChapters: List<StudyChapter>,
        /** 待做文档任务点，带着 jtoken，文档阶段不用再翻 cards 页 */
        val documents: List<DocTarget>
    )

    suspend fun audit(
        course: StudyCourse,
        chapters: List<StudyChapter>,
        onProgress: (String) -> Unit = {}
    ): Result {
        val scope = if (chapters.size > MAX_CHAPTERS) chapters.take(MAX_CHAPTERS) else chapters
        if (chapters.size > MAX_CHAPTERS) {
            log("[体检] ${course.title} 章节 ${chapters.size} 张，只扫前 $MAX_CHAPTERS 章", false)
        }

        val inputs = mutableListOf<ChapterAuditInput>()
        val video = mutableListOf<StudyChapter>()
        val quiz = mutableListOf<StudyChapter>()
        val documents = mutableListOf<DocTarget>()
        var byIcon = 0
        var scanned = 0
        // 类型分布（只统计真正扫过卡片的章节，图标直接判完成的不算）
        var vT = 0
        var vD = 0
        var dT = 0
        var dD = 0
        var wT = 0
        var wD = 0
        var aT = 0
        var uT = 0
        val otherWorkTypes = mutableMapOf<String, Int>()

        scope.forEachIndexed { i, ch ->
            onProgress("检查 ${i + 1}/${scope.size}：${ch.title}")
            // 1) 章节目录的已完成图标：最可信，也最省请求
            if (ch.finished) {
                val n = ch.jobCount.coerceAtLeast(0)
                inputs.add(ChapterAuditInput(ch.title, ChapterCheck(n, n, 0, 0, fromIcon = true)))
                byIcon++
                return@forEachIndexed
            }
            // 2) 未完成的章节才逐卡扫描
            val check = try {
                studyApi.checkChapter(course, ch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("[体检] ${course.title} · ${ch.title} 检查失败：${e.message}", false)
                null
            }
            if (check != null && !check.notOpen && !check.allComplete) {
                if (check.videoLeft > 0) video.add(ch)
                if (check.quizLeft > 0) quiz.add(ch)
                check.pendingDocuments.forEach { documents.add(DocTarget(ch, it)) }
            }
            if (check != null && !check.notOpen) {
                scanned++
                vT += check.videoTasks
                vD += check.videoDone
                dT += check.documentTasks
                dD += check.documentDone
                wT += check.quizTasks
                wD += check.quizDone
                aT += check.audioTasks
                uT += check.unknownTasks.values.sum()
                check.otherWorks.forEach { (t, n) -> otherWorkTypes[t] = (otherWorkTypes[t] ?: 0) + n }
            }
            inputs.add(ChapterAuditInput(ch.title, check))
        }

        val audit = CourseAuditCalculator.audit(course.courseId, course.title, inputs)
        log("[体检] ${audit.headline()}", audit.allGreen)
        // 真机取证口子：类型分布一眼能看出「需本人」到底是哪类
        log(
            "[体检] ${course.title} 口径：章节 ${audit.checkedChapters}/${chapters.size}（按图标直接判完成 $byIcon 章）" +
                " · 任务点 ${audit.taskPoints}，已完成 ${audit.doneTaskPoints}" +
                " · 待做 视频 ${audit.videoPending} / 文档 ${audit.documentPending}" +
                " / 测验 ${audit.quizPending} / 需本人 ${audit.humanPending}",
            audit.humanPending == 0
        )
        val humanTypes = audit.gaps.filter { it.kind == CourseAudit.Kind.HUMAN }
        humanTypes.take(5).forEach { log("[体检] 需本人明细 · ${it.chapterTitle}：${it.detail}", false) }
        // 用户要的「按类型分组」：一眼能看出「需本人」到底是哪类任务点
        log(
            "[体检] 类型分布（逐卡扫描 $scanned 章）：" +
                "video $vD/$vT · document $dD/$dT · workid(workA) $wD/$wT" +
                (if (otherWorkTypes.isEmpty()) "" else " · 其它work $otherWorkTypes") +
                (if (aT > 0) " · audio $aT" else "") +
                (if (uT > 0) " · 未知类型 $uT" else ""),
            otherWorkTypes.isEmpty() && uT == 0
        )

        return Result(audit, video, quiz, documents)
    }

    companion object {
        /** 防御上限：正常一门课不会超过这个章数 */
        const val MAX_CHAPTERS = 400
    }
}
