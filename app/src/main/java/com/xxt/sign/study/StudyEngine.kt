package com.xxt.sign.study

import com.xxt.sign.core.LogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

/**
 * 只做「看视频」：拉章节 → 取视频任务点 → 按倍速/并发上报进度。
 * 逻辑与参考实现保持一致：章节最多重试 5 次、首次 isdrag=4 尝试秒过、
 * 每 30–90 秒上报一次、403 时刷新状态 + 重新登录。
 */
class StudyEngine(
    private val api: StudyApi,
    private val plan: StudyPlan,
    private val onState: (StudyState) -> Unit,
    private val onTask: (StudyTaskItem) -> Unit,
    /** 每个视频一个弹题桥；没抓包/没配置时返回 [PopQuizBridge.NONE] */
    private val popQuizBridge: (StudyCourse, VideoJob) -> PopQuizBridge = { _, _ -> PopQuizBridge.NONE },
    /** 弹题作答：复用章节测验那条「题库优先 + 大模型兜底」链路 */
    private val popQuizSolver: suspend (PopQuizQuestion) -> String = { "" }
) {
    private val done = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private var total = 0

    private enum class ChapterOutcome { DONE, NOT_OPEN, ERROR }

    suspend fun run(): StudySummary {
        val targets = mutableListOf<Pair<StudyCourse, StudyChapter>>()

        for (course in plan.courses) {
            onState(StudyState(running = true, status = "读取章节：${course.title}"))
            val chapters = try {
                api.getChapters(course)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogStore.add("[刷课] ${course.title} 读取章节失败：${e.message}", false)
                continue
            }
            val selected = plan.chapterSelection[course.key].orEmpty()
            val pending = chapters.filter { it.id in selected && !it.finished }
            LogStore.add(
                "[刷课] ${course.title}：选中 ${selected.size} 章，待刷 ${pending.size} 章",
                pending.isNotEmpty()
            )
            targets += pending.map { course to it }
        }

        total = targets.size
        onState(
            StudyState(
                running = true,
                status = if (total == 0) "没有待刷章节" else "共 $total 个待刷章节",
                totalChapters = total
            )
        )
        if (targets.isEmpty()) return StudySummary(0, 0)

        val semaphore = Semaphore(plan.concurrency.coerceIn(1, 8))
        coroutineScope {
            targets.map { (course, chapter) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        processTarget(course, chapter)
                    }
                }
            }.awaitAll()
        }
        return StudySummary(done.get(), failed.get())
    }

    private suspend fun processTarget(course: StudyCourse, chapter: StudyChapter) {
        var ok = false
        var lastReason = "任务失败"

        for (attempt in 1..MAX_TRIES) {
            currentCoroutineContext().ensureActive()
            val outcome = try {
                runChapter(course, chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                lastReason = "会话失效"
                val relogged = runCatching { api.relogin() }.getOrDefault(false)
                if (!relogged) break
                ChapterOutcome.ERROR
            } catch (e: Exception) {
                lastReason = e.message ?: e.javaClass.simpleName
                ChapterOutcome.ERROR
            }

            when (outcome) {
                ChapterOutcome.DONE -> {
                    ok = true
                    break
                }
                ChapterOutcome.NOT_OPEN -> lastReason = "章节未开放"
                ChapterOutcome.ERROR -> Unit
            }

            if (attempt < MAX_TRIES) {
                LogStore.add(
                    "[刷课] ${course.title} · ${chapter.title} 重试 $attempt/$MAX_TRIES：$lastReason",
                    false
                )
                delay(RETRY_INTERVAL_MS)
            }
        }

        if (!ok) failed.incrementAndGet()
        val finished = done.incrementAndGet()
        onState(
            StudyState(
                running = true,
                status = "已完成 $finished/$total 章" +
                    if (failed.get() > 0) "（失败 ${failed.get()}）" else "",
                current = "${course.title} · ${chapter.title}",
                doneChapters = finished,
                totalChapters = total,
                failedTasks = failed.get()
            )
        )
    }

    private suspend fun runChapter(course: StudyCourse, chapter: StudyChapter): ChapterOutcome {
        onState(
            StudyState(
                running = true,
                status = "读取任务点：${course.title} · ${chapter.title}",
                current = "${course.title} · ${chapter.title}",
                doneChapters = done.get(),
                totalChapters = total,
                failedTasks = failed.get()
            )
        )
        val jobs = api.getVideoJobs(course, chapter)
        if (jobs.notOpen) return ChapterOutcome.NOT_OPEN
        if (jobs.jobs.isEmpty()) {
            LogStore.add("[刷课] ${course.title} · ${chapter.title} 无视频任务", true)
            return ChapterOutcome.DONE
        }

        var allOk = true
        videoLoop@ for ((index, job) in jobs.jobs.withIndex()) {
            currentCoroutineContext().ensureActive()
            LogStore.add(
                "[刷课] ${course.title} · ${chapter.title} · ${job.name}" +
                    "（${index + 1}/${jobs.jobs.size}）",
                true
            )

            val key = "${course.key}#${chapter.id}#${job.jobId}"
            val base = StudyTaskItem(
                key = key,
                courseTitle = course.title,
                chapterTitle = chapter.title,
                name = job.name
            )
            onTask(base.copy(status = StudyTaskStatus.RUNNING, message = "准备中"))

            var lastProgress = 0
            val result = runVideo(course, job) { pct, msg ->
                lastProgress = pct
                onTask(
                    base.copy(
                        status = StudyTaskStatus.RUNNING,
                        progress = pct,
                        message = msg
                    )
                )
            }

            // 弹题统计要进任务卡片和日志：视频中途弹题 2 道，答上 1 道
            val message = result.messageWithPop()
            if (result.ok) {
                onTask(
                    base.copy(
                        status = StudyTaskStatus.DONE,
                        progress = 100,
                        message = message
                    )
                )
                LogStore.add("[刷课] 视频完成：${job.name}" + if (message != "完成") "（$message）" else "", true)
            } else {
                allOk = false
                onTask(
                    base.copy(
                        status = StudyTaskStatus.FAILED,
                        progress = maxOf(lastProgress, result.progress),
                        message = message
                    )
                )
                LogStore.add("[刷课] 视频未完成：${job.name}（$message）", false)
                // 卡在弹题 = 需要本人，不是普通失败；别让它在重试里空转
                if (result.needsHuman) {
                    LogStore.add("[刷课] ${job.name}：这道弹题没答上，视频可能停在这里，已跳过", false)
                    break@videoLoop
                }
            }
        }
        return if (allOk) ChapterOutcome.DONE else ChapterOutcome.ERROR
    }

    private suspend fun runVideo(
        course: StudyCourse,
        job: VideoJob,
        onProgress: (Int, String) -> Unit
    ): VideoRunResult {
        var status: VideoStatus = api.videoStatus(job.objectId) ?: run {
            LogStore.add("[刷课] 取视频信息失败：${job.name}", false)
            return VideoRunResult(false, 0, "取视频信息失败")
        }

        var duration = status.duration
        if (duration <= 0) return VideoRunResult(false, 0, "视频时长异常")

        var playTime = min(duration, (job.playTimeMs / 1000L).toInt().coerceAtLeast(0))
        val rtCandidates = rtCandidates(job)
        var chosenRt = rtCandidates.firstOrNull().orEmpty()
        var lastPercent = -1

        fun emitProgress(force: Boolean = false) {
            val pct = ((playTime.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
            if (force || pct != lastPercent) {
                lastPercent = pct
                onProgress(pct, "正在播放 $pct%")
            }
        }

        emitProgress(force = true)

        // 与上游 study_video 一致：第一次按“已看完”上报，isdrag=4
        for (rt in rtCandidates) {
            val r = api.logVideo(course, job, status, duration, duration, 4, "Video", rt)
            if (r.passed) {
                onProgress(100, "完成")
                return VideoRunResult(true, 100, "完成")
            }
            if (r.code != 403) {
                chosenRt = rt
                break
            }
        }

        var wait = Random.nextDouble(30.0, 90.0).toInt()
        var lastLogged = 0
        var lastTick = System.currentTimeMillis()
        var forbidden = 0
        var endNotified = false
        var endStartedAt = 0L

        // ---------------- 视频中途弹题 ----------------
        // 学习通会在播放到某个点弹题，答不对视频不往下播。这里每 10 秒问一次接口，
        // 解出来就答；答不上/没接口就让播放进度自己说话（连续不动 = 卡住了）。
        val bridge = popQuizBridge(course, job)
        var popTotal = 0
        var popAnswered = 0
        var popTimedOut = false
        var popGaveUp: String? = null
        var lastPollAt = 0L
        var lastPlayTimeSeen = playTime
        var stallRounds = 0
        var quizDeadline = 0L

        fun popResult() = PopQuizResult(
            total = popTotal,
            answered = popAnswered,
            skipped = popTotal - popAnswered,
            timedOut = popTimedOut,
            needsHuman = popGaveUp != null,
            attempts = emptyList()
        )

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()

            // 客户端进度到末尾后，服务端可能还要几秒才确认；但不能无限等下去。
            if (playTime >= duration) {
                if (endStartedAt == 0L) endStartedAt = now
                if (now - endStartedAt >= END_TIMEOUT_MS) {
                    LogStore.add(
                        "[刷课] ${job.name} 末尾等待服务端确认超时（${END_TIMEOUT_MS / 1000} 秒）",
                        false
                    )
                    return VideoRunResult(false, 100, "服务端未确认完成")
                }
            } else {
                endStartedAt = 0L
            }

            // 上游原逻辑：play_time == duration 时每秒继续上报，直到 isPassed=true；
            // 这里额外用 END_TIMEOUT_MS 兜底，避免服务端一直不确认时卡死。
            if (playTime - lastLogged >= wait || playTime >= duration) {
                val r = logWithRtFallback(
                    course, job, status, playTime, duration, 3, chosenRt, rtCandidates
                )
                when {
                    r.passed -> {
                        onProgress(100, "完成")
                        return VideoRunResult(
                            ok = true,
                            progress = 100,
                            message = "完成",
                            popQuiz = popResult()
                        )
                    }

                    r.code == 403 -> {
                        forbidden++
                        if (forbidden > MAX_FORBIDDEN_RETRY) {
                            return VideoRunResult(false, lastPercent.coerceAtLeast(0), "连续 403")
                        }
                        LogStore.add("[刷课] 403，刷新会话后重试（$forbidden/$MAX_FORBIDDEN_RETRY）", false)
                        delay(2000)
                        val relogged = try {
                            api.relogin()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogStore.add("[刷课] 重新登录失败：${e.message}", false)
                            false
                        }
                        if (!relogged) {
                            return VideoRunResult(false, lastPercent.coerceAtLeast(0), "重新登录失败")
                        }
                        val fresh = try {
                            api.videoStatus(job.objectId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogStore.add("[刷课] 刷新视频状态失败：${e.message}", false)
                            null
                        }
                        if (fresh == null) {
                            return VideoRunResult(false, lastPercent.coerceAtLeast(0), "刷新视频状态失败")
                        }
                        status = fresh
                        duration = fresh.duration
                        playTime = min(playTime, duration)
                        lastLogged = playTime
                        wait = Random.nextDouble(30.0, 90.0).toInt()
                        endStartedAt = 0L
                    }

                    r.code != 200 -> {
                        LogStore.add("[刷课] 上报失败 HTTP ${r.code}", false)
                        return VideoRunResult(false, lastPercent.coerceAtLeast(0), "HTTP ${r.code}")
                    }

                    else -> {
                        wait = Random.nextDouble(30.0, 90.0).toInt()
                        lastLogged = playTime
                        if (playTime >= duration) {
                            if (!endNotified) {
                                endNotified = true
                                LogStore.add(
                                    "[刷课] ${job.name} 客户端进度已完成，服务端未完成，持续播放等待服务端结束",
                                    false
                                )
                            }
                            onProgress(
                                100,
                                "客户端进度已完成，服务端未完成，持续播放等待服务端结束"
                            )
                        }
                    }
                }
            }

            val tickNow = System.currentTimeMillis()
            val delta = ((tickNow - lastTick) / 1000.0 * plan.speed).toInt()
            if (delta > 0) {
                playTime = min(duration, playTime + delta)
                lastTick = tickNow
                emitProgress()
            }

            // ---- 弹题轮询 ----
            if (tickNow - lastPollAt >= POP_POLL_INTERVAL_MS) {
                lastPollAt = tickNow
                if (playTime > lastPlayTimeSeen) {
                    lastPlayTimeSeen = playTime
                    stallRounds = 0
                } else {
                    stallRounds++
                }

                val questions = if (bridge.configured) {
                    runCatching { bridge.poll(job, playTime) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                if (questions.isEmpty()) {
                    val outcome = PopQuizPolicy.decide(
                        bridge, emptyList(), emptyList(), timedOut = false, stalledRounds = stallRounds
                    )
                    (outcome.decision as? PopQuizDecision.GiveUp)?.let { giveUp ->
                        popGaveUp = giveUp.reason
                        LogStore.add("[刷课] ${job.name}：${giveUp.reason}", false)
                        onProgress(lastPercent.coerceAtLeast(0), "卡在弹题，标为需要人工")
                        return VideoRunResult(
                            ok = false,
                            progress = lastPercent.coerceAtLeast(0),
                            message = giveUp.reason,
                            needsHuman = true,
                            popQuiz = popResult()
                        )
                    }
                } else {
                    if (quizDeadline == 0L) quizDeadline = tickNow + POP_QUIZ_TIMEOUT_MS
                    val answers = questions.map { q ->
                        val left = quizDeadline - System.currentTimeMillis()
                        val ans = if (left > 0) {
                            kotlinx.coroutines.withTimeoutOrNull(left) { popQuizSolver(q) } ?: ""
                        } else ""
                        if (ans.isNotBlank()) {
                            runCatching { bridge.submit(job, q, ans) }.getOrDefault(false)
                            ans
                        } else ""
                    }
                    val fresh = questions.zip(answers).filter { it.second.isNotBlank() }
                    popTotal += questions.size
                    popAnswered += fresh.size
                    val timedOut = tickNow >= quizDeadline
                    popTimedOut = popTimedOut || timedOut

                    LogStore.add(
                        "[刷课] ${job.name}：视频中途弹题 ${questions.size} 道，答上 ${fresh.size} 道" +
                            if (timedOut) "（超时）" else "",
                        fresh.size == questions.size
                    )

                    val outcome = PopQuizPolicy.decide(
                        bridge, questions, answers, timedOut = timedOut, stalledRounds = stallRounds
                    )
                    (outcome.decision as? PopQuizDecision.GiveUp)?.let { giveUp ->
                        popGaveUp = giveUp.reason
                        LogStore.add("[刷课] ${job.name}：${giveUp.reason}", false)
                        return VideoRunResult(
                            ok = false,
                            progress = lastPercent.coerceAtLeast(0),
                            message = giveUp.reason,
                            needsHuman = true,
                            popQuiz = popResult()
                        )
                    }
                    if (fresh.size == questions.size) quizDeadline = 0L
                }
            }

            delay(1000)
        }
        return VideoRunResult(false, lastPercent.coerceAtLeast(0), "已取消")
    }

    /** 403 时先换 rt，再走会话恢复；与参考实现一致 */
    private suspend fun logWithRtFallback(
        course: StudyCourse,
        job: VideoJob,
        status: VideoStatus,
        playTime: Int,
        duration: Int,
        isdrag: Int,
        preferredRt: String,
        candidates: List<String>
    ): VideoLogResult {
        val order = (listOf(preferredRt) + candidates).filter { it.isNotBlank() }.distinct()
            .ifEmpty { listOf("") }
        var last: VideoLogResult? = null
        for (rt in order) {
            val r = api.logVideo(course, job, status, playTime, duration, isdrag, "Video", rt)
            last = r
            if (r.passed || r.code != 403) return r
        }
        return last ?: VideoLogResult(false, 0, "")
    }

    /** 优先用卡片自带的 rt；没有就从 otherInfo 的 -rt_1d/-rt_1 推断 */
    private fun rtCandidates(job: VideoJob): List<String> {
        val direct = job.rt.trim()
        if (direct.isNotBlank()) return listOf(direct)
        val flag = Regex("-rt_([1d])").find(job.otherInfo)?.groupValues?.getOrNull(1)
        return when (flag) {
            "d" -> listOf("0.9")
            "1" -> listOf("1")
            else -> listOf("0.9", "1")
        }
    }

    companion object {
        private const val MAX_TRIES = 5
        private const val MAX_FORBIDDEN_RETRY = 2
        private const val RETRY_INTERVAL_MS = 1000L

        /** 弹题轮询间隔 */
        private const val POP_POLL_INTERVAL_MS = 10_000L

        /** 一道弹题最多给多久（答不上就跳过，别把整门课卡死） */
        private const val POP_QUIZ_TIMEOUT_MS = 60_000L

        /** 客户端进度到 100% 后，最多再等服务端确认多久 */
        private const val END_TIMEOUT_MS = 3 * 60 * 1000L
    }}
