package com.xxt.sign.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平台任务点口径（已完成任务点 X/Y）+ 章节目录页地址。
 *
 * 真机事故：页面返回了 0/0，老代码当成有效值，分母从 392 退回自数 213，
 * 课程永远显示没做完。0/0 必须当成「没解析到」。
 */
class ChapterPageUrlTest {

    private val course = StudyCourse(
        courseId = "255115240",
        clazzId = "153254051",
        cpi = "573214867",
        title = "走在前列的广东实践"
    )

    /** 真机 HTML：数字在 span 里，后面紧跟 </span>/392 */
    private fun page(x: String, y: String) = """
        <div class="chapter_head clearfix">
          <h2 class="xs_head_name fl">
            <i class="catalog_points_yi fl"></i> 已完成任务点:
            <span style="color:#00B368">$x</span>/$y
            <div class="catalog_jindu"><div class="catalog_ressbar_width" style="width:4%"></div></div>
          </h2>
        </div>
    """.trimIndent()

    @Test
    fun parsesPlatformTotals() {
        val totals = StudyParser.parseTaskPointTotals(page("11", "392"))
        assertEquals(11 to 392, totals)
    }

    /** 0/0 = 这次请求没拿到统计口径，不是「一个都没做」 */
    @Test
    fun zeroOverZeroIsNotAValidTotal() {
        assertNull(StudyParser.parseTaskPointTotals(page("0", "0")))
    }

    /** 0/140 是合法的（真的一个都没做），不能和 0/0 混为一谈 */
    @Test
    fun zeroDoneWithRealTotalIsValid() {
        assertEquals(0 to 140, StudyParser.parseTaskPointTotals(page("0", "140")))
    }

    @Test
    fun noKeywordMeansUnparsed() {
        assertNull(StudyParser.parseTaskPointTotals("<html><body>啥也没有</body></html>"))
    }

    /** 主地址必须是 mooc2-ans + /mooc2-ans/mycourse/studentcourse，四个参数缺一不可 */
    @Test
    fun primaryUrlHasAllFourParams() {
        val urls = ChapterPageUrl.candidates(course, now = 1700000000000L)
        val primary = urls.first()
        assertTrue(primary, primary.startsWith("https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?"))
        assertTrue(primary, primary.contains("courseid=255115240"))
        assertTrue(primary, primary.contains("clazzid=153254051"))
        assertTrue(primary, primary.contains("cpi=573214867"))
        assertTrue(primary, primary.contains("ut=s"))
    }

    /** 0/0 之后要换备用域名再试一次 */
    @Test
    fun fallbackUsesMooc1WithSameParams() {
        val urls = ChapterPageUrl.candidates(course)
        assertTrue(urls.size >= 2)
        val fallback = urls[1]
        assertTrue(fallback, fallback.startsWith("https://mooc1.chaoxing.com/mooc-ans/mycourse/studentcourse?"))
        assertTrue(fallback, fallback.contains("courseid=255115240"))
        assertTrue(fallback, fallback.contains("clazzid=153254051"))
        assertTrue(fallback, fallback.contains("cpi=573214867"))
        assertTrue(fallback, fallback.contains("ut=s"))
    }

    /** 第三次仍回主域名，但带时间戳，防服务端缓存了一个 0/0 的结果 */
    @Test
    fun lastResortRetriesPrimaryWithCacheBuster() {
        val urls = ChapterPageUrl.candidates(course, now = 1700000000000L)
        assertEquals(3, urls.size)
        assertTrue(urls[2], urls[2].startsWith("https://mooc2-ans.chaoxing.com/"))
        assertTrue(urls[2], urls[2].contains("t=1700000000000"))
    }

    /** 课程缓存里没有 cpi：要能被识别出来（上层打日志），不能拿 enc 或 0 顶替 */
    @Test
    fun missingCpiIsDetectableAndNotFaked() {
        val noCpi = course.copy(cpi = "")
        assertFalse(ChapterPageUrl.hasCpi(noCpi))
        assertTrue(ChapterPageUrl.hasCpi(course))
        val url = ChapterPageUrl.candidates(noCpi).first()
        assertTrue(url, url.contains("cpi=&ut=s"))
        assertFalse(url, url.contains("cpi=0&"))
    }
}
