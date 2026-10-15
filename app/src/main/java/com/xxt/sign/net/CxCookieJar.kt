package com.xxt.sign.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内存 CookieJar，可序列化到字符串，便于每个账号独立持久化会话。
 * 学习通所有服务共用 *.chaoxing.com 的 Cookie（_uid / _d / vc3 / uf / fid ...）。
 */
class CxCookieJar : CookieJar {

    private val map = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) map[c.name] = c
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return map.values.filter { it.expiresAt > now && it.matches(url) }
    }

    @Synchronized
    fun value(name: String): String = map[name]?.value.orEmpty()

    /** 从 WebView 里收回来的 Cookie 写回内存（域统一按 .chaoxing.com 处理） */
    @Synchronized
    fun set(name: String, value: String) {
        if (name.isBlank() || value.isBlank()) return
        runCatching {
            map[name] = Cookie.Builder()
                .name(name)
                .value(value)
                .domain("chaoxing.com")
                .path("/")
                .build()
        }
    }

    @Synchronized
    fun all(): List<Cookie> = map.values.toList()

    @Synchronized
    fun dump(): String {
        val arr = JSONArray()
        for (c in map.values) {
            arr.put(JSONObject().apply {
                put("n", c.name)
                put("v", c.value)
                put("d", c.domain)
                put("p", c.path)
                put("e", c.expiresAt)
                put("s", c.secure)
                put("h", c.httpOnly)
                put("ho", c.hostOnly)
            })
        }
        return arr.toString()
    }

    @Synchronized
    fun restore(json: String) {
        map.clear()
        if (json.isBlank()) return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val domain = o.optString("d").ifBlank { "chaoxing.com" }
                val path = o.optString("p").ifBlank { "/" }
                val b = Cookie.Builder().name(o.getString("n")).value(o.optString("v")).path(path)
                if (o.optBoolean("ho")) b.hostOnlyDomain(domain) else b.domain(domain)
                b.expiresAt(o.optLong("e", Long.MAX_VALUE))
                if (o.optBoolean("s")) b.secure()
                if (o.optBoolean("h")) b.httpOnly()
                map[o.getString("n")] = b.build()
            }
        }
    }
}
