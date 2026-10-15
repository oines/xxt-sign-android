package com.xxt.sign.net

import android.graphics.BitmapFactory
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 学习通人机验证（滑块）。
 *
 * 这套流程已完整实测打通，**不需要 WebView，也不需要官方 SDK**：
 *
 *  1. `get/conf`              → 拿 `t`
 *  2. 本地按 MD5 算三个签名参数（不是加密，就是 MD5）：
 *       captchaKey = md5(t + uuid)
 *       iv         = md5(captchaId + type + 毫秒时间戳 + uuid)
 *       token      = md5(t + captchaId + type + captchaKey) + ":" + (t + 300000)
 *  3. `get/verification/image` → 背景图 + 滑块图 + 新的 token
 *  4. `check/verification/result` → extraData.validate
 *
 * 这里把「取图」和「提交」拆成两步，因为人工拖拽流程需要先渲染图片、
 * 等人拖完再提交。自动解则是这两步之间插一段位移计算。
 *
 * 实测补充：`type` 是**客户端选的**，服务端给什么要什么——
 * 签到和登录两个 captchaId 都能请求 slide，所以一套滑块 UI 就够。
 */
object CxCaptcha {

    /** 签到 / 签退场景 */
    const val CAPTCHA_ID_SIGN = "Qt9FIw9o4pwRjOyqM6yizZBh682qN2TU"

    /** 登录场景（含发短信验证码） */
    const val CAPTCHA_ID_LOGIN = "GcXX5vewqE7DezKGlyvleKCnkTglvGpL"

    private const val URL_CONF = "https://captcha.chaoxing.com/captcha/get/conf"
    private const val URL_IMAGE = "https://captcha.chaoxing.com/captcha/get/verification/image"
    private const val URL_CHECK = "https://captcha.chaoxing.com/captcha/check/verification/result"
    private const val VERSION = "1.1.20"
    const val TYPE_SLIDE = "slide"

    /** 一次滑块挑战：两张图 + 提交所需的一切 */
    class Challenge(
        val captchaId: String,
        val type: String,
        /** check 接口要用的 token（取图响应里的那个，不是请求时的） */
        val token: String,
        val iv: String,
        val shadeBytes: ByteArray,
        val cutoutBytes: ByteArray,
        /** 拼图在滑块图里的包围盒；UI 渲染和位移计算都用它 */
        val piece: Rect,
        val bgWidth: Int,
        val bgHeight: Int
    )

    sealed class Fetched {
        data class Ok(val challenge: Challenge) : Fetched()
        data class Err(val msg: String) : Fetched()
    }

    /**
     * 取一次挑战（拉配置、算签名、下两张图）。
     * @param referer 预签到页或登录页地址，服务端会看，但不做校验（已实测）
     */
    suspend fun fetch(
        client: OkHttpClient,
        captchaId: String = CAPTCHA_ID_SIGN,
        referer: String,
        type: String = TYPE_SLIDE
    ): Fetched = withContext(Dispatchers.IO) {
        runCatching {
            val hdrs = mapOf("Referer" to referer)

            val confBody = client.get("$URL_CONF?callback=cx_captcha_function&captchaId=$captchaId&_=${now()}", hdrs).body
            val t = jsonp(confBody)?.optLong("t") ?: return@runCatching Fetched.Err("get/conf 返回异常")

            val captchaKey = md5("$t${UUID.randomUUID()}")
            val iv = md5("$captchaId$type${now()}${UUID.randomUUID()}")
            val token = md5("$t$captchaId$type$captchaKey") + ":" + (t + 300_000L)

            val imgUrl = "$URL_IMAGE?callback=cx_captcha_function&captchaId=$captchaId&type=$type" +
                "&version=$VERSION&captchaKey=$captchaKey&token=${enc(token)}" +
                "&referer=${enc(referer)}&iv=$iv&_=${now()}"
            val img = jsonp(client.get(imgUrl, hdrs).body)
                ?: return@runCatching Fetched.Err("取图接口返回异常")
            val vo = img.optJSONObject("imageVerificationVo")
                ?: return@runCatching Fetched.Err("取图响应里没有 imageVerificationVo")
            val shadeUrl = vo.optString("shadeImage")
            val cutoutUrl = vo.optString("cutoutImage")
            val innerToken = img.optString("token")
            if (shadeUrl.isBlank() || cutoutUrl.isBlank() || innerToken.isBlank()) {
                return@runCatching Fetched.Err("图片地址或 token 缺失")
            }

            val shade = client.get(shadeUrl, hdrs).rawBytes
            val cutout = client.get(cutoutUrl, hdrs).rawBytes
            if (shade.isEmpty() || cutout.isEmpty()) return@runCatching Fetched.Err("图片下载失败")

            val bg = BitmapFactory.decodeByteArray(shade, 0, shade.size)
                ?: return@runCatching Fetched.Err("背景图解码失败")
            val pc = BitmapFactory.decodeByteArray(cutout, 0, cutout.size)
                ?: return@runCatching Fetched.Err("滑块图解码失败")

            val piece = pieceBounds(pc)
                ?: return@runCatching Fetched.Err("滑块图里找不到拼图")

            Fetched.Ok(
                Challenge(
                    captchaId = captchaId,
                    type = type,
                    token = innerToken,
                    iv = iv,
                    shadeBytes = shade,
                    cutoutBytes = cutout,
                    piece = piece,
                    bgWidth = bg.width,
                    bgHeight = bg.height
                )
            )
        }.getOrElse { Fetched.Err(it.message ?: "网络异常") }
    }

    /**
     * 提交拖拽距离，换取 validate。
     * @param drag 拼图相对初始位置向右拖了多少像素（不是绝对坐标）
     * @return 成功返回 validate，失败返回 null（位置不对），异常返回错误串
     */
    suspend fun verify(
        client: OkHttpClient,
        ch: Challenge,
        drag: Int,
        referer: String
    ): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val hdrs = mapOf("Referer" to referer)
            val q = "$URL_CHECK?callback=cx_captcha_function&captchaId=${ch.captchaId}&type=${ch.type}" +
                "&token=${enc(ch.token)}&textClickArr=${enc("""[{"x":$drag}]""")}" +
                "&coordinate=${enc("[]")}&runEnv=10&version=$VERSION&t=a&iv=${ch.iv}&_=${now()}"
            val out = jsonp(client.get(q, hdrs).body) ?: return@runCatching Result(null, "校验接口返回异常")

            if (out.optInt("error", 0) == 1) {
                return@runCatching Result(null, out.optString("msg").ifBlank { "校验被拒" })
            }
            if (!out.optBoolean("result", false)) {
                return@runCatching Result(null, "位置不对")
            }
            val extra = out.optString("extraData")
            val validate = runCatching {
                JSONObject(extra.replace("\\\"", "\"")).optString("validate")
            }.getOrNull().orEmpty()
            if (validate.isBlank()) Result(null, "响应里没有 validate") else Result(validate, null)
        }.getOrElse { Result(null, it.message ?: "网络异常") }
    }

    data class Result(val validate: String?, val error: String?) {
        val ok: Boolean get() = !validate.isNullOrBlank()
    }

    // ------------------------------------------------------ 自动解（可选）

    /**
     * 自动解一次。已不再作为默认路径使用，保留给诊断和自检。
     */
    suspend fun solve(
        client: OkHttpClient,
        captchaId: String = CAPTCHA_ID_SIGN,
        referer: String,
        type: String = TYPE_SLIDE,
        tries: Int = 5
    ): Result = withContext(Dispatchers.IO) {
        var last = "未知错误"
        repeat(tries) {
            when (val f = fetch(client, captchaId, referer, type)) {
                is Fetched.Err -> last = f.msg
                is Fetched.Ok -> {
                    val drag = runCatching { computeDrag(f.challenge) }.getOrNull()
                        ?: return@withContext Result(null, "位移计算失败")
                    val r = verify(client, f.challenge, drag, referer)
                    if (r != null && r.ok) return@withContext r
                    last = r?.error ?: "校验无响应"
                }
            }
        }
        Result(null, "重试 $tries 次仍未通过：$last")
    }

    /**
     * 求水平拖动距离。
     *
     * 滑块图是「纯黑底 + 一块拼图」，所以非黑包围盒就是拼图本体；
     * 拼图在滑块图里的纵向位置就是它在背景图里的纵向位置，只需搜横向。
     * 每个候选 x 用「尺度拟合最小残差」打分：允许整体缩放 k，取残差最小者。
     * 不要用「最暗区域」定位——背景照片的暗部会给出完全错误的答案。
     */
    internal fun computeDrag(ch: Challenge): Int? {
        val bg = BitmapFactory.decodeByteArray(ch.shadeBytes, 0, ch.shadeBytes.size) ?: return null
        val pc = BitmapFactory.decodeByteArray(ch.cutoutBytes, 0, ch.cutoutBytes.size) ?: return null
        val bw = bg.width; val bh = bg.height
        val pw = pc.width; val ph = pc.height
        val bpx = IntArray(bw * bh).also { bg.getPixels(it, 0, bw, 0, 0, bw, bh) }
        val ppx = IntArray(pw * ph).also { pc.getPixels(it, 0, pw, 0, 0, pw, ph) }

        val x0 = ch.piece.left; val x1 = ch.piece.right - 1
        val y0 = ch.piece.top; val y1 = ch.piece.bottom - 1
        val tw = x1 - x0 + 1; val th = y1 - y0 + 1
        if (tw < 10 || th < 10 || y0 + th > bh || tw >= bw) return null

        var bestX = -1
        var bestRes = Double.MAX_VALUE
        for (x in 0..(bw - tw)) {
            var dot = 0.0; var pp = 1e-6; var rr = 1e-9
            for (yy in 0 until th) {
                val bRow = (y0 + yy) * bw + x
                val pRow = (y0 + yy) * pw + x0
                for (xx in 0 until tw) {
                    val bc = bpx[bRow + xx]; val cv = ppx[pRow + xx]
                    val br = ((bc shr 16) and 0xFF).toDouble(); val bgc = ((bc shr 8) and 0xFF).toDouble(); val bb = (bc and 0xFF).toDouble()
                    val pr = ((cv shr 16) and 0xFF).toDouble(); val pg = ((cv shr 8) and 0xFF).toDouble(); val pb = (cv and 0xFF).toDouble()
                    dot += br * pr + bgc * pg + bb * pb
                    pp += pr * pr + pg * pg + pb * pb
                    rr += br * br + bgc * bgc + bb * bb
                }
            }
            val k = dot / pp
            if (k <= 0.2) continue
            val res = sqrt(max(0.0, rr - 2 * k * dot + k * k * pp)) / (sqrt(rr) + 1e-6)
            if (res < bestRes) { bestRes = res; bestX = x }
        }
        if (bestX < 0) return null
        return (bestX - x0).coerceAtLeast(0)
    }

    /** 滑块图里拼图的包围盒（非黑区域） */
    internal fun pieceBounds(pc: android.graphics.Bitmap): Rect? {
        val w = pc.width; val h = pc.height
        val px = IntArray(w * h).also { pc.getPixels(it, 0, w, 0, 0, w, h) }
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val lum = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                if (lum > 18) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        if (x1 < 0) return null
        return Rect(x0, y0, x1 + 1, y1 + 1)
    }

    // -------------------------------------------------------------- 工具

    private fun now() = System.currentTimeMillis()

    private fun jsonp(body: String): JSONObject? = runCatching {
        val s = body.trim()
        val start = s.indexOf('(')
        val end = s.lastIndexOf(')')
        val json = if (start >= 0 && end > start) s.substring(start + 1, end) else s
        JSONObject(json)
    }.getOrNull()

    fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
