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
        val candidates = LinkedHashMap<String, Typeface>()

        fun add(name: String, tf: Typeface?) {
            if (tf == null) return
            if (candidates.containsKey(name)) return
            candidates[name] = tf
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

        // 去重 + 体检 + 封顶：很多机型 SANS_SERIF/SERIF/DEFAULT/MONOSPACE 指向同一个
        // Noto Sans CJK，不去重就等于把 3755 个候选字渲染十几遍，真机上会卡成幻灯片
        val kept = mutableListOf<Pair<String, List<FloatArray>>>()
        candidates.forEach { (name, tf) ->
            if (kept.size >= MAX_REFERENCES) return@forEach
            val sig = signature(tf) ?: return@forEach
            if (kept.any { sameFont(it.second, sig) }) return@forEach
            kept.add(name to sig)
        }

        val list = kept.map { (name, _) ->
            val tf = candidates[name]!!
            ReferenceFont(name) { c, s -> GlyphRaster.render(tf, c, s) }
        }
        cached = list
        return list
    }

    /** 最多几套参考字体：再多对准确率没帮助，只会把整卷预处理拖慢 */
    private const val MAX_REFERENCES = 6

    private val PROBE_CHARS = charArrayOf('中', '国', '的', '一')

    /** 用几个典型字给字体做指纹；任何一画不出来、或「中/国」长得一模一样（缺字豆腐块）就丢掉 */
    private fun signature(tf: Typeface): List<FloatArray>? {
        val vecs = ArrayList<FloatArray>(PROBE_CHARS.size)
        for (ch in PROBE_CHARS) {
            val v = runCatching { GlyphRaster.render(tf, ch, 16) }.getOrNull() ?: return null
            vecs.add(v)
        }
        if (FontDecoder.cosine(vecs[0], vecs[1]) > 0.97f) return null
        return vecs
    }

    /** 四字指纹都几乎一致 = 同一套字体（同一字体工厂返回的不同名字） */
    private fun sameFont(a: List<FloatArray>, b: List<FloatArray>): Boolean =
        a.size == b.size && a.indices.all { FontDecoder.cosine(a[it], b[it]) > 0.999f }

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
