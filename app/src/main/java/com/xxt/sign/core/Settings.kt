package com.xxt.sign.core

import android.content.Context
import android.content.SharedPreferences

/** 全局可调参数：位置签到坐标、拍照签到图片、自动巡检开关 */
object Settings {
    const val QUIZ_NONE = "off"
    const val QUIZ_AUTO = "auto"
    const val QUIZ_TIKU_ADAPTER = "tikuadapter"
    const val QUIZ_YANXI = "yanxi"
    const val QUIZ_LEMON = "lemon"

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

    // ---------------------------------------------------------------- 刷课

    /** 用哪个账号刷课（Account.id），空 = 未选择 */
    var studyAccountId: String
        get() = sp.getString("studyAccount", "") ?: ""
        set(v) = sp.edit().putString("studyAccount", v).apply()

    /** 并发刷课的章节数 */
    var studyConcurrency: Int
        get() = sp.getInt("studyConcurrency", 4).coerceIn(1, 8)
        set(v) = sp.edit().putInt("studyConcurrency", v.coerceIn(1, 8)).apply()

    /** 视频倍速，1.0–2.0 */
    var studySpeed: Float
        get() = sp.getFloat("studySpeed", 1.0f).coerceIn(1.0f, 2.0f)
        set(v) = sp.edit().putFloat("studySpeed", v.coerceIn(1.0f, 2.0f)).apply()

    // -------------------------------------------------------- 章节测验

    /**
     * 挂机模式总开关：默认开启。
     * 打开后前台服务会自动巡检课程、自动答题、自动交卷；关掉就只保留手动入口。
     */
    var quizAutoMode: Boolean
        get() = sp.getBoolean("quizAutoMode", true)
        set(v) = sp.edit().putBoolean("quizAutoMode", v).apply()

    /** 通用整型存取（巡检游标之类的小状态） */
    fun rawInt(key: String, def: Int): Int = sp.getInt(key, def)
    fun putRawInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    /** 是否已经向用户解释过"挂机会替你交卷" */
    var quizAfkWarned: Boolean
        get() = sp.getBoolean("quizAfkWarned", false)
        set(v) = sp.edit().putBoolean("quizAfkWarned", v).apply()

    /** 最近一次成功的定位，位置签到用它兜底 */
    var lastGoodLat: String
        get() = sp.getString("lastGoodLat", "") ?: ""
        set(v) = sp.edit().putString("lastGoodLat", v).apply()

    var lastGoodLon: String
        get() = sp.getString("lastGoodLon", "") ?: ""
        set(v) = sp.edit().putString("lastGoodLon", v).apply()

    /** 题库来源：auto（内置免费源）/ tikuadapter / yanxi / lemon / off */
    var quizProvider: String
        get() = sp.getString("quizProvider", QUIZ_AUTO) ?: QUIZ_AUTO
        set(v) = sp.edit().putString("quizProvider", v).apply()

    /** 题库端点。tikuAdapter 填根地址，言溪/柠檬填域名 */
    var quizEndpoint: String
        get() = sp.getString("quizEndpoint", "") ?: ""
        set(v) = sp.edit().putString("quizEndpoint", v).apply()

    /** 题库 token（言溪/柠檬需要；tikuAdapter 一般不用） */
    var quizToken: String
        get() = sp.getString("quizToken", "") ?: ""
        set(v) = sp.edit().putString("quizToken", v).apply()

    /**
     * 只暂存（pyFlag=1）。**默认关闭**：挂机要的是「自己交完」，所以默认正式提交。
     * 想先看效果的人可以手动打开。
     */
    var quizStoreOnly: Boolean
        get() = sp.getBoolean("quizStoreOnly", false)
        set(v) = sp.edit().putBoolean("quizStoreOnly", v).apply()

    /** 命中率阈值：低于它仍然交，但日志/通知会标「低置信，建议回看」 */
    var quizHitThreshold: Float
        get() = sp.getFloat("quizHitRate", 0.8f).coerceIn(0f, 1f)
        set(v) = sp.edit().putFloat("quizHitRate", v.coerceIn(0f, 1f)).apply()

    /** 自动巡检测验的间隔（分钟） */
    var quizPatrolMinutes: Int
        get() = sp.getInt("quizPatrolMin", 10).coerceIn(1, 240)
        set(v) = sp.edit().putInt("quizPatrolMin", v.coerceIn(1, 240)).apply()

    /** 上次自动巡检的时间戳，用来限速 */
    var quizLastPatrolAt: Long
        get() = sp.getLong("quizLastPatrol", 0L)
        set(v) = sp.edit().putLong("quizLastPatrol", v).apply()

    /** 单题停留时间下限（秒），拟人节奏 */
    var quizPerQuestionMin: Int
        get() = sp.getInt("quizPerQMin", 8).coerceIn(1, 120)
        set(v) = sp.edit().putInt("quizPerQMin", v.coerceIn(1, 120)).apply()

    /** 单题停留时间上限（秒） */
    var quizPerQuestionMax: Int
        get() = sp.getInt("quizPerQMax", 20).coerceIn(1, 300)
        set(v) = sp.edit().putInt("quizPerQMax", v.coerceIn(1, 300)).apply()

    /** 答完到提交的等待下限（秒） */
    var quizSubmitWaitMin: Int
        get() = sp.getInt("quizSubmitMin", 30).coerceIn(0, 600)
        set(v) = sp.edit().putInt("quizSubmitMin", v.coerceIn(0, 600)).apply()

    /** 答完到提交的等待上限（秒） */
    var quizSubmitWaitMax: Int
        get() = sp.getInt("quizSubmitMax", 60).coerceIn(0, 900)
        set(v) = sp.edit().putInt("quizSubmitMax", v.coerceIn(0, 900)).apply()

    /** 两份卷子之间的最小间隔（秒），默认 90 */
    var quizGapSeconds: Int
        get() = sp.getInt("quizGap", 90).coerceIn(30, 1800)
        set(v) = sp.edit().putInt("quizGap", v.coerceIn(30, 1800)).apply()

    // ------------------------------------------------------------ LLM 兜底

    /** LLM 开关。默认关；填了 key 并且打开才会用 */
    var quizLlmEnabled: Boolean
        get() = sp.getBoolean("quizLlmEnabled", false)
        set(v) = sp.edit().putBoolean("quizLlmEnabled", v).apply()

    /** OpenAI 兼容接口的 key（DeepSeek 默认） */
    var quizLlmKey: String
        get() = sp.getString("quizLlmKey", "") ?: ""
        set(v) = sp.edit().putString("quizLlmKey", v).apply()

    /** baseUrl，DeepSeek 是 https://api.deepseek.com */
    var quizLlmBaseUrl: String
        get() = sp.getString("quizLlmBaseUrl", "https://api.deepseek.com") ?: "https://api.deepseek.com"
        set(v) = sp.edit().putString("quizLlmBaseUrl", v).apply()

    /** 模型名，DeepSeek 是 deepseek-chat */
    var quizLlmModel: String
        get() = sp.getString("quizLlmModel", "deepseek-chat") ?: "deepseek-chat"
        set(v) = sp.edit().putString("quizLlmModel", v).apply()

    /** 字体反解的最低相似度，低于它算「待复核」，不采信 */
    var quizFontThreshold: Float
        get() = sp.getFloat("quizFontThreshold", 0.86f).coerceIn(0.5f, 0.99f)
        set(v) = sp.edit().putFloat("quizFontThreshold", v.coerceIn(0.5f, 0.99f)).apply()

    /** 已勾选要刷的章节，元素形如 "courseId:clazzId#chapterId" */
    var studySelectedChapters: Set<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("studyChapters", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit()
            .putString("studyChapters", org.json.JSONArray(v.toList()).toString())
            .apply()
}
