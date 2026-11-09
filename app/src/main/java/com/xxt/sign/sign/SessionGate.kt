package com.xxt.sign.sign

import com.xxt.sign.core.Account

/**
 * 会话闸门：判断「这个账号要不要重新登录」。
 *
 * 血泪教训（真机）：短信验证码登录（authType=sms）的账号 cookie 明明有效
 * （视频一直回填 isPassed），但检测那条路径只看「有没有密码」，于是报
 * 「该账号是短信验证码登录，请在账号页删除后重新用验证码添加」，整条巡检被拦死。
 *
 * 正确口径：**看真实请求能不能拿到业务页面**（[com.xxt.sign.net.CxSession.probeSession]），
 * 有密码不代表会话有效，没密码更不代表会话无效。
 */
object SessionGate {

    const val SMS_RELOGIN = "登录已失效，请在账号页用短信验证码重新登录"
    const val WEB_RELOGIN = "登录已失效，请在账号页打开网页重新登录"

    enum class Action {
        /** cookie 有效：直接用，别碰密码 */
        USE_SESSION,

        /** cookie 失效 + 有密码：走密码登录 */
        PASSWORD_LOGIN,

        /** cookie 失效 + 没密码（sms/web）：只能请人去重新登录 */
        GIVE_UP
    }

    fun decide(authType: String, hasCookies: Boolean, probeOk: Boolean): Action = when {
        hasCookies && probeOk -> Action.USE_SESSION
        authType == Account.AUTH_PWD -> Action.PASSWORD_LOGIN
        else -> Action.GIVE_UP
    }

    /** 给人看的重新登录提示：sms 说验证码，web 说网页 */
    fun reloginMessage(authType: String): String =
        if (authType == Account.AUTH_SMS) SMS_RELOGIN else WEB_RELOGIN
}
