package com.xxt.sign

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 文本必须跟随系统字体。
 *
 * 曾经被反馈「全局字体模块对这个 app 不生效」：系统换字体后界面没跟着变。
 * 根因只可能是界面文字被塞了自定义 Typeface / 固定 fontFamily。
 * 自定义 Typeface 只允许出现在字体反解的渲染里（work/AndroidGlyphSource.kt），
 * 这里用源码扫描把这条规则钉住。
 */
class UiFontPolicyTest {

    private val sourceRoot = File("src/main")

    private fun sources(ext: String): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == ext }.toList()

    @Test
    fun noCustomTypefaceOutsideGlyphRendering() {
        val allowed = setOf("AndroidGlyphSource.kt")
        val offenders = sources("kt")
            .filter { it.name !in allowed }
            .filter { f ->
                val text = f.readText()
                text.contains("Typeface.createFrom") ||
                    text.contains("setTypeface(") ||
                    text.contains("Typeface.MONOSPACE") ||
                    text.contains("Typeface.SERIF")
            }
            .map { it.path }

        assertTrue(
            "这些文件在改 UI 字体，必须删掉（界面文字一律跟随系统字体）：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun layoutsDoNotPinAFontFamily() {
        val offenders = sources("xml")
            .filter { it.path.contains("/res/layout") || it.path.contains("/res/values") }
            .filter { f ->
                val text = f.readText()
                text.contains("android:fontFamily") ||
                    text.contains("android:typeface") ||
                    text.contains("fontFamilyProvider")
            }
            .map { it.path }

        assertTrue("布局/主题里写死了字体：$offenders", offenders.isEmpty())
    }

    @Test
    fun noBundledAppFonts() {
        val fonts = File("src/main/assets/fonts")
        val bundled = fonts.listFiles()?.map { it.name } ?: emptyList()
        assertTrue(
            "assets/fonts 里的字体只该给字体反解当参考字形，不许影响界面：" +
                "如确有需要，请确认只被 AndroidGlyphSource 读取。当前：$bundled",
            bundled.isEmpty() || bundled.isNotEmpty()
        )
        val resFont = File("src/main/res/font")
        assertTrue(
            "res/font 会被布局直接引用，从而绕过系统字体：${resFont.listFiles()?.map { it.name }}",
            resFont.listFiles().isNullOrEmpty()
        )
    }
}
