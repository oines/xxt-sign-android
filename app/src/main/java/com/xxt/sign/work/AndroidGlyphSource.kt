package com.xxt.sign.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import java.io.File

/**
 * Android 上的字形渲染：把字画到 64×64 的 ALPHA_8 位图，降采样成 [size]×[size] 灰度特征。
 * 这层是唯一依赖 Android 的部分，[FontDecoder] 本身是纯 JVM 的。
 */
class AndroidGlyphSource(
    context: Context,
    fontBytes: ByteArray,
    private val size: Int = 16
) : GlyphSource {

    private val secret: Typeface? = runCatching {
        val dir = File(context.cacheDir, "quizfont").apply { mkdirs() }
        val f = File(dir, "secret-${fontBytes.contentHashCode()}-${fontBytes.size}.ttf")
        if (!f.exists() || f.length() != fontBytes.size.toLong()) f.writeBytes(fontBytes)
        Typeface.createFromFile(f)
    }.getOrNull()

    override fun renderSecret(ch: Char, size: Int): FloatArray? =
        secret?.let { GlyphRaster.render(it, ch, size) }
}

/**
 * 参考字体集合。
 *
 * 真机踩过的坑：只放「系统黑体 + 系统宋体」时，有的卷子两套都不是页面用的那套，
 * 整卷相似度只有 0.86，解出来是「吉年夹，中圆兵户党」——**部分字错**。
 * 所以这里把设备上能拿到的中文字体全枚举出来：
 *
 *  1. Typeface 家族：黑体 / 宋体 / 等宽 / 默认
 *  2. `/system/fonts` 里名字带 CJK / NotoSans / NotoSerif / DroidSansFallback / SourceHan 的字体文件
 *  3. `assets/fonts/` 里自己放的 ttf/otf
 *
 * 每套都先做「能不能画中文」体检（'中' 和 '国' 都得画出不同形状），
 * 画不出来的（缺字变成豆腐块）直接踢掉，免得它污染相似度。
 */
object AndroidReferenceFonts {

    @Volatile
    private var cached: List<ReferenceFont>? = null

    fun list(context: Context): List<ReferenceFont> {
        cached?.let { return it }
        val fonts = LinkedHashMap<String, ReferenceFont>()

        fun add(name: String, tf: Typeface?) {
            if (tf == null) return
            if (fonts.containsKey(name)) return
            if (!drawsChinese(tf)) return
            fonts[name] = ReferenceFont(name) { c, s -> GlyphRaster.render(tf, c, s) }
        }

        add("系统黑体", Typeface.SANS_SERIF)
        add("系统宋体", Typeface.SERIF)
        add("系统等宽", Typeface.MONOSPACE)
        add("系统默认", Typeface.DEFAULT)
        runCatching {
            add("黑体-中粗", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
            add("宋体-中粗", Typeface.create(Typeface.SERIF, Typeface.BOLD))
        }

        // /system/fonts 里的中文字体文件（NotoSansCJK-Regular.ttc / NotoSerifCJK-Bold.ttc / …）
        CJK_DIRS.forEach { dir ->
            val files = runCatching {
                File(dir).listFiles { f -> f.isFile && isCjkFontName(f.name) }
            }.getOrNull().orEmpty().sortedBy { it.name }
            files.forEach { f ->
                val tf = runCatching { Typeface.createFromFile(f) }.getOrNull()
                add(f.name.substringBeforeLast('.'), tf)
            }
        }

        // assets/fonts：用户自己丢进去的字体，优先级也当参考
        runCatching {
            context.assets.list("fonts").orEmpty()
                .filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
                .forEach { name ->
                    val tf = runCatching {
                        Typeface.createFromAsset(context.assets, "fonts/$name")
                    }.getOrNull()
                    add(name.substringBeforeLast('.'), tf)
                }
        }

        val list = fonts.values.toList()
        cached = list
        return list
    }

    /** 清缓存（设置里换字体后重新枚举用） */
    fun invalidate() {
        cached = null
    }

    private val CJK_DIRS = listOf("/system/fonts", "/system/font", "/product/fonts", "/vendor/fonts")

    /** 只挑可能含中文的字体文件，别把整个字体目录（几十套）都加载进来 */
    fun isCjkFontName(name: String): Boolean {
        val n = name.lowercase()
        if (!n.endsWith(".ttf") && !n.endsWith(".otf") && !n.endsWith(".ttc")) return false
        return listOf(
            "cjk", "notosans", "notoserif", "sourcehan", "droid",
            "misansw", "wqy", "droidsansfallback", "hans", "sc-"
        ).any { n.contains(it) }
    }

    /**
     * 这套字体能不能画中文：'中' 和 '国' 都要有像素，且不能长得一模一样
     * （缺字时全是同一个豆腐块 → 相似度 1.0，会把所有参考都带偏）。
     */
    private fun drawsChinese(tf: Typeface): Boolean {
        val a = runCatching { GlyphRaster.render(tf, '中', 16) }.getOrNull() ?: return false
        val b = runCatching { GlyphRaster.render(tf, '国', 16) }.getOrNull() ?: return false
        return FontDecoder.cosine(a, b) < 0.97f
    }
}

/** 单字形光栅化 + 16×16 特征提取 */
object GlyphRaster {

    fun render(tf: Typeface, ch: Char, size: Int): FloatArray? {
        val px = 64
        val bmp = runCatching { Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8) }.getOrNull()
            ?: return null
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                textSize = px * 0.82f
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            val baseline = px / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(ch.toString(), px / 2f, baseline, paint)

            val pixels = IntArray(px * px)
            bmp.getPixels(pixels, 0, px, 0, 0, px, px)
            if (pixels.all { (it ushr 24) == 0 }) return null

            val cell = px / size
            val out = FloatArray(size * size)
            var sum = 0f
            for (y in 0 until size) {
                for (x in 0 until size) {
                    var acc = 0f
                    for (dy in 0 until cell) {
                        val row = (y * cell + dy) * px
                        for (dx in 0 until cell) {
                            acc += ((pixels[row + x * cell + dx] ushr 24) and 0xFF).toFloat()
                        }
                    }
                    val v = acc / (cell * cell) / 255f
                    out[y * size + x] = v
                    sum += v * v
                }
            }
            val norm = kotlin.math.sqrt(sum)
            if (norm <= 1e-6f) return null
            for (i in out.indices) out[i] /= norm
            return out
        } finally {
            bmp.recycle()
        }
    }
}

/** 把 base64 的 TTF 解出来；解不出来就返回 null（当作明文卷子处理） */
object FontBytes {
    fun decode(base64: String): ByteArray? = runCatching {
        android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.size > 100 }
}
