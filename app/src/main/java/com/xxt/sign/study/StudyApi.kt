package com.xxt.sign.study

import com.xxt.sign.net.CxSession
import com.xxt.sign.core.LogStore
import com.xxt.sign.net.enc
import com.xxt.sign.work.WorkJob
import com.xxt.sign.net.get
import com.xxt.sign.net.postForm
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 简单的请求限速器：同一账号的请求串行排队，保证最小间隔 + 随机抖动 */
class RequestLimiter(
    private val minIntervalMs: Long,
    private val randomMaxMs: Long = 0L
) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun acquire(extraRandomMs: Long = randomMaxMs) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val wait = (last + minIntervalMs - now).coerceAtLeast(0L)
            if (wait > 0) delay(wait)
            if (extraRandomMs > 0) delay(Random.nextLong(extraRandomMs))
            last = System.currentTimeMillis()
        }
    }
}

class SessionExpiredException : Exception("会话已失效，请在账号页重新登录")

/**
 * 刷课所需的网页接口。
 *
 * 与签到模块分开：这里全部走 [CxSession.pcClient]（桌面 UA），
 * 因为 mooc1 / mooc2-ans 返回的是 PC 网页。
 */
class StudyApi(private val session: CxSession) {

    /** 队列里跑测验要复用同一个会话 */
    val cx: CxSession get() = session

    private val c get() = session.pcClient
    private val limiter = RequestLimiter(500L, 200L)
    private val checkLimiter = RequestLimiter(100L, 50L)
    private val videoLimiter = RequestLimiter(2000L, 2000L)

    /**
     * 文档完成接口专用的短超时客户端。
     * 共享客户端的 callTimeout 是 50s，一个卡住的连接能把整段流水分拖垮；
     * 这里 15s 就丢，调用方再包一层 withTimeoutOrNull 就绝不会无限期挂着。
     */
    private val docClient by lazy {
        c.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** 有些老账号 uid/fid 只存在于 Cookie 里 */
    private val uid: String
        get() = session.uid.ifBlank { session.jar.value("_uid") }

    private val fid: String
        get() = session.schoolId.ifBlank { session.jar.value("fid") }.ifBlank { "0" }

    // ------------------------------------------------------------ 课程

    suspend fun getCourses(): List<StudyCourse> {
        val out = LinkedHashMap<String, StudyCourse>()
        courseList("0").forEach { out[it.key] = it }

        // 有二级文件夹的账号：先读 interaction 页，再按文件夹拉一遍
        runCatching {
            limiter.acquire()
            c.get(
                "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction",
                mapOf("Referer" to INTERACTION_REFERER)
            )
        }.getOrNull()?.let { r ->
            checkLogin(r.body)
            for (folderId in StudyParser.parseFolders(r.body)) {
                courseList(folderId).forEach { out[it.key] = it }
            }
        }
        return out.values.toList()
    }

    private suspend fun courseList(folderId: String): List<StudyCourse> {
        limiter.acquire()
        val r = c.postForm(
            "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata",
            mapOf(
                "courseType" to "1",
                "courseFolderId" to folderId,
                "query" to "",
                "superstarClass" to "0"
            ),
            mapOf("Referer" to INTERACTION_REFERER)
        )
        checkLogin(r.body)
        return StudyParser.parseCourses(r.body)
    }

    // ------------------------------------------------------------ 章节

    suspend fun getChapters(course: StudyCourse): List<StudyChapter> {
        limiter.acquire()
        val url = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse" +
            "?courseid=${course.courseId}&clazzid=${course.clazzId}" +
            "&cpi=${enc(course.cpi.ifBlank { "0" })}&ut=s"
        val r = c.get(url)
        checkLogin(r.body)
        return StudyParser.parseChapters(r.body)
    }

    // -------------------------------------------------------- 视频任务

    suspend fun getVideoJobs(course: StudyCourse, chapter: StudyChapter): ChapterJobs {
        val jobs = LinkedHashMap<String, VideoJob>()
        for (num in 0..6) {
            limiter.acquire()
            val r = c.get(
                "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards" +
                    "?clazzid=${course.clazzId}&courseid=${course.courseId}" +
                    "&knowledgeid=${chapter.id}&ut=s&cpi=${course.cpi.ifBlank { "0" }}" +
                    "&v=2025-0424-1038-3&mooc2=1&num=$num"
            )
            checkLogin(r.body)
            val parsed = StudyParser.parseCards(r.body)
            if (parsed.notOpen) return ChapterJobs(true, emptyList())
            parsed.jobs.forEach { jobs[it.jobId] = it }
        }

        // 没有任务点时访问一次空页面，和网页端行为一致（不计视频任务）
        if (jobs.isEmpty()) {
            runCatching {
                limiter.acquire()
                c.get(
                    "https://mooc1.chaoxing.com/mooc-ans/mycourse/studentstudyAjax",
                    mapOf(
                        "courseId" to course.courseId,
                        "clazzid" to course.clazzId,
                        "chapterId" to chapter.id,
                        "cpi" to course.cpi.ifBlank { "0" },
                        "verificationcode" to "",
                        "mooc2" to "1",
                        "microTopicId" to "0",
                        "editorPreview" to "0"
                    )
                )
            }
        }
        return ChapterJobs(false, jobs.values.toList())
    }

    /**
     * 拉一个章节里的测验任务点。
     * 与视频共用 knowledge/cards 页，只是挑 type == "workid" 的卡片，
     * 并额外带上 mArg.defaults.ktoken（取题接口要它）。
     */
    suspend fun getWorkJobs(course: StudyCourse, chapter: StudyChapter): List<WorkJob> {
        val out = LinkedHashMap<String, WorkJob>()
        var ktoken = ""
        var cpi = course.cpi.ifBlank { "0" }
        // 一个章节可能有多张卡片：只拉 num=0 会漏一大半。
        // 退出条件只看「有没有新任务点」（见 [PageSweep]），不能用「这一页是不是空的」。
        val sweep = PageSweep()
        var last = 0
        for (num in 0 until PageSweep.DEFAULT_MAX_PAGES) {
            limiter.acquire()
            val r = c.get(cardsUrl(course, chapter, num))
            checkLogin(r.body)
            if (r.body.contains("章节未开放")) return emptyList()
            val parsed = StudyParser.parseWorkCards(r.body)
            if (parsed.ktoken.isNotBlank()) ktoken = parsed.ktoken
            if (parsed.cpi.isNotBlank()) cpi = parsed.cpi
            parsed.jobs.forEach { w ->
                out[w.jobId] = WorkJob(
                    courseId = course.courseId,
                    clazzId = course.clazzId,
                    cpi = cpi,
                    chapterId = chapter.id,
                    chapterTitle = chapter.title,
                    jobId = w.jobId,
                    workId = w.workId,
                    enc = w.enc,
                    ktoken = ktoken,
                    name = w.name,
                    courseTitle = course.title,
                    module = w.module,
                    worktype = w.worktype
                )
            }
            val newItems = out.size - last
            last = out.size
            if (!sweep.afterNew(newItems)) break
        }
        // 只有真的找到测验或撞到上限才写日志，否则 91 章会刷屏
        if (out.isNotEmpty() || sweep.hitLimit) {
            LogStore.add(
                "[刷课] 测验扫描 ${chapter.title}：翻了 ${sweep.pages} 页，找到 ${out.size} 份" +
                    (if (sweep.hitLimit) "（撞上限，可能还有）" else ""),
                out.isNotEmpty()
            )
        }
        return out.values.map { if (it.ktoken.isBlank()) it.copy(ktoken = ktoken) else it }
    }

    private fun cardsUrl(course: StudyCourse, chapter: StudyChapter, num: Int): String =
        "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards" +
            "?clazzid=${course.clazzId}&courseid=${course.courseId}" +
            "&knowledgeid=${chapter.id}&ut=s&cpi=${course.cpi.ifBlank { "0" }}" +
            "&v=2025-0424-1038-3&mooc2=1&num=$num"

    /**
     * 文档任务点：GET 一次 `/mooc-ans/job/document` 就算完成，不需要真读。
     * 参数已用真实接口验证：缺 jtoken 会被回 `illegal params`，
     * knowledgeid 用 otherInfo 里的 nodeId（拿不到才用章节目录 id）。
     */
    suspend fun completeDocument(course: StudyCourse, chapter: StudyChapter, job: DocumentJob): DocumentResult {
        limiter.acquire()
        val knowledgeId = job.knowledgeId.ifBlank { chapter.id }
        val url = "https://mooc1.chaoxing.com/mooc-ans/job/document" +
            "?jobid=${enc(job.jobId)}" +
            "&knowledgeid=${enc(knowledgeId)}" +
            "&courseid=${enc(course.courseId)}" +
            "&clazzid=${enc(course.clazzId)}" +
            "&jtoken=${enc(job.jtoken)}" +
            "&checkMicroTopic=true&microTopicId=&courseEngineInfo=false"
        val r = docClient.get(url, mapOf("Referer" to CHAPTER_REFERER))
        checkLogin(r.body)
        if (!r.ok) {
            LogStore.add("[刷课] 文档 ${job.name} 完成请求失败 HTTP ${r.code}", false)
            return DocumentResult.FAILED
        }
        val body = r.body
        return when {
            body.contains("考核点已经完成") -> DocumentResult.ALREADY
            Regex("\"status\"\\s*:\\s*true").containsMatchIn(body) -> DocumentResult.DONE
            body.trim() == "1" || body.trim() == "success" -> DocumentResult.DONE
            else -> {
                LogStore.add("[刷课] 文档 ${job.name} 返回：${body.take(120)}", false)
                DocumentResult.FAILED
            }
        }
    }

    /** 一个章节里的文档任务点（已完成的不会返回，省得白跑接口） */
    suspend fun getDocumentJobs(course: StudyCourse, chapter: StudyChapter): List<DocumentJob> {
        val out = LinkedHashMap<String, DocumentJob>()
        val sweep = PageSweep()
        var last = 0
        for (num in 0 until PageSweep.DEFAULT_MAX_PAGES) {
            limiter.acquire()
            val r = c.get(cardsUrl(course, chapter, num))
            checkLogin(r.body)
            if (r.body.contains("章节未开放")) return emptyList()
            val jobs = StudyParser.parseDocumentJobs(r.body)
            jobs.forEach { out[it.jobId] = it }
            val newItems = out.size - last
            last = out.size
            if (!sweep.afterNew(newItems)) break
        }
        return out.values.toList()
    }

    /** 检查一个章节里所有任务点（含视频、章节测验）的完成情况，按类型分开数 */
    suspend fun checkChapter(course: StudyCourse, chapter: StudyChapter): ChapterCheck {
        val map = LinkedHashMap<String, CardTask>()
        var notOpen = false
        val sweep = PageSweep()
        var last = 0
        // 不用 jobCount 截断：章节测验/文档可能和视频不在同一张卡片上。
        // 退出条件看「有没有新任务点」，见 [PageSweep]。
        for (num in 0 until PageSweep.DEFAULT_MAX_PAGES) {
            checkLimiter.acquire()
            val r = c.get(
                "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards" +
                    "?clazzid=${course.clazzId}&courseid=${course.courseId}" +
                    "&knowledgeid=${chapter.id}&ut=s&cpi=${course.cpi.ifBlank { "0" }}" +
                    "&v=2025-0424-1038-3&mooc2=1&num=$num"
            )
            checkLogin(r.body)
            if (r.body.contains("章节未开放")) {
                notOpen = true
                break
            }
            StudyParser.parseCardTasks(r.body).forEach { map[it.key] = it }
            val newItems = map.size - last
            last = map.size
            if (!sweep.afterNew(newItems)) break
        }
        if (notOpen) return ChapterCheck(0, 0, 0, 0, notOpen = true)
        if (map.isEmpty() && chapter.jobCount > 0) {
            // 页面没解析出任务但章节标了有任务点：按未完成处理，避免误标蓝色
            return ChapterCheck(1, 0, 1, 0)
        }
        return summarizeChapter(map.values)
    }

    suspend fun videoStatus(objectId: String): VideoStatus? {        limiter.acquire()
        val url = "https://mooc1.chaoxing.com/ananas/status/$objectId" +
            "?k=$fid&flag=normal"
        val r = c.get(url, mapOf("Referer" to VIDEO_REFERER))
        if (!r.ok) return null
        val j = runCatching { JSONObject(r.body) }.getOrNull() ?: return null
        if (j.optString("status") != "success") return null
        val dtoken = j.optString("dtoken")
        val duration = j.optInt("duration", 0).takeIf { it > 0 }
            ?: j.optString("duration").toIntOrNull() ?: 0
        if (dtoken.isBlank() || duration <= 0) return null
        return VideoStatus(dtoken, duration)
    }

    /**
     * 上报一次视频进度。
     * @param playingTime 当前播放到第几秒
     * @param duration 视频总秒数
     * @param isdrag 4 = 首次尝试“瞬间完成”，3 = 正常观看
     */
    suspend fun logVideo(
        course: StudyCourse,
        job: VideoJob,
        status: VideoStatus,
        playingTime: Int,
        duration: Int,
        isdrag: Int,
        dtype: String,
        rt: String
    ): VideoLogResult {
        videoLimiter.acquire()

        val encHex = buildVideoEnc(
            course.clazzId, uid, job.jobId, job.objectId, playingTime, duration
        )
        val params = linkedMapOf(
            "clazzId" to course.clazzId,
            "playingTime" to playingTime.toString(),
            "duration" to duration.toString(),
            "clipTime" to "0_$duration",
            "objectId" to job.objectId,
            "otherInfo" to job.otherInfo,
            "courseId" to course.courseId,
            "jobid" to job.jobId,
            "userid" to uid,
            "isdrag" to isdrag.toString(),
            "view" to "pc",
            "enc" to encHex,
            "dtype" to dtype,
            "_t" to System.currentTimeMillis().toString()
        )
        if (rt.isNotBlank()) params["rt"] = rt
        if (job.faceCaptureEnc.isNotBlank()) params["videoFaceCaptureEnc"] = job.faceCaptureEnc
        if (job.attDuration.isNotBlank()) params["attDuration"] = job.attDuration
        if (job.attDurationEnc.isNotBlank()) params["attDurationEnc"] = job.attDurationEnc

        val cpi = course.cpi.ifBlank { "0" }
        val url = "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/$cpi/${status.dtoken}?" +
            params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }

        val referer = if (dtype.equals("Audio", ignoreCase = true)) AUDIO_REFERER else VIDEO_REFERER
        val r = c.get(url, mapOf("Referer" to referer))
        val passed = if (r.ok) {
            runCatching { JSONObject(r.body).optBoolean("isPassed", false) }.getOrDefault(false)
        } else false
        return VideoLogResult(passed, r.code, r.body)
    }

    suspend fun relogin(): Boolean {
        if (session.checkSession()) return true
        return session.login().isEmpty()
    }

    private fun checkLogin(body: String) {
        if (body.contains("passport2.chaoxing.com/mlogin") ||
            body.contains("passport2.chaoxing.com/login") ||
            body.contains("请输入手机号")
        ) {
            throw SessionExpiredException()
        }
    }

    companion object {
        /** 一个章节最多翻多少张卡片（真机上见过十几张） */
        private const val MAX_CARD_PAGES = 60

        private const val CHAPTER_REFERER = "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards"

        private const val INTERACTION_REFERER =
            "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction" +
                "?moocDomain=https://mooc1-1.chaoxing.com/mooc-ans"
        private const val VIDEO_REFERER =
            "https://mooc1.chaoxing.com/ananas/modules/video/index.html?v=2025-0725-1842"
        private const val AUDIO_REFERER =
            "https://mooc1.chaoxing.com/ananas/modules/audio/index_new.html?v=2025-0725-1842"
    }
}

/**
 * 视频进度上报的 enc 签名，和参考协议保持完全一致：
 * md5("[clazzId][uid][jobid][objectId][playingTime*1000][d_yHJ!$pdA~5][duration*1000][0_duration]")
 */
internal fun buildVideoEnc(
    clazzId: String,
    uid: String,
    jobId: String,
    objectId: String,
    playingTime: Int,
    duration: Int
): String {
    val plain = "[$clazzId][$uid][$jobId][$objectId][${playingTime * 1000}]" +
        "[d_yHJ!${'$'}pdA~5][${duration * 1000}][0_$duration]"
    val digest = MessageDigest.getInstance("MD5")
        .digest(plain.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
