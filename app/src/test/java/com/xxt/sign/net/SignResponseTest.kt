package com.xxt.sign.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `stuSignajax` 回包判定的单测。
 * 这些字符串都是实测返回，判错的后果是「签上了却显示失败」或「过期了还在重试」。
 */
class SignResponseTest {

    @Test
    fun successIsDone() {
        assertTrue(SignResponse.classify("success") is SignOutcome.Done)
    }

    /** 旧实现把 success2 当成失败，这条就是防回归 */
    @Test
    fun success2IsAlsoDone() {
        assertTrue(SignResponse.classify("success2") is SignOutcome.Done)
        assertTrue(SignResponse.classify("  success2\n") is SignOutcome.Done)
    }

    @Test
    fun alreadySignedCountsAsDone() {
        assertTrue(SignResponse.classify("您已签到过了") is SignOutcome.Done)
    }

    @Test
    fun bareValidateMeansCaptcha() {
        val r = SignResponse.classify("validate")
        assertTrue(r is SignOutcome.NeedCaptcha)
        assertEquals("validate", (r as SignOutcome.NeedCaptcha).raw)
    }

    @Test
    fun expiredQrIsRecognized() {
        val r = SignResponse.classify("签到失败，请重新扫描")
        assertTrue(r is SignOutcome.Failed)
        assertTrue((r as SignOutcome.Failed).msg.contains("二维码"))
    }

    @Test
    fun outOfRangeIsRecognized() {
        assertTrue((SignResponse.classify("不在可签到范围内") as SignOutcome.Failed).msg.contains("不在可签到范围内"))
    }

    @Test
    fun errorLocationCarriesMeters() {
        val msg = (SignResponse.classify("errorLocation2_1234") as SignOutcome.Failed).msg
        assertTrue(msg, msg.contains("1234"))
    }

    @Test
    fun emptyBodyIsFailure() {
        assertTrue(SignResponse.classify("  ") is SignOutcome.Failed)
    }
}
