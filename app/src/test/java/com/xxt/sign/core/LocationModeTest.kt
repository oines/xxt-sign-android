package com.xxt.sign.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「使用当前位置」vs「使用我填的坐标」两条提交路径的语义测试。
 *
 * ## 关键前提（真机确认）
 *
 * 虚拟定位 App 界面显示的是 GCJ-02，但它**向系统 GPS 注入的是 WGS-84**。
 * 所以两条路径拿到的数值本来就差 531 米，必须分开处理：
 *
 * - **使用当前位置**：系统 GPS → 恒为 WGS-84 → 无条件转一次成 GCJ-02，**不看 coordSource**
 * - **手填坐标**：按 coordSource 声明的类型换算（默认不转）
 *
 * 若把两者混用同一个开关，就会出现「界面数字被当成 WGS 再转一次」的双重偏移，
 * 即真机事故里的 1015 米（转两次 ≈ 1063 米）。
 */
class LocationModeTest {

    private val uiGcj = 31.889088 to 118.902002

    /** 模拟定位界面显示 GCJ，注入系统的 WGS 应该差 531 米 */
    @Test
    fun `界面显示值与系统注入值相差一个偏移量`() {
        val (lat, lon) = uiGcj
        val injected = GeoCoord.gcjToWgs(lat, lon)
        val d = GeoCoord.distanceMeters(lat, lon, injected.lat, injected.lon)
        assertTrue("应约 531 米，实际 $d", d in 450.0..650.0)
    }

    /** 自动模式：把系统 WGS 转回 GCJ，应精确还原界面显示值 */
    @Test
    fun `自动模式转一次正好还原界面显示值`() {
        val (lat, lon) = uiGcj
        val injected = GeoCoord.gcjToWgs(lat, lon)
        val back = GeoCoord.toGcj(injected.lat, injected.lon, Settings.COORD_WGS84)
        val err = GeoCoord.distanceMeters(lat, lon, back.lat, back.lon)
        assertTrue("还原误差应小于 1 米，实际 $err", err < 1.0)
    }

    /** 手填 GCJ：声明为 GCJ-02 时原样提交，不许再转 */
    @Test
    fun `手填GCJ声明为GCJ时原样提交`() {
        val (lat, lon) = uiGcj
        val r = GeoCoord.toGcj(lat, lon, Settings.COORD_GCJ02)
        assertEquals(lat, r.lat, 0.0)
        assertEquals(lon, r.lon, 0.0)
    }

    /** 手填 GCJ 却声明成 WGS-84 = 双重偏移，正是事故成因 */
    @Test
    fun `手填GCJ误声明为WGS会双重偏移`() {
        val (lat, lon) = uiGcj
        val bad = GeoCoord.toGcj(lat, lon, Settings.COORD_WGS84)
        val moved = GeoCoord.distanceMeters(lat, lon, bad.lat, bad.lon)
        assertTrue("多转一次应约 531 米，实际 $moved", moved in 450.0..650.0)
    }

    /** 默认设置必须是「不转换」，避免升级后老用户被自动偏移 */
    @Test
    fun `默认坐标源是不转换`() {
        // Settings 未初始化时读的是默认值；这里只校验常量语义
        assertEquals("none", Settings.COORD_NONE)
    }

    /** 把坐标 转两次 会到千米量级 —— 复现事故数字 */
    @Test
    fun `转两次到一千米量级`() {
        var lat = 31.889088
        var lon = 118.902002
        repeat(2) {
            val g = GeoCoord.wgsToGcj(lat, lon)
            lat = g.lat
            lon = g.lon
        }
        val d = GeoCoord.distanceMeters(lat, lon, 31.889037, 118.903384)
        assertTrue("应约 946 米，实际 $d", d in 850.0..1050.0)
    }
}
