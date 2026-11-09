package com.xxt.sign.work

import com.xxt.sign.App
import com.xxt.sign.core.LogStore
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyCourse

/**
 * 挂机模式的自动巡检验验。
 *
 * 设计取舍：
 *  - 学习通的 cards 页没有「列出本章所有测验」的接口，只能逐章问，所以每轮**只扫少量课程**，
 *    用游标轮转（[QuizCursor]），几分钟一轮慢慢把整个账号的课程覆盖完。
 *  - 每轮最多交 [maxQuizzes] 份，且 [QuizEngine] 自带「两卷间隔 ≥90 秒」。
 *  - 已处理过的（含卡在人脸/滑块的）记在 [QuizHandledStore]，不会反复重试。
 */
class QuizAutoRunner(
    private val accountId: String,
    private val studyApi: StudyApi,
    private val workApi: WorkApi,
    private val engine: QuizEngine,
    private val log: (String, Boolean) -> Unit = { m, ok -> LogStore.add(m, ok) }
) {

    data class Pass(
        val results: List<QuizResult>,
        val scannedCourses: Int,
        val scannedChapters: Int,
        val pendingHuman: Int
    ) {
        val empty: Boolean get() = results.isEmpty()
    }

    suspend fun patrol(
        courses: List<StudyCourse>,
        maxCourses: Int = 2,
        maxQuizzes: Int = 2,
        maxChaptersPerCourse: Int = 15
    ): Pass {
        if (courses.isEmpty()) return Pass(emptyList(), 0, 0, 0)

        val cursor = QuizCursor.get(accountId)
        val results = mutableListOf<QuizResult>()
        var scannedCourses = 0
        var scannedChapters = 0
        var pending = 0

        outer@ for (i in 0 until minOf(maxCourses, courses.size)) {
            val course = courses[(cursor + i).mod(courses.size)]
            val chapters = runCatching { studyApi.getChapters(course) }.getOrDefault(emptyList())
            scannedCourses++
            // 已完成的章节跳过；每门课再看最多 15 章，别把一轮拖太长
            val todo = chapters.filter { !it.finished }.take(maxChaptersPerCourse)
            for (ch in todo) {
                if (results.size >= maxQuizzes) break@outer
                scannedChapters++
                val jobs = runCatching { studyApi.getWorkJobs(course, ch) }.getOrDefault(emptyList())
                // 只做 workA（随堂测验）；其它 worktype 不猜，报出来交给人
                val quizzes = jobs.filter { it.isQuiz }
                val others = jobs - quizzes.toSet()
                if (others.isNotEmpty()) {
                    log(
                        "[测验] ${ch.title}：${others.size} 个 work 不是 workA（" +
                            others.map { it.worktype.ifBlank { "(空)" } }.distinct().joinToString(",") +
                            "），不自动作答",
                        false
                    )
                }
                for (job in quizzes) {
                    if (results.size >= maxQuizzes) break@outer
                    if (QuizHandledStore.isHandled(accountId, job.key)) continue
                    val r = runCatching { engine.run(job) }.getOrElse {
                        QuizResult(
                            title = job.chapterTitle,
                            submitted = false,
                            formal = false,
                            answered = 0,
                            total = 0,
                            message = "异常：${it.message ?: it.javaClass.simpleName}",
                            courseTitle = course.title
                        )
                    }
                    // 只有真交了 / 卡在硬阻塞才记账；失败的不标，下一轮重试
                    if (r.submitted || r.needsHuman) {
                        QuizHandledStore.mark(accountId, job.key, r.needsHuman)
                    } else {
                        log("[测验] ${job.chapterTitle} 这份没完成，下一轮重试", false)
                    }
                    if (r.needsHuman) pending++
                    results.add(r)
                }
            }
        }

        QuizCursor.set(accountId, cursor + maxCourses)
        if (results.isNotEmpty()) {
            log("[测验] 巡检：扫描 $scannedCourses 门课 / $scannedChapters 章，处理 ${results.size} 份", true)
        }
        return Pass(results, scannedCourses, scannedChapters, pending)
    }

    /** 只有真正需要答题时才建实例，避免巡检每轮都 new 一堆东西 */
    companion object {
        fun build(
            accountId: String,
            studyApi: StudyApi,
            workApi: WorkApi,
            engine: QuizEngine
        ) = QuizAutoRunner(accountId, studyApi, workApi, engine)
    }
}

/** 课程轮转游标 */
object QuizCursor {
    private fun sp() = App.ctx.getSharedPreferences("quiz_cursor", 0)

    fun get(accountId: String): Int = sp().getInt(accountId, 0)

    fun set(accountId: String, value: Int) {
        sp().edit().putInt(accountId, value).apply()
    }
}
