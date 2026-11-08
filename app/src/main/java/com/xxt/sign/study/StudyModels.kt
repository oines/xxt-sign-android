package com.xxt.sign.study

/** 一门课（学习页需要的字段比签到用的 Course 多一个 cpi） */
data class StudyCourse(
    val courseId: String,
    val clazzId: String,
    val cpi: String,
    val title: String,
    val teacher: String = ""
) {
    val key: String get() = "$courseId:$clazzId"
}

/** 课程下的一个章节 */
data class StudyChapter(
    val id: String,
    val title: String,
    val jobCount: Int,
    val finished: Boolean,
    val needUnlock: Boolean
)

/** 一个文档任务点：GET 一次就算完成，最容易自动化 */
data class DocumentJob(
    val jobId: String,
    val jtoken: String,
    val name: String
)

/** 一个音频任务点（接口待探测，先只识别） */
data class AudioJob(
    val jobId: String,
    val objectId: String,
    val name: String
)

/** 一个视频任务点 */
data class VideoJob(
    val jobId: String,
    val name: String,
    val objectId: String,
    val playTimeMs: Long,
    val rt: String,
    val otherInfo: String,
    val attDuration: String,
    val attDurationEnc: String,
    val faceCaptureEnc: String
)

data class VideoStatus(val dtoken: String, val duration: Int)

data class VideoLogResult(val passed: Boolean, val code: Int, val body: String)

data class ChapterJobs(val notOpen: Boolean, val jobs: List<VideoJob>)

/** 一个任务点（视频/文档/测验等）的完成情况，用于“检查任务点” */
data class CardTask(val key: String, val isVideo: Boolean, val passed: Boolean)

/** 一个章节里所有任务点 + 视频任务点的完成统计 */
data class ChapterCheck(
    val allTasks: Int,
    val allDone: Int,
    val videoTasks: Int,
    val videoDone: Int,
    val notOpen: Boolean = false,
    /** 已经确认完成的视频 jobId，用于任务完成后本地增量更新 */
    val doneVideoJobs: Set<String> = emptySet()
) {
    val allComplete: Boolean get() = !notOpen && allTasks == allDone
    val videoComplete: Boolean get() = !notOpen && videoTasks == videoDone
    val videoPending: Boolean get() = !notOpen && videoTasks > videoDone
}

/** 单个视频任务在详情页里的状态 */
enum class StudyTaskStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class StudyTaskItem(
    val key: String,
    val courseTitle: String,
    val chapterTitle: String,
    val name: String,
    val status: StudyTaskStatus = StudyTaskStatus.PENDING,
    val progress: Int = 0,
    val message: String = ""
)

/** 一次刷课任务：哪个账号、哪些课、每门课勾了哪些章节、多少并发、几倍速 */
data class StudyPlan(
    val accountId: String,
    val courses: List<StudyCourse>,
    /** course.key -> 选中的 chapter id 集合 */
    val chapterSelection: Map<String, Set<String>>,
    val concurrency: Int,
    val speed: Float
)

data class VideoRunResult(
    val ok: Boolean,
    val progress: Int,
    val message: String,
    /** 卡在弹题/需要本人处理，不算普通失败 */
    val needsHuman: Boolean = false,
    val popQuiz: PopQuizResult? = null
) {
    /** 写进任务卡片/日志的一句话 */
    fun messageWithPop(): String {
        val extra = popQuiz?.summary().orEmpty()
        return if (extra.isBlank()) message else "$message · $extra"
    }
}

/** 刷课进度（UI / 通知共用） */
data class StudyState(
    val running: Boolean = false,
    val status: String = "就绪",
    val current: String = "",
    val doneChapters: Int = 0,
    val totalChapters: Int = 0,
    val failedTasks: Int = 0,
    val finished: Boolean = false
) {
    val progress: Int
        get() = if (totalChapters <= 0) 0 else (doneChapters * 100 / totalChapters).coerceIn(0, 100)
}

data class StudySummary(val doneChapters: Int, val failedTasks: Int)
