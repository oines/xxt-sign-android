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
    /** 模仿学习通 Android 客户端 UA，部分接口（taskactivelist）会校验 */
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 " +
            "(schild:1) (device:Pixel 5) Language/zh_CN " +
            "com.ssreader.ChaoXingStudy/ChaoXingStudy_3_6_2_android_phone_20230101"

    val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun withJar(jar: CookieJar): OkHttpClient = base.newBuilder().cookieJar(jar).build()
}

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

fun dec(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

data class Resp(val code: Int, val body: String, val headers: Headers, val rawBytes: ByteArray = ByteArray(0)) {
    val ok: Boolean get() = code in 200..399

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

fun OkHttpClient.exec(req: Request): Resp = newCall(req).execute().use { r ->
    val bytes = r.body?.bytes() ?: ByteArray(0)
    Resp(r.code, String(bytes, Charsets.UTF_8), r.headers, bytes)
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
