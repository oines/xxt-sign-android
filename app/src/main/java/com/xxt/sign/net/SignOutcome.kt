package com.xxt.sign.net

/**
 * 一次签到提交的结构化结果。
 *
 * 用密封类而不是字符串，是因为「服务端要求人机验证」必须被程序可靠识别出来——
 * 靠匹配字符串太脆弱，而这个分支要触发人工介入流程。
 */
sealed class SignOutcome {

    /** 成功（含"您已签到过了"） */
    data class Done(val msg: String) : SignOutcome()

    /**
     * 服务端要求人机验证。需要人工过一遍拿到 validate，再带着它重试。
     * [raw] 是服务端原始返回，便于诊断。
     */
    data class NeedCaptcha(val raw: String) : SignOutcome()

    /** 硬性阻塞，非人工/配置缺失不可解（人脸识别、缺坐标、缺图片、缺签到码等） */
    data class Blocked(val msg: String) : SignOutcome()

    /** 其它失败（已结束、网络、服务端报错） */
    data class Failed(val msg: String) : SignOutcome()

    val isDone: Boolean get() = this is Done
    val text: String
        get() = when (this) {
            is Done -> msg
            is NeedCaptcha -> "服务端要求人机验证"
            is Blocked -> msg
            is Failed -> msg
        }
}
