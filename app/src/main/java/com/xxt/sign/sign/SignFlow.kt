package com.xxt.sign.sign

import com.xxt.sign.core.Account
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.SignOptions

/**
 * 单活动签到的共享流程：先直接签，被要求验证码时转人工（人工滑完立刻带 validate 重发）。
 *
 * [SignRunner] 和 [SignPlanner] 都用它，避免两处各写一遍。
 * 人工弹窗一律走 [HumanCaptcha]，保证多个账号不会互相覆盖。
 */
object SignFlow {

    data class CaptchaPrompt(
        val accountId: String,
        val accountName: String,
        val activityLabel: String,
        val aid: String,
        /** 为什么弹窗，给用户看的文案 */
        val reason: String
    ) {
        /** 标题里要写清是哪个账号、哪个活动 */
        val title: String get() = "$accountName · $activityLabel"
    }

    /**
     * 签掉一个活动（单账号，含一次人工验证码机会）。
     *
     * 服务端返回裸 `validate` 时**直接转人工**，不再做自动解；
     * 人工界面用的是 [com.xxt.sign.ui.CaptchaActivity]。
     *
     * @param askHuman 弹人机验证，返回 validate；返回 null 表示放弃/超时
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

        onProgress("${acc.display} 需要人机验证，请手动滑一下（${act.title}）")
        val prompt = CaptchaPrompt(acc.id, acc.display, act.title, act.aid, "服务端要求人机验证")
        // 串行：同一时刻只会有一个人在滑，validate 只会回到发起它的那个账号
        val validate = HumanCaptcha.ask(prompt, askHuman)
        if (validate.isNullOrBlank()) {
            return SignEngine.Outcome(
                acc.id, acc.display,
                "${act.title} ｜ 人机验证未完成，未签上（需人工）", false,
                SignEngine.SignStatus.NEED_HUMAN
            )
        }
        onProgress("${acc.display} 验证通过，正在重发签到…")
        r = SignEngine.signOneActivity(acc, api, act, opt, validate)
        return when {
            r.ok -> r.copy(detail = r.detail + "（人工过验证码）")
            r.status == SignEngine.SignStatus.NEED_CAPTCHA -> r.copy(
                detail = r.detail + "（滑块后仍要求验证）",
                status = SignEngine.SignStatus.NEED_HUMAN
            )
            else -> r
        }
    }

    fun preSignReferer(act: SignEngine.ActiveActivity): String {
        val courseId = act.course.courseId.ifBlank { act.detail.optString("courseId") }
        val classId = act.course.classId.ifBlank { act.detail.optString("clazzId") }
        return "https://mobilelearn.chaoxing.com/newsign/preSign" +
            "?courseId=$courseId&classId=$classId&activePrimaryId=${act.aid}" +
            "&general=1&sys=1&ls=1&appType=15"
    }
}
