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

    /** 坐标原样提交，不做任何换算（默认） */
    const val COORD_NONE = "none"

    /** 输入坐标是 WGS-84（GPS 原始）→ 提交换算成 GCJ-02 */
    const val COORD_WGS84 = "wgs84"

    /** 输入坐标已经是 GCJ-02（高德/腾讯/学习通口径）→ 原样提交 */
    const val COORD_GCJ02 = "gcj02"

    /** 输入坐标是 BD-09（百度）→ 提交换算成 GCJ-02 */
    const val COORD_BD09 = "bd09"

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

    /**
     * 「立即签到」要处理的账号（多选）。
     *
     * 空集合 = 全部账号（保持老行为，避免升级后一按就什么都不签）。
     * 非空 = 只对这几个账号拉取签到任务。
     */
    var signAccountIds: Set<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("signAccountIds", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit().putString("signAccountIds", org.json.JSONArray(v.toList()).toString()).apply()

    /**
     * 作业页「按课程过滤」选中的课程（多选，`courseId:clazzId`）。
     *
     * **持久化**：下次进来还是这几个课程，不用每次重选。
     * 空集合 = 全部课程（第一次用、或用户主动清空）。
     *
     * 同时决定刷新范围 —— 只刷新这些课程，不再把 14 门课全扫一遍（这就是慢的根源）。
     */
    var homeworkCourseFilter: Set<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("homeworkCourseFilter", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit().putString("homeworkCourseFilter", org.json.JSONArray(v.toList()).toString()).apply()

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

    /**
     * 签到时是否**自动使用系统 GPS 当前位置**。
     *
     * - `true`：签到时现场从系统 GPS 取一次坐标，**原样提交，不做任何换算**。
     *   完全忽略手动输入框和 [coordSource]。
     * - `false`：用用户填在「位置坐标」里的值，并按 [coordSource] 解释那个值是哪种坐标系。
     *
     * ## 为什么自动模式不做换算（真机实测）
     *
     * 曾在这里无条件做一次 WGS→GCJ（理由是「系统 GPS 恒为 WGS」）。但实测推翻了它：
     *
     * ```
     * 系统 GPS 原始(WGS)  31.891270, 118.896994   离教室 652 米
     * 转一次后(GCJ)       31.889088, 118.902002   离教室 131 米   ← 数值上更近
     * ```
     *
     * 但提交这个更近的 GCJ 值，学习通反而报**超出范围**；改成原样提交后立刻签到成功。
     * 说明**服务端自己会把收到的坐标转一次**，我们再转就是双重偏移。
     */
    var useCurrentLocation: Boolean
        get() = sp.getBoolean("useCurrentLocation", true)
        set(v) = sp.edit().putBoolean("useCurrentLocation", v).apply()

    /**
     * **手动输入**坐标所用的坐标系 —— 语义是「我填进去的这串数字是什么坐标系」。
     *
     * 只在 [useCurrentLocation] = false 时生效。默认 [COORD_NONE]（原样提交）。
     *
     * 注意：这个设置**不影响**「使用当前位置」。系统 GPS 恒为 WGS-84，
     * 自动模式会无条件转成 GCJ-02，不看这里。
     *
     * ## 为什么要用户自己声明
     *
     * 虚拟定位 App 自己带坐标系选择（WGS-84 / GCJ-02 / BD-09），注入什么完全取决于
     * 用户在那里选哪个，App 无法推断。猜错就是多转一次：偏移转一次 **531 米**、
     * 两次 **1063 米** —— 真机事故「实际差 100 米，学习通却提示超出 1015 米」正是这么来的。
     */
    var coordSource: String
        get() = sp.getString("coordSource", COORD_NONE) ?: COORD_NONE
        set(v) = sp.edit().putString("coordSource", v).apply()

    /** 坐标系的可读名字，给设置页和日志用 */
    fun coordSourceLabel(v: String = coordSource): String = when (v) {
        COORD_WGS84 -> "WGS-84（GPS 原始）"
        COORD_GCJ02 -> "GCJ-02（高德/腾讯）"
        COORD_BD09 -> "BD-09（百度）"
        else -> "不转换（原样提交）"
    }

    /**
     * 一次性清理历史配置。
     *
     * 旧版本只有「坐标纠偏 开/关」这一个布尔开关，且默认开 —— 正是它造成了双重偏移。
     * 现在改成显式坐标系且默认不转换，所以升级时直接把 `coordSource` 复位成
     * [COORD_NONE]，把老用户的 `coordShift` 遗留值彻底丢掉。
     */
    fun migrateCoordShiftOnce() {
        if (sp.getBoolean("coordShiftMigrated", false)) return
        sp.edit()
            .putString("coordSource", COORD_NONE)
            .remove("coordShift")
            .putBoolean("coordShiftMigrated", true)
            .apply()
    }

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

    /** 文档任务点要不要自动完成（一次 GET 就完成，默认做） */
    var studyDocuments: Boolean
        get() = sp.getBoolean("studyDocuments", true)
        set(v) = sp.edit().putBoolean("studyDocuments", v).apply()


    /** 正在跑的课程（course.key） */
    var runningCourses: Set<String>
        get() = sp.getStringSet("runningCourses", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("runningCourses", v).apply()

    /** 弹题取题接口模板（真机抓包后填；占位符见 ConfiguredPopQuizBridge） */
    var popQuizFetchUrl: String
        get() = sp.getString("popQuizFetchUrl", "") ?: ""
        set(v) = sp.edit().putString("popQuizFetchUrl", v).apply()

    /** 弹题提交接口模板 */
    var popQuizSubmitUrl: String
        get() = sp.getString("popQuizSubmitUrl", "") ?: ""
        set(v) = sp.edit().putString("popQuizSubmitUrl", v).apply()

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

    /** 两份卷子之间的最小间隔（秒），默认 90；下限可调到 0（用户自己承担风控风险） */
    var quizGapSeconds: Int
        get() = sp.getInt("quizGap", 90).coerceIn(0, 1800)
        set(v) = sp.edit().putInt("quizGap", v.coerceIn(0, 1800)).apply()

    // ------------------------------------------------------------ 大模型

    /**
     * 大模型做题总开关。**这是「用大模型答题」的主开关**，填了 key 并且打开才会调模型。
     * （和 [quizLlmFallback] 不是一回事：那个只管「字体识别不出来时要不要兜底」，默认关。）
     */
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

    /**
     * DeepSeek 推理强度（reasoning_effort）：low / medium / high。
     * 越低越快越省，但复杂题更容易只出推理、正文为空；默认 low。
     */
    var quizLlmEffort: String
        get() = sp.getString("quizLlmEffort", "low")
            ?.lowercase()
            ?.takeIf { it in setOf("low", "medium", "high") }
            ?: "low"
        set(v) = sp.edit()
            .putString("quizLlmEffort", v.lowercase().takeIf { it in setOf("low", "medium", "high") } ?: "low")
            .apply()

    /**
     * 大模型答题前是否先用 DeepSeek Search 联网找资料。
     * 搜索到结果后把摘要交给模型总结，能明显提高时政/教材类题的命中率。
     */
    var quizWebSearch: Boolean
        get() = sp.getBoolean("quizWebSearch", true)
        set(v) = sp.edit().putBoolean("quizWebSearch", v).apply()

    /** 字体反解的最低相似度，低于它算「待复核」，不采信 */
    var quizFontThreshold: Float
        get() = sp.getFloat("quizFontThreshold", 0.86f).coerceIn(0.5f, 0.99f)
        set(v) = sp.edit().putFloat("quizFontThreshold", v.coerceIn(0.5f, 0.99f)).apply()

    /**
     * **字体识别不出时的 LLM 兜底**（独立开关，默认关）。
     *
     * 和 [quizLlmEnabled] 完全独立：
     *  - [quizLlmEnabled] = 用大模型做题（主开关，用户按需开）
     *  - [quizLlmFallback] = 字体解不出来时，额外把题丢给大模型猜一把
     *
     * 默认关，因为大模型会按自己的理解改写题干/选项，把原本正确的题带偏
     * （用户实际踩过：题干全对，却被 LLM「纠错」改错）。
     */
    var quizLlmFallback: Boolean
        get() = sp.getBoolean("quizLlmFallback", false)
        set(v) = sp.edit().putBoolean("quizLlmFallback", v).apply()

    /** 已勾选要刷的章节，元素形如 "courseId:clazzId#chapterId" */
    var studySelectedChapters: Set<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("studyChapters", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit()
            .putString("studyChapters", org.json.JSONArray(v.toList()).toString())
            .apply()

    /** 已勾选要做题的章节，元素形如 "courseId:clazzId#chapterId" */
    var quizSelectedChapters: Set<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("quizChapters", "[]"))
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        set(v) = sp.edit()
            .putString("quizChapters", org.json.JSONArray(v.toList()).toString())
            .apply()

    /** 作业详情页：切到新题时是否自动问 AI（默认关，只有手动点按钮才问） */
    var homeworkAutoAi: Boolean
        get() = sp.getBoolean("homeworkAutoAi", false)
        set(v) = sp.edit().putBoolean("homeworkAutoAi", v).apply()
}
