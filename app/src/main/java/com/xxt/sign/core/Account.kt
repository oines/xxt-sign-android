package com.xxt.sign.core

import org.json.JSONObject
import java.util.UUID

/**
 * 一个学习通账号。password 明文保存在 App 私有目录（SharedPreferences），
 * 会话（cookies）单独保存，登录一次后可长期复用，不必反复输入密码。
 */
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val phone: String,
    val password: String = "",
    /** "pwd" 密码登录；"sms" 短信验证码登录（不保存密码，重登需手动） */
    var authType: String = AUTH_PWD,
    var name: String = "",
    var schoolId: String = "",
    var uid: String = "",
    var cookies: String = "",
    var lastLoginAt: Long = 0L,
    var lastError: String = ""
) {
    val display: String get() = name.ifBlank { phone }

    /** 是否曾成功建立过会话 */
    val hasSession: Boolean get() = uid.isNotBlank() && cookies.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("phone", phone)
        put("password", password)
        put("authType", authType)
        put("name", name)
        put("schoolId", schoolId)
        put("uid", uid)
        put("cookies", cookies)
        put("lastLoginAt", lastLoginAt)
        put("lastError", lastError)
    }

    companion object {
        fun from(o: JSONObject) = Account(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            phone = o.optString("phone"),            password = o.optString("password"),
            authType = o.optString("authType").ifBlank { AUTH_PWD },
            name = o.optString("name"),
            schoolId = o.optString("schoolId"),
            uid = o.optString("uid"),
            cookies = o.optString("cookies"),
            lastLoginAt = o.optLong("lastLoginAt"),
            lastError = o.optString("lastError")
        )

        const val AUTH_PWD = "pwd"
        const val AUTH_SMS = "sms"
        const val AUTH_WEB = "web"
    }
}
