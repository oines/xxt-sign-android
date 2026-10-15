package com.xxt.sign.sign

import com.xxt.sign.core.Account
import com.xxt.sign.core.LogStore
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.CxCaptcha
import com.xxt.sign.net.Http
import com.xxt.sign.net.SignOptions

/**
 * 单活动签到的共享流程：先直接签，被要求验证码时先自动解、失败再转人工。
 * [SignRunner] 和 [SignPlanner] 都用它，避免两处各写一遍。
 */
object SignFlow {

    data class CaptchaPrompt(
        val accountId: String,
        val accountName: String,
        val activityLabel: String,
        val aid: String,
        val autoFailedReason: String
    )

    /**
     * 签掉一个活动。
     *
     * @param askHuman 自动解失败时的人工兜底，返回 validate；返回 null 表示放弃
     * @param autoSolve 是否先尝试自动解（巡检无界面场景可关掉）
     */
    suspend fun signWithCaptcha(
        acc: Account,
        api: CxApi,
        act: SignEngine.ActiveActivity,
        opt: SignOptions,
        onProgress: (String) -> Unit = {},
        askHuman: suspend (CaptchaPrompt) -> String? = { null },
        autoSolve: Boolean = true
    ): SignEngine.Outcome {
        var r = SignEngine.signOneActivity(acc, api, act, opt)
        if (r.status != SignEngine.SignStatus.NEED_CAPTCHA) return r

        val referer = preSignReferer(act)
        var autoReason = "未尝试"

        if (autoSolve) {
            onProgress("${acc.display} 触发人机验证，正在自动解…")
            val solved = CxCaptcha.solve(
                client = Http.base,
                captchaId = CxCaptcha.CAPTCHA_ID_SIGN,
                referer = referer
            )
            if (solved.ok) {
                r = SignEngine.signOneActivity(acc, api, act, opt, solved.validate)
                if (r.status != SignEngine.SignStatus.NEED_CAPTCHA) {
                    return r.copy(detail = r.detail + "（验证码自动通过）")
                }
                autoReason = "自动通过后服务端仍要求验证"
            } else {
                autoReason = solved.error ?: "自动未通过"
            }
            LogStore.add("[验证码] 自动解结果：${if (solved.ok) "通过" else autoReason}", solved.ok)
        }

        onProgress("${acc.display} 等待人工过验证码（$autoReason）")
        val validate = askHuman(
            CaptchaPrompt(acc.id, acc.display, act.title, act.aid, autoReason)
        )
        if (validate.isNullOrBlank()) {
            return SignEngine.Outcome(
                acc.id, acc.display, "${act.title} ｜ 人机验证未完成，本次跳过", false,
                SignEngine.SignStatus.FAILED
            )
        }
        onProgress("${acc.display} 人工验证通过，继续签到…")
        r = SignEngine.signOneActivity(acc, api, act, opt, validate)
        return r.copy(detail = r.detail + "（人工通过验证码）")
    }

    fun preSignReferer(act: SignEngine.ActiveActivity): String {
        val courseId = act.course.courseId.ifBlank { act.detail.optString("courseId") }
        val classId = act.course.classId.ifBlank { act.detail.optString("clazzId") }
        return "https://mobilelearn.chaoxing.com/newsign/preSign" +
            "?courseId=$courseId&classId=$classId&activePrimaryId=${act.aid}" +
            "&general=1&sys=1&ls=1&appType=15"
    }
}
