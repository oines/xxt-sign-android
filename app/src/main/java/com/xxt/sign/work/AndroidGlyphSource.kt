package com.xxt.sign.work

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.xxt.sign.core.LogStore
import java.io.File

/**
 * Android 上的字形渲染：把字画到 128×128 的 ALPHA_8 位图，按墨迹裁边后缩放到 [size]×[size] 灰度特征。
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

        // ① assets/fonts 优先。
        //
        // 实测（2026-11-11，手机里 111 份缓存密文字体全量解析）：卷面的密文字体
        // **全部是「思源黑体 CN Normal / Source Han Sans CN Normal」的子集**
        // （name 表都没清，每份只有 7–49 个字形）。同源同字重渲染出来的字形几乎完全重合，
        // 逐字比对基本不会认错，所以它必须排在最前面，不能被 MAX_REFERENCES 挤掉。
        //
        // **不要用 `Typeface.createFromAsset`**：真机上它对这种子集化的 TTF 会静默失败，
        // 返回一个「能创建但画出来是系统默认字体」的 Typeface，而且不抛异常。
        // 表现是「assets 字体和系统黑体的中位相似度几乎相同（0.881 / 0.880）」——
        // 因为压根就是同一套字体在画。改成先落到私有目录、再 `createFromFile`，
        // 这条路径和密文字体完全一致，已验证可靠。
        runCatching {
            val dir = File(context.cacheDir, "reffonts").apply { mkdirs() }
            context.assets.list("fonts").orEmpty()
                .filter { it.endsWith(".ttf", true) || it.endsWith(".otf", true) }
                .sorted()
                .forEach { name ->
                    val tf = runCatching {
                        val f = File(dir, name)
                        if (!f.exists() || f.length() == 0L) {
                            context.assets.open("fonts/$name").use { input ->
                                f.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        Typeface.createFromFile(f)
                    }.getOrNull()
                    add(name.substringBeforeLast('.'), tf)
                }
        }

        // ② 系统字体兜底：assets 没放字体、或者密文字体不是思源黑体时，回退到老的多字体比对
        add("系统黑体", Typeface.SANS_SERIF)
        add("系统宋体", Typeface.SERIF)
        add("系统等宽", Typeface.MONOSPACE)
        add("系统默认", Typeface.DEFAULT)
        runCatching {
            add("黑体-中粗", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
            add("宋体-中粗", Typeface.create(Typeface.SERIF, Typeface.BOLD))
        }

        // ③ /system/fonts 里的中文字体文件（NotoSansCJK-Regular.ttc / NotoSerifCJK-Bold.ttc / …）
        CJK_DIRS.forEach { dir ->
            val files = runCatching {
                File(dir).listFiles { f -> f.isFile && isCjkFontName(f.name) }
            }.getOrNull().orEmpty().sortedBy { it.name }
            files.forEach { f ->
                // 名字过滤只是粗筛，必须再验一次「这个字体文件自己真的有中文字形」。
                // 踩过的坑：`isCjkFontName` 里的 "notosans" 会把 NotoSansAhom / NotoSansBalinese
                // 这类非中文字体也放进来。Android 的字体回退会替它们画豆腐块，于是它们在
                // 逐字比对里「看起来也像中文」，还抢走了十几个字的最优匹配，
                // 把整卷相似度从 0.99 拖到 0.92、虚报出一堆「存疑」。
                if (!FontCoverage.hasChinese(f)) {
                    LogStore.add("[字体] 跳过 ${f.name}：文件内没有中文字形（避免回退豆腐块污染比对）", true)
                    return@forEach
                }
                val tf = runCatching { Typeface.createFromFile(f) }.getOrNull()
                add(f.name.substringBeforeLast('.'), tf)
            }
        }

        // 去重 + 体检 + 封顶：很多机型 SANS_SERIF/SERIF/DEFAULT/MONOSPACE 指向同一个
        // Noto Sans CJK，不去重就等于把 3755 个候选字渲染十几遍，真机上会卡成幻灯片
        val kept = mutableListOf<Pair<String, List<FloatArray>>>()
        candidates.forEach { (name, tf) ->
            if (kept.size >= MAX_REFERENCES) return@forEach
            val sig = signature(tf) ?: return@forEach
            if (kept.any { sameFont(it.second, sig) }) {
                // 和已入选的某套字体「几乎完全相同」。
                //
                // 这有两种含义：①确实是同一套字体的不同名字（正常，跳过）；
                // ②**加载失败退化成默认字体**（必须暴露出来，否则排查时会以为资产生效了）。
                LogStore.add("[字体] $name 与已入选字体指纹一致，跳过（若这是 assets 字体，说明没加载成功）", true)
                return@forEach
            }
            kept.add(name to sig)
        }

        val list = kept.map { (name, _) ->
            val tf = candidates[name]!!
            ReferenceFont(name) { c, s -> GlyphRaster.render(tf, c, s) }
        }
        // 真机排障：把最终参与比对的字体和各自的中文字形覆盖率打出来。
        // 判断「assets 是否真的生效」「系统字体是否重复」全靠这行。
        runCatching {
            LogStore.add(
                "[字体] 参考字体 ${list.size} 套：" + list.joinToString("、") { it.name },
                true
            )
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
        // 先排除明显不是中文的 Noto 家族（Ahom / Balinese / Arabic / Hebrew … 都是 notosans*）
        val excluded = listOf(
            "emoji", "symbols", "math", "mono", "arabic", "hebrew", "devanagari",
            "bengali", "tamil", "telugu", "thai", "lao", "khmer", "myanmar",
            "ahom", "balinese", "javanese", "sundanese", "tagalog", "buginese"
        )
        if (excluded.any { n.contains(it) }) return false
        return listOf(
            "cjk", "notosans", "notoserif", "sourcehan", "droid",
            "misansw", "wqy", "droidsansfallback", "hans", "sc-"
        ).any { n.contains(it) }
    }
}

/**
 * 判断一个字体文件**自己**是否真的包含中文字形。
 *
 * 只看 cmap：字号表里有 '中'(U+4E2D) / '国'(U+56FD) / '的'(U+7684) / '一'(U+4E00) 这四个码位，
 * 并且映射到的字形不全是同一个（全同一个 = 都是 .notdef 或豆腐块）。
 *
 * 为什么要自己解析而不是用 Typeface：Android 对任何字体都会做**回退**，
 * `Typeface.createFromFile(NotoSansAhom.ttf)` 画 '中' 也能画出一个（从别的字体借来的）字，
 * 光看渲染结果分不出「这字体自己有没有中文」。
 */
internal object FontCoverage {

    private val PROBES = intArrayOf(0x4E2D, 0x56FD, 0x7684, 0x4E00)

    fun hasChinese(file: File): Boolean = runCatching {
        val bytes = file.readBytes()
        val cmap = CmapParser.parse(bytes) ?: return false
        val gids = PROBES.map { cmap[it] ?: return false }
        // 四个码位必须映射到不同字形；全相同说明是同一个缺省字形
        gids.toSet().size >= 3
    }.getOrDefault(false)
}

/**
 * 极简 TTF/OTF cmap 解析，只为「判断某字体自己有没有某几个码位」。
 *
 * 支持 format 4（BMP，最常用）和 format 12（UCS-4）；TTC 取第一个字体的表目录。
 * 只读、无第三方依赖，出错一律返回 null（当作没有）。
 */
internal object CmapParser {

    fun parse(data: ByteArray): Map<Int, Int>? = runCatching {
        var base = 0
        val sfnt = u32(data, 0)
        if (sfnt == 0x74746366L) { // 'ttcf'
            // TTC 头：tag(4) version(4) numFonts(4，**32 位！**) offsetTable[numFonts]
            //
            // 真机踩过：按 16 位读 numFonts 会读成 0，于是把 NotoSansCJK-Regular.ttc
            // （思源黑体本体！）误判成「文件内没有中文字形」直接跳过，
            // 参考池里只剩小米默认字体和宋体，整卷相似度掉到 0.88。
            val numFonts = u32(data, 8)
            if (numFonts <= 0L) return null
            // 逐个字体试，取第一个能解析出中文的（.ttc 里通常 N 个同源字重）
            for (i in 0 until numFonts.toInt().coerceAtMost(32)) {
                val off = u32(data, 12 + i * 4).toInt()
                val m = parseSfnt(data, off)
                if (m != null && m.containsKey(0x4E2D)) return m
            }
            return null
        }
        parseSfnt(data, base)
    }.getOrNull()

    /** 解析单个 sfnt（非 TTC）的 cmap */
    private fun parseSfnt(data: ByteArray, base: Int): Map<Int, Int>? {
        val numTables = u16(data, base + 4)
        var cmapOff = -1
        for (i in 0 until numTables) {
            val rec = base + 12 + i * 16
            if (rec + 16 > data.size) return null
            val tag = String(data, rec, 4, Charsets.US_ASCII)
            if (tag == "cmap") {
                cmapOff = u32(data, rec + 8).toInt()
                break
            }
        }
        if (cmapOff < 0) return null

        val nSub = u16(data, cmapOff + 2)
        var best = -1
        var bestScore = -1
        for (i in 0 until nSub) {
            val rec = cmapOff + 4 + i * 8
            val pid = u16(data, rec)
            val eid = u16(data, rec + 2)
            val sub = cmapOff + u32(data, rec + 4).toInt()
            if (sub + 2 > data.size) continue
            val fmt = u16(data, sub)
            if (fmt != 4 && fmt != 12) continue
            // 优先 Windows Unicode 全字库（3/10），其次 3/1，再次 0/x
            val score = when {
                fmt == 12 && pid == 3 && eid == 10 -> 100
                fmt == 4 && pid == 3 && eid == 1 -> 80
                fmt == 12 && pid == 0 -> 70
                fmt == 4 && pid == 0 -> 60
                fmt == 12 -> 50
                else -> 40
            }
            if (score > bestScore) {
                bestScore = score
                best = sub
            }
        }
        if (best < 0) return null
        return if (u16(data, best) == 12) parseFormat12(data, best) else parseFormat4(data, best)
    }

    private fun parseFormat4(d: ByteArray, off: Int): Map<Int, Int> {
        val out = HashMap<Int, Int>(512)
        val segX2 = u16(d, off + 6)
        val endO = off + 14
        val startO = off + 16 + segX2
        val deltaO = off + 16 + 2 * segX2
        val rangeO = off + 16 + 3 * segX2
        for (s in 0 until segX2 / 2) {
            val end = u16(d, endO + s * 2)
            val start = u16(d, startO + s * 2)
            val delta = i16(d, deltaO + s * 2)
            val ro = u16(d, rangeO + s * 2)
            if (start == 0xFFFF) continue
            for (c in start..end) {
                val g = if (ro == 0) {
                    (c + delta) and 0xFFFF
                } else {
                    val gi = rangeO + s * 2 + ro + (c - start) * 2
                    if (gi + 1 >= d.size) 0 else {
                        val raw = u16(d, gi)
                        if (raw != 0) (raw + delta) and 0xFFFF else 0
                    }
                }
                if (g != 0) out[c] = g
            }
        }
        return out
    }

    private fun parseFormat12(d: ByteArray, off: Int): Map<Int, Int> {
        val out = HashMap<Int, Int>(512)
        val n = u32(d, off + 12).toInt()
        for (i in 0 until n) {
            val p = off + 16 + i * 12
            val sc = u32(d, p).toInt()
            val ec = u32(d, p + 4).toInt()
            val sg = u32(d, p + 8).toInt()
            for (c in sc..ec) out[c] = sg + (c - sc)
        }
        return out
    }

    private fun u16(d: ByteArray, o: Int): Int =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun i16(d: ByteArray, o: Int): Int {
        val v = u16(d, o)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or
            ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or
            (d[o + 3].toLong() and 0xFF)
}

/**
 * 单字形光栅化 + 特征提取。
 *
 * **必须按墨迹裁边再缩放**（2026-11-11 用 111 份真实密文字体验证）：
 * 画布是 64×64，字只占中间一小块，剩下的全是空白边距。原来直接把整块画布盒式降采样到
 * 16×16，边距里的抗锯齿噪声和字形笔画混在一个 cell 里，导致同源字体（思源黑体 CN Normal）
 * 也只能跑到平均 0.85、最小间距 0.0097 —— 大量字「几乎分不开」，于是出现
 * 「吉年夹，中圆兵户党」这种部分字错。
 *
 * 改成「先算墨迹包围盒 → 裁掉边距 → 留 2% 余量 → 缩放到 size×size」后，同一批数据：
 *
 * | 配置                   | 最低相似度 | 平均   | 最小间距(中位) |
 * |------------------------|-----------|--------|---------------|
 * | 64→16 不裁边（原实现）  | 0.767     | 0.846  | 0.0097        |
 * | 64→16 裁边             | 0.949     | 0.982  | 0.1061        |
 * | 128→16 裁边（现实现）   | 0.944     | 0.996  | 0.1296        |
 *
 * 间距中位数提升 13 倍，这是识别正确率提升的主要来源。渲染分辨率同时从 64 提到 128，
 * 让缩小时的抗锯齿质量更好（对准确率有额外收益，开销可忽略：一格 128×128 位图）。
 */
object GlyphRaster {

    /**
     * 基准字号（像素）。
     *
     * 画布尺寸不再固定，而是 `基准字号 × 行高 × 1.12`，见 [render] 里的说明。
     * 这个值本身决定字形细节量：128 是在真机上对比过 64/96/128 后选的。
     */
    private const val BASE_TEXT_SIZE = 128f

    fun render(tf: Typeface, ch: Char, size: Int): FloatArray? {
        // 画布边长按字体的实际行高算，保证字形**完整**落在画布内。
        //
        // 真机踩过的大坑：原来固定 128×128 + textSize=128*0.82=105px，
        // 而思源黑体的 ascender=1.16em、descender=0.29em（总高 1.45em），
        // 字形顶部落在 y = -12px —— **上半截被画布裁掉了**。
        // 密文字体和参考字体被裁掉的量一样，但裁切会让笔画粘连、抗锯齿信息丢失，
        // 整卷相似度从 0.99 掉到 0.88，还制造出一堆「分不开」的假歧义。
        //
        // 正确做法：画布 = textSize × 行高 × 安全系数，让 baseline 落在画布内部。
        val paint0 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            textSize = BASE_TEXT_SIZE
        }
        val fm0 = paint0.fontMetrics
        val lineHeight = (fm0.descent - fm0.ascent).coerceAtLeast(BASE_TEXT_SIZE)
        // 留 12% 余量（字形上下的 accent / 下伸部都放得下）
        val px = (lineHeight * 1.12f).toInt().coerceAtLeast(BASE_TEXT_SIZE.toInt())
        val textSize = BASE_TEXT_SIZE

        // 用 ARGB_8888 而不是 ALPHA_8。
        //
        // ALPHA_8 只有 alpha 通道，`getPixels()` 会把 alpha 放进 ARGB 的 alpha 位、
        // rgb 全 0；而 `drawText` 在 ALPHA_8 上的抗锯齿质量在不同 ROM / Skia 版本上
        // 不一致（有的直接把覆盖率丢掉）。ARGB_8888 是唯一行为完全可控的配置：
        // 黑字画在透明底上，取 alpha 通道就是纯覆盖率。
        val bmp = runCatching { Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return null
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf
                this.textSize = textSize
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            // 垂直居中：让 (ascent..descent) 这段居中
            val baseline = px / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(ch.toString(), px / 2f, baseline, paint)

            val pixels = IntArray(px * px)
            bmp.getPixels(pixels, 0, px, 0, 0, px, px)

            // 1) 找墨迹包围盒（alpha > 0），顺带判断是不是空字形
            var minX = px
            var minY = px
            var maxX = -1
            var maxY = -1
            for (y in 0 until px) {
                val row = y * px
                for (x in 0 until px) {
                    if (((pixels[row + x] ushr 24) and 0xFF) != 0) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) return null

            // 2) 留 2% 余量，别让笔画贴边（贴边会把笔画压扁）
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val pad = kotlin.math.max(1, (kotlin.math.max(w, h) * 0.02f).toInt())
            val left = (minX - pad).coerceAtLeast(0)
            val top = (minY - pad).coerceAtLeast(0)
            val right = (maxX + pad + 1).coerceAtMost(px)
            val bottom = (maxY + pad + 1).coerceAtMost(px)
            val cropW = right - left
            val cropH = bottom - top

            // 3) 面积平均缩放到 size×size（比盒式采样更准，且天然抗锯齿）
            val out = FloatArray(size * size)
            var sum = 0f
            for (ty in 0 until size) {
                val sy0 = top + ty * cropH / size
                val sy1 = kotlin.math.max(sy0 + 1, top + (ty + 1) * cropH / size)
                for (tx in 0 until size) {
                    val sx0 = left + tx * cropW / size
                    val sx1 = kotlin.math.max(sx0 + 1, left + (tx + 1) * cropW / size)
                    var acc = 0f
                    var cnt = 0
                    for (sy in sy0 until sy1) {
                        val row = sy * px
                        for (sx in sx0 until sx1) {
                            acc += ((pixels[row + sx] ushr 24) and 0xFF).toFloat()
                            cnt++
                        }
                    }
                    val v = if (cnt > 0) acc / cnt / 255f else 0f
                    out[ty * size + tx] = v
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
