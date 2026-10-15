package com.xxt.sign.core

import android.content.Context
import android.content.SharedPreferences

/** 全局可调参数：位置签到坐标、拍照签到图片、自动巡检开关 */
object Settings {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("xxt_prefs", Context.MODE_PRIVATE)
    }

    var latitude: String
        get() = sp.getString("lat", "") ?: ""
        set(v) = sp.edit().putString("lat", v).apply()

    var longitude: String
        get() = sp.getString("lon", "") ?: ""
        set(v) = sp.edit().putString("lon", v).apply()

    var address: String
        get() = sp.getString("address", "") ?: ""
        set(v) = sp.edit().putString("address", v).apply()

    var photoPath: String
        get() = sp.getString("photo", "") ?: ""
        set(v) = sp.edit().putString("photo", v).apply()

    var autoMonitor: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    /** 已手动指定的签到码（留空表示用服务端活动里的签到码） */
    var manualSignCode: String
        get() = sp.getString("signCode", "") ?: ""
        set(v) = sp.edit().putString("signCode", v).apply()

    /** 巡检间隔（秒） */
    var intervalSeconds: Int
        get() = sp.getInt("interval", 5).coerceIn(1, 600)
        set(v) = sp.edit().putInt("interval", v.coerceIn(1, 600)).apply()

    /** 定位来源：true = 只用 GPS（准，但室内可能拿不到）；false = 所有 provider 取最准的 */
    var gpsOnly: Boolean
        get() = sp.getBoolean("gpsOnly", true)
        set(v) = sp.edit().putBoolean("gpsOnly", v).apply()

    /**
     * 是否假定所有账号在相同的课堂里。
     * 开启后只需一个「探针账号」遍历课程：没活动就整批跳过，省掉 (N-1)×K 次请求。
     */
    var sameCourses: Boolean
        get() = sp.getBoolean("sameCourses", true)
        set(v) = sp.edit().putBoolean("sameCourses", v).apply()

    /**
     * 账号之间的随机延尺上限（秒）。
     * 多账号在同一秒内齐刷刷签到是典型机器人特征，最容易招来验证码。
     */
    var jitterSeconds: Int
        get() = sp.getInt("jitter", 6).coerceIn(0, 60)
        set(v) = sp.edit().putInt("jitter", v.coerceIn(0, 60)).apply()

    /**
     * 课堂白名单，元素形如 "courseId:classId"。
     * 为空 = 该账号全部课程。
     */
    var courseFilter: Set<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("courseFilter", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit()
            .putString("courseFilter", org.json.JSONArray(v.toList()).toString())
            .apply()

    /** 白名单的展示名，key 同上，仅用于 UI */
    var courseNames: Map<String, String>
        get() = runCatching {
            val o = org.json.JSONObject(sp.getString("courseNames", "{}"))
            o.keys().asSequence().associateWith { o.optString(it) }
        }.getOrDefault(emptyMap())
        set(v) = sp.edit().putString("courseNames", org.json.JSONObject(v as Map<*, *>).toString()).apply()
}
