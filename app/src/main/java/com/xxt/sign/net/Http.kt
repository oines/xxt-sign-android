package com.xxt.sign.net

import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object Http {

    /**
     * 模仿学习通 Android 客户端的 UA。
     *
     * 其中 `(schild:xxx)` 是 **UA 里唯一表明设备身份的东西**，必须每个账号不同——
     * 所有账号共用同一个 schild，等于直接告诉服务端这些账号来自同一台设备。
     *
     * `(device:xxx)` 是机型，同样按账号区分。
     * 其余部分（系统版本、Chrome 版本、App 版本）保持一致即可。
     */
    private const val UA_TEMPLATE =
        "Mozilla/5.0 (Linux; Android 13; %MODEL% Build/TQ3A.230901.001) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 " +
            "(schild:%SCHILD%) (device:%MODEL%) Language/zh_CN " +
            "com.ssreader.ChaoXingStudy/ChaoXingStudy_3_6_2_android_phone_20230101"

    /** 默认 UA（无账号上下文时用，例如公开的验证码接口） */
    val UA: String get() = buildUa("Pixel 5", "1")

    /**
     * 刷课（mooc1 / mooc2-ans 网页接口）使用的桌面浏览器 UA。
     * 这些接口返回的是 PC 网页，Android UA 容易拿到不一样的页面结构。
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

    /** 与桌面 UA 配套的 client hints，尽量贴近官方网页请求 */
    val PC_HEADERS: Map<String, String> = mapOf(
        "sec-ch-ua" to "\"Chromium\";v=\"129\", \"Google Chrome\";v=\"129\", \"Not=A?Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    fun buildUa(model: String, schild: String): String =
        UA_TEMPLATE
            .replace("%MODEL%", model.ifBlank { "Pixel 5" })
            .replace("%SCHILD%", schild.ifBlank { "1" })

    val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 带 Cookie 与**该账号专属 UA** 的客户端。
     * 用拦截器统一注入 UA，这样包括图片下载、multipart 上传在内的所有请求都带上。
     */
    fun withJar(
        jar: CookieJar,
        ua: String = UA,
        extraHeaders: Map<String, String> = emptyMap()
    ): OkHttpClient = base.newBuilder()
        .cookieJar(jar)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder().header("User-Agent", ua)
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            chain.proceed(builder.build())
        }
        .build()
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

data class Resp(
    val code: Int,
    val body: String,
    val headers: Headers,
    val rawBytes: ByteArray = ByteArray(0),
    /** 跟随重定向后的最终 URL（排错用） */
    val finalUrl: String = ""
) {
    val ok: Boolean get() = code in 200..399

    // ByteArray 参与 equals 会让 data class 语义变味，这里按引用比较
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

fun OkHttpClient.exec(req: Request): Resp = newCall(req).execute().use { r ->
    val bytes = r.body?.bytes() ?: ByteArray(0)
    Resp(r.code, String(bytes, Charsets.UTF_8), r.headers, bytes, r.request.url.toString())
}

fun OkHttpClient.get(url: String, headers: Map<String, String> = emptyMap()): Resp {
    val b = Request.Builder().url(url).header("User-Agent", Http.UA)
    headers.forEach { (k, v) -> b.header(k, v) }
    return exec(b.build())
}

fun OkHttpClient.postForm(
    url: String,
    form: Map<String, String>,
    headers: Map<String, String> = emptyMap()
): Resp {
    val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toRequestBody(Http.FORM)
    val b = Request.Builder().url(url).post(body).header("User-Agent", Http.UA)
    headers.forEach { (k, v) -> b.header(k, v) }
    return exec(b.build())
}
