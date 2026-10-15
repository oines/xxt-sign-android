package com.xxt.sign.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Course(val courseId: String, val classId: String, val name: String = "")

data class SignLocation(val address: String, val lat: String, val lon: String)

/** 一次签到的可选项，由 UI 传入 */
class SignOptions(
    var signCode: String? = null,
    var enc: String? = null,
    var aid: String? = null,
    var location: SignLocation? = null,
    var photoBytes: ByteArray? = null,
    var objectId: String? = null,
    /** 人机验证通过后拿到的 validate，签到时要回传 */
    var validate: String? = null,
    /**
     * 二维码签到的 rcode。
     * 格式就是扫到的那串：`SIGNIN:aid=<aid>&source=15&Code=<code>&enc=<enc>`
     * 服务端不会把 code/enc 下发（signDetail 里这两个字段不存在），
     * 所以必须把扫码原文传回去。
     */
    var rcode: String? = null,
    /** 限定只在这些 "courseId:classId" 里找签到活动；空集合 = 全部课程 */
    var courseFilter: Set<String> = emptySet()
) {
    /** 派生一个只改了 validate 的副本 */
    fun withValidate(v: String?): SignOptions = SignOptions(
        signCode = signCode,
        enc = enc,
        aid = aid,
        location = location,
        photoBytes = photoBytes,
        objectId = objectId,
        validate = v,
        rcode = rcode,
        courseFilter = courseFilter
    )
}

/**
 * 学习通签到相关接口。所有请求都带该账号的 Cookie。
 */
class CxApi(private val s: CxSession) {

    private val c by lazy { Http.withJar(s.jar) }

    // ---------------------------------------------------------------- 课程

    suspend fun getCourses(): List<Course> = withContext(Dispatchers.IO) {
        val uid = s.uid
        if (uid.isNotBlank()) {
            CourseCache.get(uid)?.let { return@withContext it }
        }
        val list = loadCourses()
        if (uid.isNotBlank() && list.isNotEmpty()) CourseCache.put(uid, list)
        list
    }

    private suspend fun loadCourses(): List<Course> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Course>()

        // 主路径：backclazzdata 的结构是
        //   channelList[i].content.id                    -> clazzId
        //   channelList[i].content.cpi                   -> cpi
        //   channelList[i].content.course.data[0].id     -> courseId
        //   channelList[i].content.course.data[0].name   -> 课程名
        runCatching {
            val r = c.get("https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1")
            if (r.body.trimStart().startsWith("{")) parseBackclazz(JSONObject(r.body), out)
        }

        if (out.isEmpty()) {
            runCatching {
                val r = c.postForm(
                    "https://mooc1-1.chaoxing.com/visit/courselistdata",
                    mapOf("courseType" to "1", "courseFolderId" to "0", "courseFolderSize" to "0"),
                    mapOf("Accept" to "text/html, */*; q=0.01", "X-Requested-With" to "XMLHttpRequest")
                )
                val html = r.body.replace("&amp;", "&")
                Regex("course_(\\d+)_(\\d+)").findAll(html).forEach {
                    out[it.groupValues[1]] = Course(it.groupValues[1], it.groupValues[2])
                }
                Regex("courseid=(\\d+)&clazzid=(\\d+)").findAll(html).forEach {
                    out[it.groupValues[1]] = Course(it.groupValues[1], it.groupValues[2])
                }
                Regex("/visit/stucoursemiddle\\?courseid=(\\d+)[^\"']*clazzid=(\\d+)").findAll(html).forEach {
                    out[it.groupValues[1]] = Course(it.groupValues[1], it.groupValues[2])
                }
            }
        }
        out.values.toList()
    }

    /** 按 backclazzdata 的真实结构解析课程 */
    private fun parseBackclazz(json: JSONObject, out: MutableMap<String, Course>) {
        val channels = json.optJSONArray("channelList") ?: return
        for (i in 0 until channels.length()) {
            val content = channels.optJSONObject(i)?.optJSONObject("content") ?: continue
            val classId = content.optString("id")
            if (classId.isBlank() || !classId.all { it.isDigit() }) continue
            val className = content.optString("name")
            val data = content.optJSONObject("course")?.optJSONArray("data") ?: continue
            for (j in 0 until data.length()) {
                val co = data.optJSONObject(j) ?: continue
                val courseId = co.optString("id")
                if (courseId.isBlank() || !courseId.all { it.isDigit() }) continue
                out[courseId] = Course(
                    courseId = courseId,
                    classId = classId,
                    name = co.optString("name").ifBlank { className }
                )
            }
        }
    }

    // ------------------------------------------------------------ 活动查询

    /** 某课程下正在进行、且是签到 / 签退的活动 */
    suspend fun activeList(courseId: String, classId: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val url = "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist" +
            "?fid=${s.schoolId.ifBlank { "0" }}&courseId=$courseId&classId=$classId&_=${System.currentTimeMillis()}"
        val j = runCatching { JSONObject(c.get(url).body) }.getOrNull()
            ?: return@withContext emptyList()
        val d = j.optJSONObject("data") ?: return@withContext emptyList()
        val a = d.optJSONArray("activeList") ?: return@withContext emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter {
                if (it.optInt("status", 0) != 1) return@filter false
                // 2 = 签到，74 = 签退。两者字段结构完全一致，只差一个 activeId。
                // 官方 IM 推送也是同时处理这两种（atype != 2 && atype != 74 才忽略）。
                val t = it.optInt("activeType", 2)
                t == TYPE_ACTIVITY_SIGN || t == TYPE_ACTIVITY_SIGN_OUT
            }
    }

    /** 活动详情：含 signCode / enc / code / ifRefreshEwm / 经纬度 等 */
    suspend fun signDetail(aid: String): JSONObject = withContext(Dispatchers.IO) {
        val j = JSONObject(c.get("https://mobilelearn.chaoxing.com/newsign/signDetail?activePrimaryId=$aid&type=1").body)
        j.optJSONObject("data") ?: j
    }

    /** 活动详情（PPT 版），位置签到用它拿教师指定的地点 */
    suspend fun pptActiveInfo(aid: String): JSONObject = withContext(Dispatchers.IO) {
        val j = JSONObject(c.get("https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$aid").body)
        j.optJSONObject("data") ?: j
    }

    // -------------------------------------------------------------- 预签到

    /** 返回非空字符串表示预签到失败原因 */
    suspend fun preSign(
        courseId: String,
        classId: String,
        detail: JSONObject,
        rcode: String? = null
    ): String? =
        withContext(Dispatchers.IO) {
            val aid = detail.optString("id").ifBlank { detail.optString("activePrimaryId") }
            var url = "https://mobilelearn.chaoxing.com/newsign/preSign" +
                "?courseId=${enc(courseId)}&classId=${enc(classId)}&activePrimaryId=$aid" +
                "&general=1&sys=1&ls=1&appType=15&uid=${s.uid}&isTeacherViewOpen=0"

            // 动态二维码必须带 rcode，格式就是扫码原文：SIGNIN:aid=..&source=15&Code=..&enc=..
            // 优先用扫到的原文；没有扫码时才尝试从详情里拼（实测 signDetail 不下发
            // code/enc 这两个字段，所以没有扫码基本拼不出来）。
            val rc = rcode?.takeIf { it.isNotBlank() } ?: run {
                if (detail.optInt("ifRefreshEwm", 0) == 1) {
                    val code = detail.optString("code")
                    val qrEnc = detail.optString("enc")
                    if (qrEnc.isNotBlank()) "SIGNIN:aid=$aid&source=15&Code=$code&enc=$qrEnc" else null
                } else null
            }
            if (!rc.isNullOrBlank()) url += "&rcode=" + enc(rc)

            val html = c.get(url).body
            delay(400)

            // 位置签到必备的两步 analysis
            runCatching {
                val a1 = c.get("https://mobilelearn.chaoxing.com/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM&aid=$aid").body
                val code = Regex("code='\\+'(.*?)'").find(a1)?.groupValues?.getOrNull(1)
                if (!code.isNullOrBlank()) {
                    c.get("https://mobilelearn.chaoxing.com/pptSign/analysis2?DB_STRATEGY=RANDOM&code=$code")
                }
            }
            delay(300)

            Regex("id=\"statuscontent\"[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.getOrNull(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace(Regex("\\s"), "")
                ?.takeIf { it.isNotBlank() }
        }

    // ------------------------------------------------------------ 签到动作

    private suspend fun stuSign(params: List<Pair<String, String>>): SignOutcome = withContext(Dispatchers.IO) {
        val url = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax?" +
            params.joinToString("&") { "${it.first}=${enc(it.second)}" }
        val body = runCatching { c.get(url).body.trim() }
            .getOrElse { return@withContext SignOutcome.Failed("请求失败：${it.message}") }
        when {
            body == "success" -> SignOutcome.Done("签到成功")
            body.contains("您已签到过了") -> SignOutcome.Done("已经签过了")
            isCaptcha(body) -> SignOutcome.NeedCaptcha(body)
            body.startsWith("success") -> SignOutcome.Failed("签到已结束/过期")
            body.isBlank() -> SignOutcome.Failed("签到无响应（可能已过期）")
            else -> SignOutcome.Failed(body)
        }
    }

    /** 服务端要求过图形验证码时的各种返回值形态 */
    private fun isCaptcha(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("captcha") || b.contains("validate") ||
            body.contains("验证码") || body.contains("滑块") || body.contains("人机")
    }

    private fun baseParams(
        detail: JSONObject,
        validate: String? = null,
        courseId: String? = null
    ): MutableList<Pair<String, String>> {
        val aid = detail.optString("id").ifBlank { detail.optString("activePrimaryId") }
        val m = mutableListOf(
            "activeId" to aid,
            "uid" to s.uid,
            "clientip" to "",
            "appType" to "15",
            "fid" to s.schoolId,
            "name" to s.realName
        )
        // 官方版会带这两个反作弊字段（常量，服务器用来标记“本端不参与概率抽检”）。
        // 不带通常也能签上，但带上更接近官方行为，不容易被风控盯上。
        m.add("vpProbability" to "-1")
        m.add("vpStrategy" to "")
        val cid = courseId?.takeIf { it.isNotBlank() } ?: detail.optString("courseId")
        if (cid.isNotBlank()) m.add("courseId" to cid)
        if (!validate.isNullOrBlank()) m.add("validate" to validate)
        return m
    }

    /**
     * 按活动类型自动选择签到方式。
     */
    suspend fun signActivity(
        detail: JSONObject,
        courseId: String,
        classId: String,
        opt: SignOptions
    ): SignOutcome {
        val aid = detail.optString("id").ifBlank { detail.optString("activePrimaryId") }
        if (aid.isBlank()) return SignOutcome.Blocked("活动 ID 缺失")

        val otherId = detail.optInt("otherId", -1)
        val ifPhoto = detail.optInt("ifPhoto", detail.optInt("ifphoto", 0))

        val pre = preSign(courseId, classId, detail, opt.rcode)
        if (!pre.isNullOrBlank()) {
            // 预签到阶段的返回值也可能是验证码要求
            return if (isCaptcha(pre)) SignOutcome.NeedCaptcha(pre)
            else SignOutcome.Failed("预签到失败：$pre")
        }

        return when {
            otherId == TYPE_QRCODE -> {
                // 动态二维码：服务端 enc 几秒一变，优先用刚拉到的详情里的最新 enc，
                // 扫描得到的 enc 只作为兵底。
                val fresh = if (detail.optInt("ifRefreshEwm", 0) == 1) {
                    detail.optString("enc").takeIf { it.isNotBlank() }
                } else null
                val e = fresh
                    ?: opt.enc?.takeIf { it.isNotBlank() }
                    ?: detail.optString("enc").takeIf { it.isNotBlank() }
                if (e.isNullOrBlank()) return SignOutcome.Blocked("二维码签到但拿不到 enc，请用「扫码签到」")
                signQr(detail, e, opt.validate, courseId)
            }

            otherId == TYPE_GESTURE || otherId == TYPE_CODE -> {
                val code = opt.signCode?.takeIf { it.isNotBlank() }
                    ?: detail.optString("signCode").takeIf { it.isNotBlank() }
                    ?: return SignOutcome.Blocked("拿不到签到码，请手动输入")
                val err = checkSignCode(aid, code)
                if (err != null) return SignOutcome.Blocked("签到码校验失败：$err")
                delay(200)
                stuSign(
                    baseParams(detail, opt.validate).apply {
                        add("signCode" to code)
                        add("latitude" to "")
                        add("longitude" to "")
                    }
                )
            }

            otherId == TYPE_LOCATION -> {
                val loc = opt.location ?: locationOf(detail, aid)
                    ?: return SignOutcome.Blocked("位置签到拿不到坐标：教师未开放经纬度，请先在「位置设置」里填入你所在的位置")
                stuSign(
                    baseParams(detail, opt.validate).apply {
                        add("address" to loc.address)
                        add("latitude" to loc.lat)
                        add("longitude" to loc.lon)
                        add("ifTiJiao" to "1")
                        if (opt.validate.isNullOrBlank()) add("validate" to "")
                    }
                )
            }

            ifPhoto == 1 -> {
                val oid = opt.objectId?.takeIf { it.isNotBlank() }
                    ?: opt.photoBytes?.let { uploadPhoto(it) }
                    ?: return SignOutcome.Blocked("拍照签到需要图片，请在「拍照签到图片」里选一张")
                stuSign(
                    baseParams(detail, opt.validate).apply {
                        add("objectId" to oid)
                        add("useragent" to "")
                        add("latitude" to "-1")
                        add("longitude" to "-1")
                    }
                )
            }
            else -> stuSign(
                baseParams(detail, opt.validate).apply {
                    add("latitude" to "-1")
                    add("longitude" to "-1")
                }
            )
        }
    }

    suspend fun signQr(
        detail: JSONObject,
        qrEnc: String,
        validate: String? = null,
        courseId: String? = null
    ): SignOutcome = stuSign(
        baseParams(detail, validate, courseId).apply {
            add("enc" to qrEnc)
            add("useragent" to "")
            add("latitude" to "-1")
            add("longitude" to "-1")
        }
    )

    private suspend fun checkSignCode(aid: String, code: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(
                c.get("https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode?activeId=$aid&signCode=${enc(code)}").body
            )
            if (j.optInt("result", 0) == 1) null else j.optString("errorMsg").ifBlank { "签到码不正确" }
        }.getOrNull()
    }

    /**
     * 位置签到的坐标。
     *
     * 实测结论（54 个真实活动）：教师端的 `locationLatitude` / `locationLongitude`
     * **全部为空**，只有 `locationText` 地址文本。所以坐标只能靠手机定位或用户手动设。
     * 拿不到就返回 null，让上层明确报错——不要送去 -1,-1 白白浪费一次签到机会。
     */
    suspend fun locationOf(detail: JSONObject, aid: String): SignLocation? {
        val info = runCatching { pptActiveInfo(aid) }.getOrNull()
        val address = info?.optString("locationText").orEmpty().ifBlank { detail.optString("locationText") }

        fun ok(v: String?) = !v.isNullOrBlank() && v != "-1" && v != "0" && v != "-1.0" && v != "0.0"

        val lat = info?.optString("locationLatitude").orEmpty()
            .ifBlank { detail.optString("latitude") }
        val lon = info?.optString("locationLongitude").orEmpty()
            .ifBlank { detail.optString("longitude") }
        if (ok(lat) && ok(lon)) return SignLocation(address, lat, lon)

        val uLat = com.xxt.sign.core.Settings.latitude
        val uLon = com.xxt.sign.core.Settings.longitude
        if (ok(uLat) && ok(uLon)) {
            val uAddr = com.xxt.sign.core.Settings.address.ifBlank { address }
            return SignLocation(uAddr, uLat, uLon)
        }
        return null
    }

    /** 上传图片到超星云盘，拿到 objectId（拍照签到用） */
    suspend fun uploadPhoto(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val token = c.get("https://pan-yz.chaoxing.com/api/token/uservalid").body.trim().trim('"')
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "1.png", bytes.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("puid", s.uid)
                .build()
            val req = Request.Builder()
                .url("https://pan-yz.chaoxing.com/upload?_from=mobilelearn&_token=${enc(token)}")
                .post(body)
                .header("User-Agent", Http.UA)
                .build()
            val r = c.exec(req)
            JSONObject(r.body).optString("objectId").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_QRCODE = 2
        const val TYPE_GESTURE = 3
        const val TYPE_LOCATION = 4
        const val TYPE_CODE = 5

        /** 活动大类：签到 */
        const val TYPE_ACTIVITY_SIGN = 2

        /** 活动大类：签退（教师单独发布的一个活动，字段结构与签到一致） */
        const val TYPE_ACTIVITY_SIGN_OUT = 74

        fun typeName(otherId: Int, ifPhoto: Int): String = when {
            otherId == TYPE_QRCODE -> "二维码签到"
            otherId == TYPE_GESTURE -> "手势签到"
            otherId == TYPE_LOCATION -> "位置签到"
            otherId == TYPE_CODE -> "签到码签到"
            ifPhoto == 1 -> "拍照签到"
            else -> "普通签到"
        }

        /** 完整标签：类型 + 是签到还是签退 */
        fun label(detail: JSONObject): String {
            val base = typeName(detail.optInt("otherId", -1), detail.optInt("ifPhoto", 0))
            val isSignOut = detail.optInt("activeType", TYPE_ACTIVITY_SIGN) == TYPE_ACTIVITY_SIGN_OUT
            return if (isSignOut) base.replace("签到", "") + "·签退" else base
        }
    }
}

/**
 * 课程列表缓存。巡检频率高，每次都重新拉课程列表太浪费。
 */
object CourseCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private val map = HashMap<String, Pair<Long, List<Course>>>()

    @Synchronized
    fun get(uid: String): List<Course>? {
        val e = map[uid] ?: return null
        if (System.currentTimeMillis() - e.first > TTL_MS) {
            map.remove(uid)
            return null
        }
        return e.second
    }

    @Synchronized
    fun put(uid: String, list: List<Course>) {
        map[uid] = System.currentTimeMillis() to list
    }

    @Synchronized
    fun clear() = map.clear()
}
