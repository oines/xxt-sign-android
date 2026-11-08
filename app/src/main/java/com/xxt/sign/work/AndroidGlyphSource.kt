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
 * Android 上的字形渲染：把字画到 64×64 的 ALPHA_8 位图上，降采样成 [size]×[size] 灰度特征。
 *
 * 密文字体是页面里内嵌的 TTF（base64），落到 cache 目录再交给 Typeface；
 * 参考字体用系统自带的（Android 中文默认就是思源黑体 Noto Sans CJK）。
 *
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

    private val reference: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    override fun renderSecret(ch: Char, size: Int): FloatArray? =
        secret?.let { render(it, ch, size) }

    override fun renderReference(ch: Char, size: Int): FloatArray? =
        render(reference, ch, size)

    private fun render(tf: Typeface, ch: Char, size: Int): FloatArray? {
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
            // L2 归一化，后面直接点积就是余弦
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
