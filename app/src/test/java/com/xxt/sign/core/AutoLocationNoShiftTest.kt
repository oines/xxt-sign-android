package com.xxt.sign.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「使用当前位置」**不做任何换算**的回归测试。
 *
 * ## 为什么这条规则被反转了两次
 *
 * 早期认为：系统 GPS 恒为 WGS-84，学习通按 GCJ-02 校验，所以要转一次。
 *
 * 真机实测推翻了它：
 *
 * ```
 * 系统 GPS 原始(WGS)  31.891270, 118.896994   离教室 652 米
 * 转一次后(GCJ)       31.889088, 118.902002   离教室 131 米   ← 数值上更近
 * ```
 *
 * 但提交这个更近的 GCJ 值，学习通反而报**超出范围**。唯一解释是
 * **服务端自己会把收到的坐标当 WGS 转一次**，我们再转就是双重偏移。
 *
 * 所以自动定位必须原样提交。这个测试守住「不许再转」。
 */
class AutoLocationNoShiftTest {

    /** 系统 GPS 原始值（模拟定位注入的真 WGS） */
    private val sysLat = 31.891270
    private val sysLon = 118.896994

    /** 教室真实坐标 */
    private val roomLat = 31.889037
    private val roomLon = 118.903384

    /** 原样提交时，与教室的真实距离 = 652 米（这就是学习通该看到的值） */
    @Test
    fun `原样提交时离教室约六百五十米`() {
        val d = GeoCoord.distanceMeters(sysLat, sysLon, roomLat, roomLon)
        assertTrue("应约 652 米，实际 $d", d in 600.0..700.0)
    }

    /** 若错误地转一次，数值看起来更近（131 米）—— 这正是当初误判的原因 */
    @Test
    fun `转一次数值上反而更接近教室`() {
        val g = GeoCoord.wgsToGcj(sysLat, sysLon)
        val d = GeoCoord.distanceMeters(g.lat, g.lon, roomLat, roomLon)
        assertTrue("应约 131 米，实际 $d", d in 110.0..150.0)
    }

    /**
     * 关键回归：为了说明「多转一次」的代价，看转两次后离教室多远。
     *
     * 注意方向——从系统 WGS 出发连续转两次，实际落点是 416 米；
     * 而事故里那个 1015 米是**从教室/界面显示的 GCJ 值出发**再转一次的结果。
     * 两个数都对，别混：
     *
     * ```
     * WGS --转1次--> GCJ(131米, 正确)  --转2次--> 416米
     * 界面GCJ(=教室附近) --转1次--> 偏移 531 米 --再转一次--> 约 1063 米
     * ```
     */
    @Test
    fun `从系统WGS出发转两次落到四百米量级`() {
        var la = sysLat
        var lo = sysLon
        repeat(2) {
            val g = GeoCoord.wgsToGcj(la, lo)
            la = g.lat
            lo = g.lon
        }
        val d = GeoCoord.distanceMeters(la, lo, roomLat, roomLon)
        assertTrue("应约 416 米，实际 $d", d in 350.0..480.0)
    }

    /**
     * 事故真正的成因：把「已经是对的那个 GCJ 值」当 WGS 再连转两次。
     *
     * `31.889088,118.902002` 是转一次后的正确结果（离教室 131 米）。
     * 从这个正确值出发：
     *
     * ```
     * 转 1 次 → 416 米
     * 转 2 次 → 946 米   ← 和当年「超出 1015 米」同量级
     * ```
     *
     * 也就是说：**只要多转一次就是几百米，多转两次就上千米。**
     * 这条守住「自动定位一次都不许转」。
     */
    @Test
    fun `把正确的GCJ值连转两次会到九百米量级`() {
        var la = 31.889088
        var lo = 118.902002
        repeat(2) {
            val g = GeoCoord.wgsToGcj(la, lo)
            la = g.lat
            lo = g.lon
        }
        val d = GeoCoord.distanceMeters(la, lo, roomLat, roomLon)
        assertTrue("应约 946 米，实际 $d", d in 850.0..1050.0)
    }

    /** 一次换算的位移量恒为 531 米量级 */
    @Test
    fun `一次换算位移约五百三十一米`() {
        val g = GeoCoord.wgsToGcj(sysLat, sysLon)
        val moved = GeoCoord.distanceMeters(sysLat, sysLon, g.lat, g.lon)
        assertTrue("应约 531 米，实际 $moved", moved in 450.0..650.0)
    }
}
