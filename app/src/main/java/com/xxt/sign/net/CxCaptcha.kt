package com.xxt.sign.net

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 学习通人机验证（滑块）自动求解。
 *
 * 这套流程已经完全实测打通，**不需要 WebView，也不需要人工**：
 *
 *  1. `get/conf`              → 拿 `t`
 *  2. 本地按 MD5 算出三个签名参数：
 *       captchaKey = md5(t + uuid)
 *       iv         = md5(captchaId + type + 毫秒时间戳 + uuid)
 *       token      = md5(t + captchaId + type + captchaKey) + ":" + (t + 300000)
 *  3. `get/verification/image` → 背景图 + 滑块图 + 新的 token
 *  4. 本地算位移：滑块图是「纯黑底 + 拼图」，取非黑包围盒当模板，
 *     在背景图上按尺度拟合最小残差搜水平位置
 *  5. `check/verification/result` → extraData.validate
 *
 * 其中请求要带 `Referer: <preSign 页地址>`，服务端会据此判断场景。
 */
object CxCaptcha {

    /** 签到 / 签退场景 */
    const val CAPTCHA_ID_SIGN = "Qt9FIw9o4pwRjOyqM6yizZBh682qN2TU"

    /** 登录场景（点选，暂未自动解） */
    const val CAPTCHA_ID_LOGIN = "GcXX5vewqE7DezKGlyvleKCnkTglvGpL"

    private const val URL_CONF = "https://captcha.chaoxing.com/captcha/get/conf"
    private const val URL_IMAGE = "https://captcha.chaoxing.com/captcha/get/verification/image"
    private const val URL_CHECK = "https://captcha.chaoxing.com/captcha/check/verification/result"
    private const val VERSION = "1.1.20"
    private const val TYPE_SLIDE = "slide"

    data class Result(val validate: String?, val error: String?) {
        val ok: Boolean get() = !validate.isNullOrBlank()
    }

    /**
     * 自动解一次滑块。带重试，因为偶尔会算偏。
     *
     * @param referer 预签到页地址，服务端用它判断验证场景
     */
    suspend fun solve(
        client: OkHttpClient,
        captchaId: String = CAPTCHA_ID_SIGN,
        referer: String,
        type: String = TYPE_SLIDE,
        tries: Int = 5
    ): Result = withContext(Dispatchers.IO) {
        var lastErr = "未知错误"
        repeat(tries) { attempt ->
            val r = runCatching { attemptOnce(client, captchaId, referer, type) }
                .getOrElse { return@withContext Result(null, it.message ?: "网络异常") }
            if (r.ok) return@withContext r
            lastErr = r.error ?: "第 ${attempt + 1} 次未通过"
        }
        Result(null, "重试 $tries 次仍未通过：$lastErr")
    }

    private fun attemptOnce(
        client: OkHttpClient,
        captchaId: String,
        referer: String,
        type: String
    ): Result {
        val hdrs = mapOf("Referer" to referer)

        // 1. 拿 t
        val confBody = client.get("$URL_CONF?callback=cx_captcha_function&captchaId=$captchaId&_=${now()}", hdrs).body
        val t = jsonp(confBody)?.optLong("t") ?: return Result(null, "get/conf 异常")

        // 2. 算签名
        val captchaKey = md5("$t${UUID.randomUUID()}")
        val iv = md5("$captchaId$type${now()}${UUID.randomUUID()}")
        val token = md5("$t$captchaId$type$captchaKey") + ":" + (t + 300_000L)

        // 3. 取图
        val imgUrl = "$URL_IMAGE?callback=cx_captcha_function&captchaId=$captchaId&type=$type" +
            "&version=$VERSION&captchaKey=$captchaKey&token=${enc(token)}" +
            "&referer=${enc(referer)}&iv=$iv&_=${now()}"
        val img = jsonp(client.get(imgUrl, hdrs).body) ?: return Result(null, "取图接口返回异常")
        val vo = img.optJSONObject("imageVerificationVo") ?: return Result(null, "取图响应无 imageVerificationVo")
        val shadeUrl = vo.optString("shadeImage")
        val cutoutUrl = vo.optString("cutoutImage")
        val innerToken = img.optString("token")
        if (shadeUrl.isBlank() || cutoutUrl.isBlank() || innerToken.isBlank()) {
            return Result(null, "图片或 token 缺失")
        }

        // 4. 下载图片并算位移
        val bg = client.get(shadeUrl, hdrs).let { respBytes(it) }
            ?: return Result(null, "背景图下载失败")
        val piece = client.get(cutoutUrl, hdrs).let { respBytes(it) }
            ?: return Result(null, "滑块图下载失败")
        val drag = runCatching { computeDrag(bg, piece) }.getOrNull()
            ?: return Result(null, "位移计算失败")

        // 5. 提交校验
        val checkUrl = "$URL_CHECK?callback=cx_captcha_function&captchaId=$captchaId&type=$type" +
            "&token=${enc(innerToken)}&textClickArr=${enc("""[{"x":$drag}]""")}" +
            "&coordinate=${enc("[]")}&runEnv=10&version=$VERSION&t=a&iv=$iv&_=${now()}"
        val out = jsonp(client.get(checkUrl, hdrs).body) ?: return Result(null, "校验接口返回异常")

        if (out.optInt("error", 0) == 1) return Result(null, out.optString("msg").ifBlank { "校验被拒" })
        if (!out.optBoolean("result", false)) return Result(null, "拼图位置不对")

        val extra = out.optString("extraData")
        val validate = runCatching {
            JSONObject(extra.replace("\\\"", "\"")).optString("validate")
        }.getOrNull().orEmpty()
        return if (validate.isBlank()) Result(null, "响应里没有 validate") else Result(validate, null)
    }

    // ------------------------------------------------------------ 位移计算

    /**
     * 求水平拖动距离。
     *
     * 滑块图是「纯黑底 + 一块拼图」，所以非黑区域的包围盒就是拼图本体；
     * 拼图在滑块图里的纵向位置就是它在背景图里的纵向位置，只需搜横向。
     * 每个候选 x 用「尺度拟合最小残差」打分：允许整体缩放 k，取残差最小者。
     * 不要用「最暗区域」定位——背景照片的暗部会给出完全错误的答案。
     */
    internal fun computeDrag(bgBytes: ByteArray, pieceBytes: ByteArray): Int? {
        val bg = BitmapFactory.decodeByteArray(bgBytes, 0, bgBytes.size) ?: return null
        val pc = BitmapFactory.decodeByteArray(pieceBytes, 0, pieceBytes.size) ?: return null
        val bw = bg.width
        val bh = bg.height
        val pw = pc.width
        val ph = pc.height
        if (bw < 20 || bh < 20 || pw < 10 || ph < 10) return null

        val bpx = IntArray(bw * bh).also { bg.getPixels(it, 0, bw, 0, 0, bw, bh) }
        val ppx = IntArray(pw * ph).also { pc.getPixels(it, 0, pw, 0, 0, pw, ph) }

        // 非黑包围盒
        var x0 = pw; var x1 = -1; var y0 = ph; var y1 = -1
        for (y in 0 until ph) {
            val row = y * pw
            for (x in 0 until pw) {
                val c = ppx[row + x]
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
        val tw = x1 - x0 + 1
        val th = y1 - y0 + 1
        if (tw < 10 || th < 10 || y0 + th > bh || tw >= bw) return null

        var bestX = -1
        var bestRes = Double.MAX_VALUE
        for (x in 0..(bw - tw)) {
            var dot = 0.0; var pp = 1e-6; var rr = 1e-9
            for (yy in 0 until th) {
                val bRow = (y0 + yy) * bw + x
                val pRow = (y0 + yy) * pw + x0
                for (xx in 0 until tw) {
                    val bc = bpx[bRow + xx]
                    val cv = ppx[pRow + xx]
                    val br = ((bc shr 16) and 0xFF).toDouble()
                    val bgc = ((bc shr 8) and 0xFF).toDouble()
                    val bb = (bc and 0xFF).toDouble()
                    val pr = ((cv shr 16) and 0xFF).toDouble()
                    val pg = ((cv shr 8) and 0xFF).toDouble()
                    val pb = (cv and 0xFF).toDouble()
                    dot += br * pr + bgc * pg + bb * pb
                    pp += pr * pr + pg * pg + pb * pb
                    rr += br * br + bgc * bgc + bb * bb
                }
            }
            val k = dot / pp
            if (k <= 0.2) continue
            // ||reg - k*piece|| / ||reg||
            val res = sqrt(max(0.0, rr - 2 * k * dot + k * k * pp)) / (sqrt(rr) + 1e-6)
            if (res < bestRes) {
                bestRes = res
                bestX = x
            }
        }
        if (bestX < 0) return null
        // 拼图左边缘原本在滑块图里的位置是 x0，拖到 bestX 即拖动 (bestX - x0)
        return (bestX - x0).coerceAtLeast(0)
    }

    // -------------------------------------------------------------- 工具

    private fun respBytes(resp: Resp): ByteArray? =
        runCatching { resp.rawBytes }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun now() = System.currentTimeMillis()

    /** 去掉 JSONP 外壳 */
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
