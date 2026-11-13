package com.xxt.sign.ui

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 自定义扫码 Activity：**解决「画面看得见、二维码就是扫不上」**。
 *
 * ## 为什么要自己写一个
 *
 * `zxing-android-embedded` 的默认 [CaptureActivity] 对「举着手机扫远处大屏」很不友好：
 *
 * 1. **默认是单次对焦（AUTO），不是连续对焦。** 举着手机对准几米外的大屏时，
 *    单次对焦很容易停在近处，画面看着"有画面"其实是虚的，二维码边缘全糊。
 *    这是最主要的原因。
 * 2. **没有开条码场景模式和测光。** 教室大屏常反光/偏暗，对比度不够，二值化直接失败。
 * 3. **没有缩放。** 距离远时二维码只占画面很小一块，模块（黑白小格）不足 1 像素，
 *    ZXing 无论怎么 `TRY_HARDER` 都解不出来。相册图片能成功正是因为它分辨率够。
 *
 * ## 这个实现做了什么
 *
 * - **双指捏合缩放**（1x–5x），放大后等于提升二维码的有效分辨率
 * - **手电筒开关**（右上角按钮），暗光/反光时补光
 * - 连续对焦 + 条码场景模式 + 中心测光
 * - 锁竖屏，避免传感器方向重算导致预览与解码区域错位
 */
class QrCaptureActivity : CaptureActivity() {

    private var decorated: DecoratedBarcodeView? = null
    private var scaleDetector: ScaleGestureDetector? = null
    private var torchButton: TextView? = null
    private var zoomLabel: TextView? = null

    /** 当前缩放（相对 1x 的倍数，1.0–5.0） */
    private var zoom = 1.0f
    private var torchOn = false

    private val minZoom = 1.0f
    private val maxZoom = 5.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }

    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        decorated = view
        applyCameraTuning(view)
        addOverlayControls(view)
        setupPinchZoom(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        // 相机在 onResume 才真正建立，这里再设一次确保生效
        decorated?.let { applyCameraTuning(it) }
    }

    /** 把相机配置调到「扫远处大屏」最友好的状态 */
    private fun applyCameraTuning(view: DecoratedBarcodeView) {
        runCatching {
            val settings = view.barcodeView.cameraSettings
            settings.isContinuousFocusEnabled = true
            settings.isAutoFocusEnabled = true
            settings.isBarcodeSceneModeEnabled = true
            settings.isMeteringEnabled = true
        }
    }

    // ------------------------------------------------------------ 缩放

    /**
     * 双指捏合缩放。
     *
     * 用 `changeCameraParameters` 改硬件缩放，而不是把预览画面做视觉放大 ——
     * 后者只是把已有的像素拉大，对解码**毫无帮助**；硬件缩放是真正让相机
     * 以更高倍率采样，二维码的模块数实打实变多，这才是能扫上的关键。
     */
    private fun setupPinchZoom(view: DecoratedBarcodeView) {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    val next = (zoom * factor).coerceIn(minZoom, maxZoom)
                    if (kotlin.math.abs(next - zoom) > 0.01f) {
                        zoom = next
                        applyZoom(view)
                        updateZoomLabel()
                    }
                    return true
                }
            }
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    /** 把当前 [zoom] 应用到相机硬件 */
    private fun applyZoom(view: DecoratedBarcodeView) {
        runCatching {
            view.barcodeView.changeCameraParameters { params ->
                try {
                    if (params.isZoomSupported) {
                        val max = params.maxZoom
                        val ratios = params.zoomRatios
                        if (max > 0 && ratios != null && ratios.size == max + 1) {
                            // zoomRatios 是「相对 1x 的百分比」，例如 100 = 1x、300 = 3x
                            val target = (zoom * 100).toInt()
                            var best = 0
                            var bestDiff = Int.MAX_VALUE
                            for (i in 0..max) {
                                val d = kotlin.math.abs(ratios[i] - target)
                                if (d < bestDiff) {
                                    bestDiff = d
                                    best = i
                                }
                            }
                            params.zoom = best
                        }
                    }
                } catch (_: Throwable) {
                }
                params
            }
        }
    }

    // ------------------------------------------------------------ 手电筒

    private fun toggleTorch(view: DecoratedBarcodeView) {
        torchOn = !torchOn
        runCatching { view.barcodeView.setTorch(torchOn) }
        torchButton?.text = if (torchOn) "🔦 开" else "🔦 关"
        torchButton?.setTextColor(if (torchOn) Color.parseColor("#FFD54F") else Color.WHITE)
    }

    // ------------------------------------------------------------ 界面覆盖层

    /** 在扫码页上叠一层：右上角手电筒按钮 + 底部缩放提示 */
    private fun addOverlayControls(view: DecoratedBarcodeView) {
        val root = view.parent as? ViewGroup ?: return

        val overlay = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
        }

        // 手电筒（右上角）
        val torch = TextView(this).apply {
            text = "🔦 关"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isClickable = true
            setOnClickListener { decorated?.let { toggleTorch(it) } }
        }
        torqueLayout(torch, Gravity.TOP or Gravity.END, dp(16), dp(48))
        overlay.addView(torch)
        torchButton = torch

        // 缩放倍数（底部中间，只在放大后显示）
        val label = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        torqueLayout(label, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(72))
        overlay.addView(label)
        zoomLabel = label

        root.addView(overlay)
    }

    private fun torqueLayout(v: TextView, gravity: Int, marginH: Int, marginV: Int) {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = gravity
        lp.setMargins(marginH, marginV, marginH, marginV)
        v.layoutParams = lp
    }

    private fun updateZoomLabel() {
        val label = zoomLabel ?: return
        if (zoom <= 1.05f) {
            label.text = ""
            label.visibility = android.view.View.GONE
        } else {
            label.visibility = android.view.View.VISIBLE
            label.text = "%.1fx（双指捏合缩放）".format(zoom)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
