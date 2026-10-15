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
     * 服务端要求人机验证时，**直接转人工**（不再做自动解）。
     * 人工界面用的是 [com.xxt.sign.ui.CaptchaActivity]，与短信登录同一套，已验证可用。
     *
     * @param askHuman 弹人机验证，返回 validate；返回 null 表示放弃
     */
    suspend fun signWithCaptcha(
        acc: Account,
        api: CxApi,
        act: SignEngine.ActiveActivity,
        opt: SignOptions,
        onProgress: (String) -> Unit = {},
        askHuman: suspend (CaptchaPrompt) -> String? = { null }
    ): SignEngine.Outcome {
        var r = SignEngine.signOneActivity(acc, api, act, opt)
        if (r.status != SignEngine.SignStatus.NEED_CAPTCHA) return r

        onProgress("${acc.display} 碰到人机验证，请手动过一下")
        val validate = askHuman(
            CaptchaPrompt(acc.id, acc.display, act.title, act.aid, "需要人工验证")
        )
        if (validate.isNullOrBlank()) {
            return SignEngine.Outcome(
                acc.id, acc.display, "${act.title} ｜ 人机验证未完成，本次跳过", false,
                SignEngine.SignStatus.FAILED
            )
        }
        onProgress("${acc.display} 验证通过，继续签到…")
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
