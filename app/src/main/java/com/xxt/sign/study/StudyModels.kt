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

/** 文档任务点完成结果 */
enum class DocumentResult {
    /** 刚做完 */
    DONE,
    /** 服务端回「考核点已经完成」 */
    ALREADY,
    /** 参数不对或网络失败 */
    FAILED
}

/** 一个文档任务点：GET 一次就算完成，最容易自动化 */
data class DocumentJob(
    val jobId: String,
    val jtoken: String,
    val name: String,
    /** 完成接口要的 knowledgeid：优���用 otherInfo 里的 nodeId，缺了才回落到章节目录 id */
    val knowledgeId: String = ""
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

/**
 * 一个任务点（视频/文档/测验等）的完成情况，用于“检查任务点”。
 *
 * 类型判据只用原始字段，**不看标题**（实测 145 个 workA 标题五花八门：随堂测试、章节测试、
 * 考核通关，甚至有直接写章名的）：
 *  - `type`：video / document / workid / audio
 *  - `property.module` + `property.worktype`：workid 里 module=work 且 worktype=workA 才是章节测验
 */
data class CardTask(
    val key: String,
    val type: String,
    val passed: Boolean,
    val module: String = "",
    val worktype: String = "",
    val name: String = "",
    val jtoken: String = "",
    val knowledgeId: String = ""
) {
    val isVideo: Boolean get() = type.equals("video", ignoreCase = true)
    val isDocument: Boolean get() = type.equals("document", ignoreCase = true)
    val isAudio: Boolean get() = type.equals("audio", ignoreCase = true)
    val isWork: Boolean get() = type.equals("workid", ignoreCase = true)

    /** 章节测验（随堂测验）= 能自动做，不是作业 */
    val isQuiz: Boolean get() = isWork && worktype.equals(WORKTYPE_QUIZ, ignoreCase = true)

    /** workid 但不是 workA：作业/考试/待确认的新类型，一律按原始值上报，不猜 */
    val isOtherWork: Boolean get() = isWork && !isQuiz

    companion object {
        /** 实测：全账号 145 个 workid 全是 workA，module 全是 work */
        const val WORKTYPE_QUIZ = "workA"
    }
}

/** 一个章节里所有任务点 + 视频任务点的完成统计，按类型分开数 */
data class ChapterCheck(
    val allTasks: Int,
    val allDone: Int,
    val videoTasks: Int,
    val videoDone: Int,
    val notOpen: Boolean = false,
    /** 已经确认完成的视频 jobId，用于任务完成后本地增量更新 */
    val doneVideoJobs: Set<String> = emptySet(),
    val documentTasks: Int = 0,
    val documentDone: Int = 0,
    /** workA 章节测验（能自动做） */
    val quizTasks: Int = 0,
    val quizDone: Int = 0,
    val audioTasks: Int = 0,
    val audioDone: Int = 0,
    /** 非 workA 的 work 任务点：原始 worktype → 待做个数 */
    val otherWorks: Map<String, Int> = emptyMap(),
    /** 认不出来的附件：原始 type → 待做个数 */
    val unknownTasks: Map<String, Int> = emptyMap(),
    /** 这章的结论来自章节目录的 icon_yiwanc，不是逐卡扫描 */
    val fromIcon: Boolean = false,
    /** 待做的文档任务点，直接带着 jtoken，省掉再翻一遍 cards 页 */
    val pendingDocuments: List<DocumentJob> = emptyList()
) {
    val allComplete: Boolean get() = !notOpen && allTasks == allDone
    val videoComplete: Boolean get() = !notOpen && videoTasks == videoDone
    val videoPending: Boolean get() = !notOpen && videoTasks > videoDone

    val videoLeft: Int get() = (videoTasks - videoDone).coerceAtLeast(0)
    val documentLeft: Int get() = (documentTasks - documentDone).coerceAtLeast(0)
    val quizLeft: Int get() = (quizTasks - quizDone).coerceAtLeast(0)
    val audioLeft: Int get() = (audioTasks - audioDone).coerceAtLeast(0)
    val otherWorkLeft: Int get() = otherWorks.values.sum()
    val unknownLeft: Int get() = unknownTasks.values.sum()

    /** 真需要本人出手的：非 workA 的 work、音频（未实现）、认不出的类型 */
    val humanLeft: Int get() = otherWorkLeft + audioLeft + unknownLeft

    /** 它自己能做完的缺口：视频 + 文档 + 章节测验 */
    val autoLeft: Int get() = videoLeft + documentLeft + quizLeft
}

/**
 * 把一章的附件统计成 [ChapterCheck]。纯计算，单测直接喂 [CardTask]。
 *
 * 分类只看原始字段：type=video/document/workid/audio，workid 再看 property.worktype。
 * 实测这个账号 897 个任务点只有这三种类型，145 个 workid 的 worktype 全是 workA，
 * 标题五花八门（随堂测试 / 章节测试 / 考核通关 / 直接写章名），绝不能拿来判类型。
 */
fun summarizeChapter(tasks: Collection<CardTask>): ChapterCheck {
    val all = tasks.toList()
    val video = all.filter { it.isVideo }
    val docs = all.filter { it.isDocument }
    val quiz = all.filter { it.isQuiz }
    val audio = all.filter { it.isAudio }
    val others = all.filter { it.isOtherWork }
    val unknown = all.filter { !it.isVideo && !it.isDocument && !it.isAudio && !it.isWork }
    return ChapterCheck(
        allTasks = all.size,
        allDone = all.count { it.passed },
        videoTasks = video.size,
        videoDone = video.count { it.passed },
        doneVideoJobs = video.filter { it.passed }.map { it.key }.toSet(),
        documentTasks = docs.size,
        documentDone = docs.count { it.passed },
        quizTasks = quiz.size,
        quizDone = quiz.count { it.passed },
        audioTasks = audio.size,
        audioDone = audio.count { it.passed },
        otherWorks = others.filter { !it.passed }
            .groupingBy { it.worktype.ifBlank { "(空)" } }.eachCount(),
        unknownTasks = unknown.filter { !it.passed }
            .groupingBy { it.type }.eachCount(),
        pendingDocuments = docs.filter { !it.passed }
            .map { DocumentJob(it.key, it.jtoken, it.name, it.knowledgeId) }
    )
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
