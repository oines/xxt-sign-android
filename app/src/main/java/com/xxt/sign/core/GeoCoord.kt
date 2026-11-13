package com.xxt.sign.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换。
 *
 * 系统 `LocationManager` 给的是 **WGS-84**（GPS 原始坐标）；学习通、高德、腾讯那张地图显示/校验
 * 的是 **GCJ-02（火星坐标）**。两者在南京一带差 **530 米左右** —— 位置签到按这个距离校验，
 * 直接把 GPS 原始坐标填进去，人在教室里也会被判「超出签到范围」。
 *
 * 实测（2026-11-11，用系统级模拟定位注入已知点验证）：
 * ```
 * 系统给 App 的 WGS-84      31.905238,118.893970
 * 高德地图上选的点 GCJ-02   31.903054,118.898980
 * 本算法换算结果            31.903054,118.898980   （误差 < 1e-6 度）
 * 两点直线距离              531.6 米
 * ```
 *
 * 算法就是公开的 GCJ-02 偏移公式，`gcjToWgs` 用迭代反解（误差 < 1 厘米）。
 */
object GeoCoord {

    data class LatLng(val lat: Double, val lon: Double)

    /** 中国大陆范围外不做偏移（和主流实现一致） */
    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    /** WGS-84 → GCJ-02 */
    fun wgsToGcj(lat: Double, lon: Double): LatLng {
        if (outOfChina(lat, lon)) return LatLng(lat, lon)
        val dLatRaw = transformLat(lon - 105.0, lat - 35.0)
        val dLonRaw = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val dLat = (dLatRaw * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLon = (dLonRaw * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return LatLng(lat + dLat, lon + dLon)
    }

    /** GCJ-02 → WGS-84（迭代反解） */
    fun gcjToWgs(lat: Double, lon: Double): LatLng {
        if (outOfChina(lat, lon)) return LatLng(lat, lon)
        var wLat = lat
        var wLon = lon
        repeat(6) {
            val g = wgsToGcj(wLat, wLon)
            wLat += lat - g.lat
            wLon += lon - g.lon
        }
        return LatLng(wLat, wLon)
    }

    /** 两点距离（米），haversine */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val s = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * asin(sqrt(s.coerceIn(0.0, 1.0)))
    }

    private const val PI = 3.141592653589793
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val EARTH_R = 6371000.0

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLon(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }
}
