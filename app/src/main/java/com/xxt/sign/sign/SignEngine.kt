package com.xxt.sign.sign

import com.xxt.sign.core.Account
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.net.Course
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.CxSession
import com.xxt.sign.net.SignOptions
import com.xxt.sign.net.SignOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.Collections

/**
 * 签到相关的底层能力：会话、活动发现、单活动提交。
 * 多账号编排在 [SignRunner] 里，那套是按账号逐个处理并支持人工验证码介入的。
 */
object SignEngine {

    enum class SignStatus { DONE, NEED_CAPTCHA, BLOCKED, FAILED, NO_ACTIVITY }

    data class Outcome(
        val accountId: String,
        val title: String,
        val detail: String,
        val ok: Boolean,
        val status: SignStatus = if (ok) SignStatus.DONE else SignStatus.FAILED
    )

    data class ActivityRef(val aid: String, val courseId: String, val classId: String)

    /** 一个进行中的签到/签退活动 */
    data class ActiveActivity(
        val detail: JSONObject,
        val course: Course,
        val listItem: JSONObject
    ) {
        val aid: String get() = detail.optString("id").ifBlank { listItem.optString("id") }
        val otherId: Int get() = detail.optInt("otherId", listItem.optInt("otherId", -1))
        val isSignOut: Boolean get() = detail.optInt("activeType", 2) == CxApi.TYPE_ACTIVITY_SIGN_OUT
        val label: String get() = CxApi.label(detail)
        val title: String get() = "${course.name.ifBlank { "课程" }} · $label"
    }

    // ------------------------------------------------------------ 会话

    /** 保证拿到可用会话；返回 (session, 错误信息) */
    suspend fun sessionFor(acc: Account): Pair<CxSession?, String> {
        // 在副本上干活：Store 里的实例保持旧值，回写时 StateFlow 才会因为内容变化而发射
        val work = acc.copy()
        val s = CxSession(work)
        if (s.hasCookies() && s.checkSession()) return s to ""
        val err = s.login()
        AccountStore.upsert(work)
        return if (err.isEmpty()) s to "" else null to err
    }

    // -------------------------------------------------------- 活动发现

    private suspend fun resolveCourses(api: CxApi, filter: Set<String>): List<Course> =
        if (filter.isEmpty()) {
            api.getCourses()
        } else {
            filter.mapNotNull {
                val p = it.split(':')
                if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) Course(p[0], p[1]) else null
            }
        }

    /**
     * 找出某个账号所有进行中的签到 / 签退活动。
     * 关键：`activeType` 既可能是 2（签到）也可能是 74（签退），两者都要收。
     */
    suspend fun findAllActive(api: CxApi, filter: Set<String> = emptySet()): List<ActiveActivity> {
        val courses = resolveCourses(api, filter)
        if (courses.isEmpty()) return emptyList()

        val out = Collections.synchronizedList(mutableListOf<ActiveActivity>())
        val sem = Semaphore(4)
        coroutineScope {
            courses.map { course ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        runCatching {
                            api.activeList(course.courseId, course.classId).forEach { act ->
                                val aid = act.optString("id")
                                if (aid.isBlank()) return@forEach
                                if (out.any { it.aid == aid }) return@forEach
                                val detail = api.signDetail(aid)
                                if (detail.optInt("status", 1) == 2) return@forEach // 已结束
                                out.add(ActiveActivity(detail, course, act))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        return out.toList()
    }

    /** 按指定 aid 直接构造一个活动（扫码场景） */
    suspend fun activityByAid(api: CxApi, aid: String): ActiveActivity {
        val detail = api.signDetail(aid)
        return ActiveActivity(
            detail = detail,
            course = Course(detail.optString("courseId"), detail.optString("clazzId")),
            listItem = JSONObject()
        )
    }

    // ------------------------------------------------------ 单活动提交

    /**
     * 签掉一个具体活动。
     * [validate] 非空时用于人工过完验证码后的重试。
     */
    suspend fun signOneActivity(
        acc: Account,
        api: CxApi,
        act: ActiveActivity,
        opt: SignOptions,
        validate: String? = null
    ): Outcome {
        val detail = act.detail
        val prefix = act.title
        val aid = act.aid

        val info = runCatching { api.pptActiveInfo(aid) }.getOrNull()
        val needV = info?.optInt("ifNeedVCode", 0) ?: 0
        val needFace = info?.optInt("openCheckFaceFlag", 0) ?: 0
        val needAddr = info?.optInt("ifopenAddress", 0) ?: 0
        val prevent = info?.optInt("openPreventCheatFlag", 0) ?: 0
        val effectiveValidate = validate ?: opt.validate

        LogStore.add(
            "[诊断] $prefix aid=$aid ${if (act.isSignOut) "签退" else "签到"} " +
                "needVCode=$needV 防作弊=$prevent openFace=$needFace openAddress=$needAddr " +
                "signCode='${detail.optString("signCode")}' " +
                "encLen=${detail.optString("enc").length} refreshEwm=${detail.optInt("ifRefreshEwm", 0)} " +
                "validate=${if (effectiveValidate.isNullOrBlank()) "无" else "有"}",
            true
        )

        if (needFace == 1) {
            return Outcome(acc.id, acc.display, "$prefix ｜ 要求人脸识别，无法自动签", false, SignStatus.BLOCKED)
        }
        // 服务端明说要验证码，而人工还没过 —— 直接交给上层去弹窗
        if (needV == 1 && effectiveValidate.isNullOrBlank()) {
            return Outcome(acc.id, acc.display, "$prefix ｜ 需要人机验证", false, SignStatus.NEED_CAPTCHA)
        }

        val courseId = act.course.courseId.ifBlank { detail.optString("courseId") }
        val classId = act.course.classId.ifBlank { detail.optString("clazzId") }
        val opt2 = opt.withValidate(effectiveValidate)

        return when (val r = api.signActivity(detail, courseId, classId, opt2)) {
            is SignOutcome.Done ->
                Outcome(acc.id, acc.display, "$prefix ｜ ${r.msg}", true, SignStatus.DONE)

            is SignOutcome.NeedCaptcha ->
                Outcome(acc.id, acc.display, "$prefix ｜ 服务端要求人机验证", false, SignStatus.NEED_CAPTCHA)

            is SignOutcome.Blocked ->
                Outcome(acc.id, acc.display, "$prefix ｜ ${r.msg}", false, SignStatus.BLOCKED)

            is SignOutcome.Failed ->
                Outcome(acc.id, acc.display, "$prefix ｜ ${r.msg}", false, SignStatus.FAILED)
        }
    }

    // -------------------------------------------------- 兼容旧的批量入口

    /** 旧接口：一次性处理（无人工介入，验证码靠自动解）。巡检服务用。 */
    suspend fun signAll(accounts: List<Account>, opt: SignOptions, quiet: Boolean = false): List<Outcome> =
        SignRunner(
            accounts = accounts,
            opt = opt,
            askHuman = { null },
            quiet = quiet
        ).run()

    suspend fun loginAll(accounts: List<Account>, onEach: (Outcome) -> Unit): List<Outcome> {
        val rs = accounts.map { acc ->
            val (s, err) = sessionFor(acc)
            if (s == null) Outcome(acc.id, acc.display, "登录失败：$err", false)
            else Outcome(acc.id, acc.display, "会话正常（${acc.name}）", true)
        }
        rs.forEach(onEach)
        return rs
    }

    suspend fun checkAll(accounts: List<Account>): List<Outcome> = accounts.map { acc ->
        val (s, err) = sessionFor(acc)
        if (s == null) Outcome(acc.id, acc.display, "登录失败：$err", false)
        else Outcome(acc.id, acc.display, "会话有效", true)
    }

    suspend fun locateActivity(acc: Account): ActivityRef? {
        val (s, _) = sessionFor(acc)
        if (s == null) return null
        val found = runCatching { findAllActive(CxApi(s), Settings.courseFilter) }
            .getOrNull()?.firstOrNull() ?: return null
        return ActivityRef(found.aid, found.course.courseId, found.course.classId)
    }
}
