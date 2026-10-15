package com.xxt.sign.net

import com.xxt.sign.core.Account
import com.xxt.sign.core.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一个账号的登录态。负责：
 *  - 从已保存的 Cookie 恢复会话
 *  - 会话失效时重新登录（密码登录 / 短信验证码登录）
 *  - 把最新 Cookie 写回 Account
 */
class CxSession(val account: Account) {

    val jar = CxCookieJar().apply { restore(account.cookies) }
    private val client = Http.withJar(jar)

    var uid: String = account.uid
        private set
    var realName: String = account.name
        private set
    var schoolId: String = account.schoolId
        private set

    fun persist() {
        account.cookies = jar.dump()
        account.uid = uid
        account.name = realName
        account.schoolId = schoolId
    }

    fun hasCookies(): Boolean = account.cookies.isNotBlank()

    /** 会话是否仍然有效 */
    suspend fun checkSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val b = client.get(URL_ACCOUNT_MANAGE).body
            when {
                b.isBlank() -> false
                b.contains("messageName") -> true
                b.contains("请输入手机号") || b.contains("忘记密码") || b.contains("fanyalogin") -> false
                else -> jar.value("_uid").isNotBlank()
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ 密码登录

    /** 密码登录。返回空串表示成功，否则为错误信息。 */
    suspend fun login(): String = withContext(Dispatchers.IO) {
        if (account.password.isBlank()) {
            val m = "该账号是短信验证码登录，请在账号页删除后重新用验证码添加"
            account.lastError = m
            persist()
            return@withContext m
        }
        try {
            runCatching { client.get(URL_MLOGIN) }

            var err = tryApiLogin()
            if (err.isNotEmpty()) err = tryFanYaLogin(err)
            if (err.isNotEmpty()) {
                account.lastError = err
                persist()
                return@withContext err
            }
            finalizeLogin()
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            account.lastError = m
            persist()
            m
        }
    }

    private fun tryApiLogin(): String {
        val url = "https://passport2.chaoxing.com/api/login" +
            "?name=${enc(account.phone)}&pwd=${enc(account.password)}&schoolid=&verify="
        val r = client.get(url, mapOf("X-Requested-With" to "XMLHttpRequest"))
        val j = runCatching { JSONObject(r.body) }.getOrNull()
            ?: return "账号密码登录失败：返回异常（${r.code}）"
        if (j.optBoolean("result")) {
            uid = j.optString("uid").ifBlank { uid }
            realName = j.optString("realname").ifBlank { realName }
            schoolId = j.optString("schoolid").ifBlank { schoolId }
            return ""
        }
        return j.optString("errorMsg").ifBlank { "账号密码登录失败" }
    }

    /**
     * 官方网页版登录。t=true 时 uname 与 password 都要做 AES/CBC 加密，
     * refer 在页面里已编码过一次，再经表单编码一次。
     */
    private fun tryFanYaLogin(prevErr: String, validate: String = ""): String {
        val r = client.postForm(
            "https://passport2.chaoxing.com/fanyalogin",
            mapOf(
                "uname" to CxCrypto.aesEncryptBase64(account.phone),
                "password" to CxCrypto.aesEncryptBase64(account.password),
                "fid" to "-1",
                "t" to "true",
                "refer" to REFER_ENCODED,
                "forbidotherlogin" to "0",
                "validate" to validate,
                "doubleFactorLogin" to "0",
                "independentId" to "0"
            ),
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to URL_MLOGIN,
                "Origin" to "https://passport2.chaoxing.com"
            )
        )
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        if (j != null && j.optBoolean("status")) return ""
        val msg = j?.optString("msg").orEmpty().ifBlank { j?.optString("message").orEmpty() }
        return msg.ifBlank { prevErr }
    }

    // -------------------------------------------------------- 短信验证码登录

    /**
     * 发送短信验证码。返回空串表示已发出。
     * 先查当日上限，再查手机号是否存在，最后才真正发送（与官方页面顺序一致）。
     */
    suspend fun sendSmsCode(validate: String = ""): String = withContext(Dispatchers.IO) {
        try {
            val phone = account.phone
            // 必须先访问登录页拿到 Cookie，否则 phonecode 直接 403
            runCatching { client.get(URL_MLOGIN) }
            val hdrs = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to URL_MLOGIN,
                "Origin" to "https://passport2.chaoxing.com"
            )

            val limited = runCatching {
                client.postForm(
                    "https://passport2.chaoxing.com/num/booleanCode?key=${enc(phone)}&type=1",
                    emptyMap(),
                    hdrs
                ).body.trim()
            }.getOrDefault("")
            LogStore.add("[诊断] booleanCode -> '$limited'", true)
            if (limited.contains("alert")) return@withContext "发送失败：超出今日短信发送上限"

            // isPhoneExist 只是预检，且按默认学校查，绑在特定学校下的账号会被误判成“未注册”，
            // 所以只记日志不拦截，真正的判据是下面 phonecode 的返回。
            val exist = runCatching {
                client.postForm(
                    "https://passport2.chaoxing.com/isPhoneExist?phone=${enc(phone)}",
                    emptyMap(),
                    hdrs
                ).body.trim()
            }.getOrDefault("")
            LogStore.add("[诊断] isPhoneExist -> '${exist.take(80)}'", true)

            val url = "https://passport2.chaoxing.com/num/phonecode" +
                "?phone=${enc(phone)}&code=&type=1&needcode=true&countrycode=86" +
                "&validate=${enc(validate)}&fid=-1"
            val r = client.get(url, hdrs)
            val body = r.body
            LogStore.add("[诊断] phonecode(${r.code}) -> '${body.take(200)}'", true)

            val j = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext "发送失败：HTTP ${r.code}"
            if (j.optBoolean("result")) "" else {
                val msg = j.optString("msg").ifBlank { "验证码发送失败" }
                // 服务端要人机验证时文案里带“验证串/验证码”，把它原样透给 UI，由 UI 弹验证码
                if (msg.contains("验证")) "需要人机验证：$msg" else msg
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * 用短信验证码登录。
     * 注意：verCode 要做 AES 加密，再做一次 URL 编码（表单编码后是双重编码）。
     */
    suspend fun loginBySmsCode(code: String, validate: String = ""): String = withContext(Dispatchers.IO) {
        try {
            runCatching { client.get(URL_MLOGIN) }
            val verCode = enc(CxCrypto.aesEncryptBase64(code))
            val r = client.postForm(
                "https://passport2.chaoxing.com/fanyaloginbycode",
                mapOf(
                    "fid" to "-1",
                    "uname" to account.phone,
                    "verCode" to verCode,
                    "refer" to REFER_ENCODED,
                    "doubleFactorLogin" to "0",
                    "independentNameId" to ""
                ),
                mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to URL_MLOGIN,
                    "Origin" to "https://passport2.chaoxing.com"
                )
            )
            val j = runCatching { JSONObject(r.body) }.getOrNull()
                ?: return@withContext "登录失败：返回异常（${r.code}）"

            if (!j.optBoolean("status")) {
                val msg = j.optString("msg").orEmpty()
                val tip = when {
                    j.optInt("type", 0) == 1 -> "该手机号尚未注册学习通"
                    msg.isNotBlank() -> msg
                    else -> "验证码登录失败（验证码可能已过期）"
                }
                return@withContext tip
            }
            finalizeLogin()
        } catch (e: Exception) {
            val m = e.message ?: e.javaClass.simpleName
            account.lastError = m
            persist()
            m
        }
    }

    // -------------------------------------------------------------- 收尾

    /**
     * 人工在 WebView 里登录/签到完成后，用收回来的 Cookie 重建会话档。
     * 返回会话是否真的可用。
     */
    suspend fun adoptCookies(): Boolean = withContext(Dispatchers.IO) {
        uid = uid.ifBlank { jar.value("_uid") }
        schoolId = schoolId.ifBlank { jar.value("fid") }
        runCatching {
            val am = client.get(URL_ACCOUNT_MANAGE)
            val name = NAME_PATTERNS.firstNotNullOfOrNull { re ->
                re.find(am.body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
            if (name != null) realName = name
        }
        if (uid.isBlank()) uid = jar.value("_uid")
        if (schoolId.isBlank()) schoolId = jar.value("fid")
        val ok = checkSession()
        account.lastLoginAt = System.currentTimeMillis()
        account.lastError = if (ok) "" else "人工验证后会话仍不可用"
        persist()
        ok
    }

    /** 登录成功后把完整 Cookie 建立起来，并校验会话 */
    private fun finalizeLogin(): String {
        runCatching { client.get(URL_SELF_HOME) }
        runCatching {
            val am = client.get(URL_ACCOUNT_MANAGE)
            val name = NAME_PATTERNS.firstNotNullOfOrNull { re ->
                re.find(am.body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }
            if (name != null) realName = name
        }
        if (uid.isBlank()) uid = jar.value("_uid")
        if (schoolId.isBlank()) schoolId = jar.value("fid")

        if (!runBlockingCheck()) {
            val m = "会话建立失败：可能需要先通过图形验证码（滑块/点选）"
            account.lastError = m
            persist()
            return m
        }
        account.lastLoginAt = System.currentTimeMillis()
        account.lastError = ""
        persist()
        return ""
    }

    private fun runBlockingCheck(): Boolean = kotlinx.coroutines.runBlocking { checkSession() }

    companion object {
        private const val URL_MLOGIN =
            "https://passport2.chaoxing.com/mlogin?fid=&newversion=true&refer=http%3A%2F%2Fi.chaoxing.com"
        private const val URL_SELF_HOME = "https://i.chaoxing.com/base"
        private const val URL_ACCOUNT_MANAGE = "https://passport2.chaoxing.com/mooc/accountManage"
        private const val REFER_ENCODED = "http%3A%2F%2Fi.chaoxing.com"

        private val NAME_PATTERNS = listOf(
            Regex("messageName[\"'\\s]*[:=][\"'\\s]*([^\"'<]+)"),
            Regex("id=\"messageName\"[^>]*>([^<]+)<")
        )

        /** 官方滑块/点选验证码的 captchaId（登录场景） */
        const val CAPTCHA_ID = "GcXX5vewqE7DezKGlyvleKCnkTglvGpL"
        const val CAPTCHA_SDK = "https://captcha.chaoxing.com/load.min.js"
    }
}
