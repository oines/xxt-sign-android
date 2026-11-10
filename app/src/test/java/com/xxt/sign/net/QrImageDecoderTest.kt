package com.xxt.sign.net

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrImageDecoderTest {

    private fun qrPixels(text: String, size: Int = 320): Triple<IntArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(pixels, size, size)
    }

    @Test
    fun decodePixels_readsSigninQr() {
        val text = "SIGNIN:aid=123456&source=15&Code=abcdef&enc=xyz"
        val (pixels, w, h) = qrPixels(text)
        assertEquals(text, QrImageDecoder.decodePixels(pixels, w, h))
    }

    @Test
    fun decodePixels_returnsNullForBlankImage() {
        val w = 120
        val h = 80
        assertNull(QrImageDecoder.decodePixels(IntArray(w * h) { 0xFFFFFFFF.toInt() }, w, h))
    }
}
