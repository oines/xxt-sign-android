package com.xxt.sign.net

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * 从相册图片里解二维码。
 *
 * journeyapps 的 ScanContract 只负责相机；相册图片需要自己交给 ZXing 解码。
 * 这里只收裸像素，Bitmap 的读取/旋转放在 UI 层，方便单测。
 */
object QrImageDecoder {
    private val reader = QRCodeReader()

    /** @return 二维码原文；图中没有可识别的二维码时返回 null */
    fun decodePixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return runCatching {
            val source = RGBLuminanceSource(width, height, pixels)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
            reader.decode(bitmap, hints).text
        }.getOrNull()
    }
}
