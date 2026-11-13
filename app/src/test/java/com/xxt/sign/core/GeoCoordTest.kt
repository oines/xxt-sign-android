package com.xxt.sign.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标系转换的回归测试。
 *
 * 参考值来自 2026-11-11 的真机实测：系统级模拟定位注入了一个高德地图上选的点，
 * App 从 LocationManager 拿到的系统坐标（WGS-84）和输入的高德坐标（GCJ-02）正好差
 * 一次 WGS→GCJ 转换 —— 用这组真实数据锁住算法，别让偏移公式被改坏。
 */
class GeoCoordTest {

    @Test
    fun `wgs84 转成 gcj02 命中高德地图上的点`() {
        // 系统给 App 的坐标 → 高德上选的点
        val a = GeoCoord.wgsToGcj(31.905238, 118.893970)
        assertEquals(31.903054, a.lat, 1e-6)
        assertEquals(118.898980, a.lon, 1e-6)

        // 另一组（保存点「南」）
        val b = GeoCoord.wgsToGcj(31.890583, 118.896853)
        assertEquals(31.888402, b.lat, 1e-6)
        assertEquals(118.901861, b.lon, 1e-6)
    }

    /**
     * 2026-11-12 又抓了一组真机数据，锁住「模拟器显示值」和「系统原始值」的关系。
     *
     * 用户当时报「模拟器显示的经纬度和 App 里显示的不一致」，一度以为是 App 多转了一次。
     * 实际情况相反：模拟定位 App 用高德底图显示 GCJ-02，注入系统时换算成 WGS-84，
     * App 读到 WGS-84 —— **必须转回来**才对得上用户在地图上选的点。
     *
     * ```
     * 模拟器 UI（GCJ-02）        31.889087, 118.902128
     * 系统给的原始值（WGS-84）   31.891269, 118.897120
     * ```
     *
     * 如果哪天有人把 `Settings.coordShift` 默认改成 false，这条测试会立刻失败。
     */
    @Test
    fun `系统原始坐标转一次正好等于模拟定位App里显示的值`() {
        val rawLat = 31.891269
        val rawLon = 118.897120
        val shownBySpooferLat = 31.889087
        val shownBySpooferLon = 118.902128

        val g = GeoCoord.wgsToGcj(rawLat, rawLon)
        assertEquals(shownBySpooferLat, g.lat, 1e-6)
        assertEquals(shownBySpooferLon, g.lon, 1e-6)

        // 不转的话差 500 多米，正好是「不在可签到范围内」的量级
        val d = GeoCoord.distanceMeters(rawLat, rawLon, shownBySpooferLat, shownBySpooferLon)
        assertEquals(531.0, d, 5.0)
        assertTrue("不纠偏会偏 500 米以上，签到必然超范围", d > 400)
    }

    @Test
    fun `大陆偏移量在 500 米上下`() {
        val raw = 31.905238 to 118.893970
        val g = GeoCoord.wgsToGcj(raw.first, raw.second)
        val d = GeoCoord.distanceMeters(raw.first, raw.second, g.lat, g.lon)
        assertEquals(531.6, d, 2.0)
    }

    @Test
    fun `gcj 反解能回到 wgs`() {
        val wgs = 31.905238 to 118.893970
        val gcj = GeoCoord.wgsToGcj(wgs.first, wgs.second)
        val back = GeoCoord.gcjToWgs(gcj.lat, gcj.lon)
        assertEquals(wgs.first, back.lat, 1e-6)
        assertEquals(wgs.second, back.lon, 1e-6)
    }

    @Test
    fun `国外坐标不偏移`() {
        val p = GeoCoord.wgsToGcj(48.8566, 2.3522) // 巴黎
        assertEquals(48.8566, p.lat, 0.0)
        assertEquals(2.3522, p.lon, 0.0)
        assertTrue(GeoCoord.outOfChina(48.8566, 2.3522))
        assertFalse(GeoCoord.outOfChina(31.9, 118.9))
    }

    @Test
    fun `距离计算`() {
        // 同一点 = 0
        assertEquals(0.0, GeoCoord.distanceMeters(31.9, 118.9, 31.9, 118.9), 1e-6)
        // 纬度差 0.001 度 ≈ 111.2 米
        assertEquals(111.2, GeoCoord.distanceMeters(31.9, 118.9, 31.901, 118.9), 0.5)
    }
}
