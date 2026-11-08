package com.xxt.sign.net

/**
 * `pptSign/stuSignajax` 的回包判定。
 *
 * 单独拆出来是因为这里每一个分支都对应一种真实发生过的结果，
 * 判错的表现是「明明签上了却显示失败」或「二维码过期了还在傻等」。
 *
 * 实测到的形态：
 *  - `success` / `success2`      成功（**两个都算成功**，旧代码把 success2 判成失败）
 *  - `validate`                  服务端要人机验证；通过后带 validate 重发
 *  - `签到失败，请重新扫描`      二维码过期
 *  - `不在可签到范围内`          位置判定不在范围
 *  - `errorLocation2_1234`       超范围，后面是米数
 *  - `您已签到过了`              重复签到，按成功算
 */
internal object SignResponse {

    fun classify(raw: String): SignOutcome {
        val body = raw.trim()
        if (body.isEmpty()) return SignOutcome.Failed("签到无响应（服务端返回空）")
        val lower = body.lowercase()

        if (body == "validate") return SignOutcome.NeedCaptcha(body)
        if (body == "success" || body == "success2") return SignOutcome.Done("签到成功")
        if (body.contains("您已签到过了") || body.contains("已签到过")) {
            return SignOutcome.Done("已经签过了")
        }
        if (body.contains("请重新扫描")) {
            return SignOutcome.Failed("二维码已过期，请重新扫码")
        }
        if (body.contains("不在可签到范围内")) return SignOutcome.Failed("不在可签到范围内")
        if (lower.startsWith("errorlocation2")) {
            // 米数在 errorLocation2 后面，不能直接扫全串的数字（2 本身也是数字）
            val meters = Regex("errorlocation2[_:\\-]?(\\d+)", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1)
            return SignOutcome.Failed(if (meters != null) "超出签到范围 $meters 米" else "超出签到范围")
        }
        if (body.length <= 32 && lower.contains("validate")) return SignOutcome.NeedCaptcha(body)
        if (isCaptcha(body)) return SignOutcome.NeedCaptcha(body)
        if (body.contains("已结束") || body.contains("已过期")) {
            return SignOutcome.Failed(body.take(80))
        }
        if (body.startsWith("success")) return SignOutcome.Done("签到成功")
        return SignOutcome.Failed(body.take(120))
    }

    /** 服务端要求过图形验证码时的各种返回值形态 */
    fun isCaptcha(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("captcha") || b.contains("validate") ||
            body.contains("验证码") || body.contains("滑块") || body.contains("人机")
    }
}
