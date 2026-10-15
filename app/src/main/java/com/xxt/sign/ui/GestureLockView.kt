package com.xxt.sign.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 3×3 手势点阵绘制控件。
 *
 * 学习通手势签到的码就是「画过的点按编号拼起来」：
 * 九宫格从左到右、从上到下编号 1-9，画过哪几个点就依次拼成字符串。
 * 例如左上→中上→右上→中中→右下 = `12369`。
 * 空档（跳过的点）不会自动补上，和你实际画过的路径一致。
 */
class GestureLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 手指抬起时回调，参数是拼好的手势码 */
    var onComplete: ((String) -> Unit)? = null

    private val path = mutableListOf<Int>()
    private var fingerX = 0f
    private var fingerY = 0f
    private var fingerDown = false

    private val accent = 0xFF1E6F5C.toInt()
    private val idle = 0xFFBDBDBD.toInt()

    private val dotIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = idle
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = idle
    }
    private val dotSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val ringSel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = accent
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = accent
    }

    /** 当前已画出的手势码 */
    val code: String
        get() = path.joinToString("") { (it + 1).toString() }

    fun reset() {
        path.clear()
        fingerDown = false
        invalidate()
    }

    private fun centers(): List<Pair<Float, Float>> {
        val cellW = width / 3f
        val cellH = height / 3f
        return (0..8).map { i ->
            val row = i / 3
            val col = i % 3
            (cellW * (col + 0.5f)) to (cellH * (row + 0.5f))
        }
    }

    private fun dotRadius() = min(width, height) / 3f * 0.11f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val pts = centers()
        val r = dotRadius()

        // 连线
        if (path.size >= 2) {
            for (i in 0 until path.size - 1) {
                val (x1, y1) = pts[path[i]]
                val (x2, y2) = pts[path[i + 1]]
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }
        }
        // 手指当前拖到尾部的引导线
        if (fingerDown && path.isNotEmpty()) {
            val (x1, y1) = pts[path.last()]
            canvas.drawLine(x1, y1, fingerX, fingerY, linePaint)
        }

        // 点
        pts.forEachIndexed { i, (cx, cy) ->
            val selected = path.contains(i)
            if (selected) {
                canvas.drawCircle(cx, cy, r, dotSel)
                canvas.drawCircle(cx, cy, r * 1.8f, ringSel)
            } else {
                canvas.drawCircle(cx, cy, r, dotIdle)
                canvas.drawCircle(cx, cy, r * 1.8f, ringPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                path.clear()
                fingerDown = true
                fingerX = event.x
                fingerY = event.y
                hitDot(event.x, event.y)?.let { path.add(it) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                fingerX = event.x
                fingerY = event.y
                hitDot(event.x, event.y)?.let { if (!path.contains(it)) path.add(it) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                invalidate()
                val c = code
                if (c.isNotEmpty()) onComplete?.invoke(c)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitDot(x: Float, y: Float): Int? {
        val threshold = min(width, height) / 3f * 0.42f
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        centers().forEachIndexed { i, (cx, cy) ->
            val d = hypot(x - cx, y - cy)
            if (d < threshold && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }
}
