package com.xxt.sign.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import com.xxt.sign.core.LogStore

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
    var courseFilter: Set<String> = emptySet(),
    /**
     * 提交时的 appType。真实 App 里课堂入口是 0、扫码入口是 15，
     * 服务端两者都接受，但带上正确的值更像官方客户端。
     */
    var appType: Int = CxApi.APP_TYPE_COURSE,
    /** 扫到 enc 的时刻，用来配合 ewmRefreshTime 判断这个 enc 还能不能用 */
    var encAt: Long = 0L
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
        courseFilter = courseFilter,
        appType = appType,
        encAt = encAt
    )
}

/**
 * 会话已失效（HTTP 401/403）。
 *
 * 单独一个异常类型：403 的空白响应体绝不能当成「预签到成功」，
 * 否则一路走到提交才失败，最后报一个误导性的「签到无响应」。
 */
class CxSessionExpiredException(message: String) : Exception(message)

// ---------------------------------------------------------------- JSON 小工具

/** 按候选键名依次取整数：getPPTActiveInfo 的闸门字段是全小写，signDetail 里是驼峰 */
private fun JSONObject.intAny(vararg keys: String): Int {
    for (k in keys) {
        if (has(k) && !isNull(k)) return optInt(k, 0)
    }
    return 0
}

/** 按候选键名依次取非空字符串（跳过 JSON null 被转成的 "null"） */
private fun JSONObject.strAny(vararg keys: String): String {
    for (k in keys) {
        val v = optString(k).trim()
        if (v.isNotBlank() && v != "null") return v
    }
    return ""
}

/**
 * 学习通签到相关接口。所有请求都带该账号的 Cookie。
 */
class CxApi(private val s: CxSession) {

    /** 直接用该账号自己的客户端，保证 UA / Cookie 都一致 */
    val client: OkHttpClient get() = s.client

    private val c: OkHttpClient get() = s.client

    /** getPPTActiveInfo 的短缓存：一次签到里多个环节都要用同一份 */
    private val infoCache = HashMap<String, Pair<Long, JSONObject>>()
    private val infoLock = Any()

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

    /**
     * 活动详情（PPT 版）。位置签到用它拿地址、二维码签到用它拿刷新周期。
     *
     * 注意字段名：这个接口的闸门字段是**全小写**
     * （`otherId` `ifphoto` `ifrefreshewm` `ifopenAddress` `ewmRefreshTime` `locationRange` `locationText` `numberCount`），
     * 而 `signDetail` 里是驼峰（`ifPhoto` / `ifRefreshEwm`）。读的时候要分开对。
     *
     * 一次签到里会被好几个地方问到（闸门判断 / enc / 地址），所以做一个短 TTL 缓存。
     */
    suspend fun pptActiveInfo(aid: String, force: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        synchronized(infoLock) {
            val cached = infoCache[aid]
            if (!force && cached != null && System.currentTimeMillis() - cached.first < INFO_TTL_MS) {
                return@withContext cached.second
            }
        }
        val body = c.get("https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$aid").body
        val j = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
        val data = j.optJSONObject("data") ?: j
        synchronized(infoLock) {
            infoCache[aid] = System.currentTimeMillis() to data
        }
        data
    }

    /**
     * 问服务端要一次当前有效的二维码 enc（best-effort）。
     *
     * 真实二维码的 enc 只存在二维码图像里，`signDetail` 不下发这个字段；
     * 这个接口能拿到一份，拿不到（活动未开始 / 已结束 / 无权限）就返回 null，
     * 由上层退回「请扫码」。失败不影响原流程。
     */
    suspend fun refreshQRCode(aid: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = c.get("https://mobilelearn.chaoxing.com/v2/apis/sign/refreshQRCode?activeId=$aid").body
            val j = JSONObject(body)
            val ok = j.optBoolean("result") || j.optInt("result", 0) == 1
            if (!ok) {
                LogStore.add("[二维码] refreshQRCode 未拿到 enc：${j.optString("errorMsg").take(40)}", true)
                return@runCatching null
            }
            sequenceOf(
                j.strAny("enc"),
                j.optJSONObject("data")?.strAny("enc").orEmpty(),
                j.strAny("data")
            ).firstOrNull { it.isNotBlank() }
        }.getOrNull()
    }

    // -------------------------------------------------------------- 预签到

    /**
     * 预签到。返回非空字符串表示预签到失败原因。
     *
     * 真实学生端是在 `newsign/preSign` 页面完成的：页面加载先带一遍参数请求，
     * 服务端把「本次签到上下文」记在这个会话上，之后提交才认。
     *
     * 这里拿不到 2xx 就抛 [CxSessionExpiredException]：403 的响应体是空的，
     * 当成成功会一路拖到提交阶段才失败，最后只报一句「签到无响应」。
     */
    private suspend fun preSign(
        courseId: String,
        classId: String,
        detail: JSONObject,
        rcode: String?,
        appType: Int
    ): String? =
        withContext(Dispatchers.IO) {
            val aid = detail.optString("id").ifBlank { detail.optString("activePrimaryId") }
            var url = "https://mobilelearn.chaoxing.com/newsign/preSign" +
                "?courseId=${enc(courseId)}&classId=${enc(classId)}&activePrimaryId=$aid" +
                "&general=1&sys=1&ls=1&appType=$appType&uid=${s.uid}&isTeacherViewOpen=0"

            // 动态二维码必须带 rcode，格式就是扫码原文：SIGNIN:aid=..&source=15&Code=..&enc=..
            // 只能来自扫码：signDetail 里没有 code / enc 这两个字段。
            if (!rcode.isNullOrBlank()) url += "&rcode=" + enc(rcode)

            val r = c.get(url)
            if (r.code == 401 || r.code == 403) {
                throw CxSessionExpiredException("预签到返回 HTTP ${r.code}")
            }
            com.xxt.sign.core.Jitter.betweenRequests()

            Regex("id=\"statuscontent\"[^>]*>(.*?)</", RegexOption.DOT_MATCHES_ALL)
                .find(r.body)?.groupValues?.getOrNull(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace(Regex("\\s"), "")
                ?.takeIf { it.isNotBlank() }
        }

    // ------------------------------------------------------------ 签到动作

    /**
     * 提交签到：**POST 表单** 到 `/pptSign/stuSignajax`。
     *
     * 真实页面先把参数拼成 query（每项 encodeURIComponent），再 `urlParamsToObject`
     * 逐项 `decodeURIComponent` 后作为表单字段 POST 出去，所以服务端看到的 field 是原文。
     * 这里两种位置都带一份（值完全一致，服务端取哪个都一样），`locationResult` 只在 body 里。
     */
    private suspend fun stuSign(
        params: LinkedHashMap<String, String>,
        extraForm: Map<String, String> = emptyMap()
    ): SignOutcome = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val form = LinkedHashMap<String, String>(params).apply { putAll(extraForm) }

        val r = runCatching {
            c.postForm(
                "$URL_STUSIGN?$query",
                form,
                mapOf(
                    "Referer" to "$URL_PRESIGN?activePrimaryId=0",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )
        }.getOrElse { return@withContext SignOutcome.Failed("请求失败：${it.message}") }

        if (r.code == 401 || r.code == 403) {
            throw CxSessionExpiredException("签到提交返回 HTTP ${r.code}")
        }
        SignResponse.classify(r.body)
    }

    /**`location` / `locationResult` 里那份 JSON，页面就是这么构造的 */
    private fun locationJson(loc: SignLocation): String = JSONObject().apply {
        put("result", "success")
        put("address", loc.address)
        put("longitude", loc.lon)
        put("latitude", loc.lat)
    }.toString()

    /**
     * 组装提交参数，顺序与真实页面一致。
     * `location` 与 `locationResult` 是同一份 JSON（页面里前者被 encode 过，解码后相同）。
     * 注意：**没有** `vpProbability` / `vpStrategy`，页面里根本不存在这两个参数。
     */
    private fun submitParams(
        detail: JSONObject,
        courseId: String?,
        signCode: String,
        opt: SignOptions,
        loc: SignLocation?
    ): LinkedHashMap<String, String> {
        val aid = detail.optString("id").ifBlank { detail.optString("activePrimaryId") }
        val m = linkedMapOf(
            "activeId" to aid,
            "courseId" to (courseId?.takeIf { it.isNotBlank() } ?: detail.strAny("courseId")),
            "uid" to s.uid,
            "clientip" to "",
            "latitude" to (loc?.lat ?: ""),
            "longitude" to (loc?.lon ?: ""),
            "appType" to opt.appType.toString(),
            "fid" to s.schoolId,
            "name" to s.realName,
            "signCode" to signCode,
            "deviceCode" to s.deviceCode,
            "location" to (loc?.let { locationJson(it) } ?: "")
        )
        if (!opt.validate.isNullOrBlank()) m["validate"] = opt.validate!!
        return m
    }

    /**
     * 按活动类型自动选择签到方式。
     *
     * 已经拿到过 validate（人工过完验证码）再调一次时会**跳过预签到**：
     * 页面就是对同一个提交追加 `validate` 重发，不需要重新预签一次。
     */
    suspend fun signActivity(
        detail: JSONObject,
        courseId: String,
        classId: String,
        opt: SignOptions
    ): SignOutcome {
        val aid = detail.optString("id").ifBlank { detail.optString("activePrimaryId") }
        if (aid.isBlank()) return SignOutcome.Blocked("活动 ID 缺失")

        val info = runCatching { pptActiveInfo(aid) }.getOrNull()
        val otherId = detail.optInt("otherId", -1).takeIf { it >= 0 }
            ?: info?.optInt("otherId", -1)?.takeIf { it >= 0 }
            ?: -1
        // signDetail 里叫 ifPhoto，getPPTActiveInfo 里叫 ifphoto
        val ifPhoto = detail.intAny("ifPhoto", "ifphoto")
            .takeIf { it != 0 } ?: (info?.intAny("ifphoto", "ifPhoto") ?: 0)
        // ifopenAddress 只在 getPPTActiveInfo 里，且是全小写
        val needAddress = info?.intAny("ifopenAddress") == 1 || otherId == TYPE_LOCATION

        // 定位：地址校验类活动拿不到坐标就**不提交**（页面也是直接不提交）。
        // 绝不能用 -1,-1 硬发一次，那只是白白用掉一次签到机会。
        val loc = opt.location ?: runCatching { locationOf(detail, aid) }.getOrNull()
        if (loc == null && needAddress) {
            return SignOutcome.Blocked("拿不到定位，无法签到：该活动要求位置校验，请先在「位置设置」填坐标")
        }

        if (opt.validate.isNullOrBlank()) {
            val pre = preSign(courseId, classId, detail, opt.rcode, opt.appType)
            if (!pre.isNullOrBlank()) {
                return if (SignResponse.isCaptcha(pre)) SignOutcome.NeedCaptcha(pre)
                else SignOutcome.Failed("预签到失败：$pre")
            }
        }

        var signCode = ""
        val extras = LinkedHashMap<String, String>()

        when {
            otherId == TYPE_QRCODE -> {
                // enc 只存在于二维码里（signDetail 不下发），所以只能来自扫码或 refreshQRCode。
                // 时效看 getPPTActiveInfo 的 ewmRefreshTime（实测 5 / 10 / 20 秒）：
                // 扫到的 enc 已经超过这个时长就先去问服务端要一份新的。
                val scanned = opt.enc?.takeIf { it.isNotBlank() }
                val ttlMs = (info?.optInt("ewmRefreshTime", 0) ?: 0) * 1000L
                val stale = ttlMs > 0 && opt.encAt > 0 &&
                    System.currentTimeMillis() - opt.encAt > ttlMs
                val e = when {
                    scanned == null -> refreshQRCode(aid)
                    !opt.validate.isNullOrBlank() || stale -> refreshQRCode(aid) ?: scanned
                    else -> scanned
                }
                if (e.isNullOrBlank()) {
                    return SignOutcome.Blocked("二维码签到拿不到 enc：请用「扫码签到」重新扫一次大屏二维码")
                }
                extras["enc"] = e
            }

            otherId == TYPE_GESTURE || otherId == TYPE_CODE -> {
                signCode = opt.signCode?.takeIf { it.isNotBlank() }
                    ?: detail.strAny("signCode")
                if (signCode.isBlank()) return SignOutcome.Blocked("拿不到签到码，请手动输入")
                when (val chk = checkSignCode(aid, signCode)) {
                    is CodeCheck.Banned ->
                        return SignOutcome.Blocked("签到码已被封禁（尝试次数过多）：${chk.msg}")
                    is CodeCheck.Rejected ->
                        return SignOutcome.Blocked("签到码校验失败：${chk.msg}")
                    CodeCheck.Ok -> Unit
                }
                com.xxt.sign.core.Jitter.betweenRequests()
            }

            ifPhoto == 1 -> {
                val oid = opt.objectId?.takeIf { it.isNotBlank() }
                    ?: opt.photoBytes?.let { uploadPhoto(it) }
                    ?: return SignOutcome.Blocked("拍照签到需要图片，请在「拍照签到图片」里选一张")
                extras["objectId"] = oid
            }
        }

        val params = submitParams(detail, courseId, signCode, opt, loc)
        params.putAll(extras)
        val locJson = loc?.let { locationJson(it) }
        return stuSign(
            params,
            if (locJson != null) mapOf("locationResult" to locJson) else emptyMap()
        )
    }

    /** 只签二维码（保留给外部直接调用） */
    suspend fun signQr(
        detail: JSONObject,
        qrEnc: String,
        validate: String? = null,
        courseId: String? = null
    ): SignOutcome {
        val opt = SignOptions(enc = qrEnc, validate = validate, appType = APP_TYPE_SCAN)
        val params = submitParams(detail, courseId, "", opt, null)
        params["enc"] = qrEnc
        return stuSign(params)
    }

    /** 签到码预校验的结果 */
    private sealed class CodeCheck {
        /** result == 1，通过 */
        object Ok : CodeCheck()

        /** 普通校验失败（签到码不对） */
        data class Rejected(val msg: String) : CodeCheck()

        /** result == -1：签到码被暴力破解封禁，要单独报出来 */
        data class Banned(val msg: String) : CodeCheck()
    }

    /**
     * 签到码预校验。真实页面提交前**真的会调**这个接口，不是多余的一步。
     * `result=1` 通过；`result=-1` 是暴力破解封禁，不能混进「签到码不正确」。
     */
    private suspend fun checkSignCode(aid: String, code: String): CodeCheck = withContext(Dispatchers.IO) {
        runCatching {
            val body = c.get(
                "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode" +
                    "?activeId=$aid&signCode=${enc(code)}"
            ).body
            val j = runCatching { JSONObject(body) }.getOrNull() ?: return@runCatching CodeCheck.Ok
            when (j.optInt("result", 0)) {
                1 -> CodeCheck.Ok
                -1 -> CodeCheck.Banned(j.strAny("errorMsg", "msg").ifBlank { "签到码已被封禁" })
                else -> CodeCheck.Rejected(j.strAny("errorMsg", "msg").ifBlank { "签到码不正确" })
            }
        }.getOrElse { CodeCheck.Ok } // 预校验本身出错不应拦住真正的提交
    }

    /**
     * 位置签到的坐标。
     *
     * 实测结论（54 个真实活动）：教师端的 `locationLatitude` / `locationLongitude`
     * **全部为空**，只有 `locationText` 地址文本。距离由服务端算，客户端只负责报出自己的坐标。
     * 所以只能靠手机定位或用户预置坐标；拿不到就返回 null，让上层明确报错。
     */
    suspend fun locationOf(detail: JSONObject, aid: String): SignLocation? {
        val info = runCatching { pptActiveInfo(aid) }.getOrNull()
        val address = info?.strAny("locationText").orEmpty()
            .ifBlank { detail.strAny("locationText") }

        fun ok(v: String?) = !v.isNullOrBlank() && v != "-1" && v != "0" && v != "-1.0" && v != "0.0"

        val lat = info?.strAny("locationLatitude", "latitude").orEmpty()
            .ifBlank { detail.strAny("locationLatitude", "latitude") }
        val lon = info?.strAny("locationLongitude", "longitude").orEmpty()
            .ifBlank { detail.strAny("locationLongitude", "longitude") }
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
            // token 接口返回的是 JSON：{"result":true,"_token":"0e55..."}
            // 把整串 JSON 当 token 用必然拿不到 objectId，必须取 _token 字段。
            val raw = c.get("https://pan-yz.chaoxing.com/api/token/uservalid").body.trim()
            val token = runCatching { JSONObject(raw).optString("_token") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: raw.trim('"').takeIf { !it.contains('{') }
                ?: return@runCatching null
            if (token.isBlank()) return@runCatching null

            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "1.png", bytes.toRequestBody("image/png".toMediaType()))
                .addFormDataPart("puid", s.uid)
                // 拼图 token 不仅要拼在 URL 上，表单里也要带一份
                .addFormDataPart("_token", token)
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
        /** 课堂入口（课程列表 → 活动） */
        const val APP_TYPE_COURSE = 0

        /** 扫码入口（扫大屏二维码直接签） */
        const val APP_TYPE_SCAN = 15

        private const val URL_STUSIGN = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax"
        private const val URL_PRESIGN = "https://mobilelearn.chaoxing.com/newsign/preSign"

        /** getPPTActiveInfo 的缓存时长：一次签到内多个环节共用，但不能让 enc / 闸门过期 */
        private const val INFO_TTL_MS = 15_000L

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
