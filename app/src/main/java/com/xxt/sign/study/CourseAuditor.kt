package com.xxt.sign.study

import com.xxt.sign.core.LogStore

/**
 * 真机上跑一遍「这门课还差什么」。
 *
 * 每章一次任务点检查（[StudyApi.checkChapter]），只在发现非视频任务点时才去数测验，
 * 控制请求量：一门课几十章，不能每章都翻 cards 页。
 */
class CourseAuditor(
    private val studyApi: StudyApi,
    private val log: (String, Boolean) -> Unit = { m, ok -> LogStore.add(m, ok) }
) {

    data class Result(
        val audit: CourseAudit,
        /** 需要自己刷视频的章节（直接喂给刷课流水线） */
        val videoChapters: List<StudyChapter>,
        /** 需要自己做的测验章节 */
        val quizChapters: List<StudyChapter>
    )

    suspend fun audit(
        course: StudyCourse,
        chapters: List<StudyChapter>,
        maxChapters: Int = 40,
        onProgress: (String) -> Unit = {}
    ): Result {
        val scope = chapters.take(maxChapters)
        val inputs = mutableListOf<ChapterAuditInput>()
        val video = mutableListOf<StudyChapter>()
        val quiz = mutableListOf<StudyChapter>()

        scope.forEachIndexed { i, ch ->
            onProgress("检查 ${i + 1}/${scope.size}：${course.title} · ${ch.title}")
            val check = runCatching { studyApi.checkChapter(course, ch) }.getOrNull()
            var quizCount: Int? = null

            if (check != null && !check.notOpen && !check.allComplete) {
                val videoLeft = (check.videoTasks - check.videoDone).coerceAtLeast(0)
                if (videoLeft > 0) video.add(ch)
                val otherLeft = (check.allTasks - check.allDone) - videoLeft
                if (otherLeft > 0) {
                    // 只有存在非视频任务点时才去数测验
                    val jobs = runCatching { studyApi.getWorkJobs(course, ch) }.getOrDefault(emptyList())
                    quizCount = jobs.size
                    if (jobs.isNotEmpty()) quiz.add(ch)
                }
            }
            inputs.add(ChapterAuditInput(ch.title, check, quizCount))
        }

        val audit = CourseAuditCalculator.audit(course.courseId, course.title, inputs)
        log("[体检] ${audit.headline()}", audit.allGreen)
        return Result(audit, video, quiz)
    }
}
