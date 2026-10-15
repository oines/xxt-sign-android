package com.xxt.sign.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * 一个学习通账号。password 明文保存在 App 私有目录（SharedPreferences），
 * 会话（cookies）单独保存，登录一次后可长期复用，不必反复输入密码。
 */
data class Account(
    val id: String = UUID.randomUUID().toString(),
    val phone: String,
    val password: String = "",

    /** "pwd" 密码登录；"sms" 短信验证码登录；"web" 网页登录（不存密码） */
    var authType: String = AUTH_PWD,

    /**
     * 设备标识，对应学习通 UA 里的 `(schild:xxx)`。
     *
     * 每个账号固定一个：UA 里唯一能表明设备身份的就是这个字段，
     * 所有账号共用同一个值，等于直接告诉服务端「这些账号来自同一台设备」。
     */
    var schild: String = "",

    /** UA 里声称的设备型号，同样每个账号固定一个 */
    var deviceModel: String = "",

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
        put("schild", schild)
        put("deviceModel", deviceModel)
        put("name", name)
        put("schoolId", schoolId)
        put("uid", uid)
        put("cookies", cookies)
        put("lastLoginAt", lastLoginAt)
        put("lastError", lastError)
    }

    companion object {
        const val AUTH_PWD = "pwd"
        const val AUTH_SMS = "sms"
        const val AUTH_WEB = "web"

        private val MODELS = listOf(
            "Pixel 5", "Pixel 6", "Pixel 7", "MI 11", "MI 12", "M2102J2SC",
            "V2118A", "PGT-AN00", "PHK110", "Redmi K40", "OnePlus 9", "SM-G9910"
        )

        fun newSchild(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun newDeviceModel(): String = MODELS.random()

        fun from(o: JSONObject) = Account(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            phone = o.optString("phone"),
            password = o.optString("password"),
            authType = o.optString("authType").ifBlank { AUTH_PWD },
            // 老版本存下来的账号没有这两个字段，读出来时补一个（下次 upsert 会回写）
            schild = o.optString("schild").ifBlank { newSchild() },
            deviceModel = o.optString("deviceModel").ifBlank { newDeviceModel() },
            name = o.optString("name"),
            schoolId = o.optString("schoolId"),
            uid = o.optString("uid"),
            cookies = o.optString("cookies"),
            lastLoginAt = o.optLong("lastLoginAt"),
            lastError = o.optString("lastError")
        )
    }
}
