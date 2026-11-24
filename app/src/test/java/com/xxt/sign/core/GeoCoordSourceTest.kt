package com.xxt.sign.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标系换算的回归测试。
 *
 * 背景（真机事故）：同学人在教室、实际离签到点约 100 米，学习通却提示**超出范围 1015 米**。
 * GCJ-02 偏移在南京一带**转一次 531 米、转两次 1063 米** —— 1015 米正是「多转了一次」的量级。
 *
 * 所以现在的策略是：**默认不做任何换算**，只有用户在设置里明确选了坐标系才在提交前转一次。
 * 下面这些用例把这个约定钉死。
 */
class GeoCoordSourceTest {

    private val lat = 31.889088
    private val lon = 118.902002

    /** 默认「不转换」：原样返回，一个数字都不许动 */
    @Test
    fun `不转换时原样返回`() {
        val r = GeoCoord.toGcj(lat, lon, Settings.COORD_NONE)
        assertEquals(lat, r.lat, 0.0)
        assertEquals(lon, r.lon, 0.0)
    }

    /** GCJ-02 输入：本来就是学习通口径，也不该动 */
    @Test
    fun `GCJ-02 输入原样返回`() {
        val r = GeoCoord.toGcj(lat, lon, Settings.COORD_GCJ02)
        assertEquals(lat, r.lat, 0.0)
        assertEquals(lon, r.lon, 0.0)
    }

    /** WGS-84 输入：换算一次，位移约 531 米 */
    @Test
    fun `WGS-84 输入换算一次的位移在一个偏移量量级`() {
        val r = GeoCoord.toGcj(lat, lon, Settings.COORD_WGS84)
        val moved = GeoCoord.distanceMeters(lat, lon, r.lat, r.lon)
        assertTrue("位移应约 531 米，实际 $moved", moved in 450.0..650.0)
    }

    /** 关键回归：换算只做一次，绝不能变成两次（两次就是 1063 米，正是事故量级） */
    @Test
    fun `换算只做一次不会变成一千米`() {
        val once = GeoCoord.toGcj(lat, lon, Settings.COORD_WGS84)
        val moved = GeoCoord.distanceMeters(lat, lon, once.lat, once.lon)
        assertTrue("一次换算不应超过 700 米（两次会接近 1063 米），实际 $moved", moved < 700.0)
    }

    /** BD-09 输入：换算到 GCJ-02，位移在几百米量级 */
    @Test
    fun `BD-09 输入能换算到 GCJ-02`() {
        val bd = GeoCoord.gcjToBd09(lat, lon)
        val back = GeoCoord.toGcj(bd.lat, bd.lon, Settings.COORD_BD09)
        // 往返后应回到原点附近（亚米级）
        val err = GeoCoord.distanceMeters(lat, lon, back.lat, back.lon)
        assertTrue("BD-09 往返误差应小于 5 米，实际 $err", err < 5.0)
    }

    /** WGS→GCJ→WGS 往返误差应在厘米级 */
    @Test
    fun `WGS 往返误差极小`() {
        val g = GeoCoord.wgsToGcj(lat, lon)
        val w = GeoCoord.gcjToWgs(g.lat, g.lon)
        val err = GeoCoord.distanceMeters(lat, lon, w.lat, w.lon)
        assertTrue("往返误差应 < 0.1 米，实际 $err", err < 0.1)
    }

    /** 事故数值复核：粘贴值 vs 教室实际，差约 131 米 */
    @Test
    fun `事故坐标的真实间距约一百三十米`() {
        val d = GeoCoord.distanceMeters(31.889088, 118.902002, 31.889037, 118.903384)
        assertTrue("应约 131 米，实际 $d", d in 120.0..145.0)
    }

    /** 事故数值复核：转两次会到 900 米以上，和 1015 米同量级 */
    @Test
    fun `事故坐标转两次会接近一千米`() {
        var la = 31.889088
        var lo = 118.902002
        repeat(2) {
            val g = GeoCoord.wgsToGcj(la, lo)
            la = g.lat
            lo = g.lon
        }
        val d = GeoCoord.distanceMeters(la, lo, 31.889037, 118.903384)
        assertTrue("转两次应到 900 米量级，实际 $d", d in 850.0..1050.0)
    }
}
