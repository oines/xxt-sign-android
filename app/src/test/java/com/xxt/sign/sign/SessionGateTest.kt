package com.xxt.sign.sign

import com.xxt.sign.core.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话闸门：短信验证码账号不能因为「没密码」被判登录失败。
 *
 * 真机事故：[检测] <账号> 登录失败：该账号是短信验证码登录，请在账号页删除后
 * 重新用验证码添加 —— cookie 明明有效（视频一直回填 isPassed），整条巡检被拦死。
 */
class SessionGateTest {

    @Test
    fun smsAccountWithValidSessionUsesSession() {
        assertEquals(
            SessionGate.Action.USE_SESSION,
            SessionGate.decide(Account.AUTH_SMS, hasCookies = true, probeOk = true)
        )
    }

    @Test
    fun passwordAccountWithValidSessionUsesSession() {
        assertEquals(
            SessionGate.Action.USE_SESSION,
            SessionGate.decide(Account.AUTH_PWD, hasCookies = true, probeOk = true)
        )
    }

    /** 会话过期 + 短信账号：不碰密码，直接给出「用验证码重新登录」 */
    @Test
    fun expiredSmsSessionAsksForSmsRelogin() {
        val action = SessionGate.decide(Account.AUTH_SMS, hasCookies = true, probeOk = false)
        assertEquals(SessionGate.Action.GIVE_UP, action)
        val msg = SessionGate.reloginMessage(Account.AUTH_SMS)
        assertTrue(msg, msg.contains("验证码"))
        assertFalse("不该再让人删账号", msg.contains("删除"))
    }

    /** 会话过期 + 密码账号：还能自己重新登录 */
    @Test
    fun expiredPasswordSessionFallsBackToPasswordLogin() {
        assertEquals(
            SessionGate.Action.PASSWORD_LOGIN,
            SessionGate.decide(Account.AUTH_PWD, hasCookies = false, probeOk = false)
        )
    }

    /** 网页登录的账号没密码，只能请人重新走网页 */
    @Test
    fun expiredWebSessionAsksForWebRelogin() {
        assertEquals(
            SessionGate.Action.GIVE_UP,
            SessionGate.decide(Account.AUTH_WEB, hasCookies = true, probeOk = false)
        )
        assertTrue(SessionGate.reloginMessage(Account.AUTH_WEB).contains("网页"))
    }
}
