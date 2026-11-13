package com.xxt.sign.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 取设备当前位置。
 *
 * 取法对齐地图 App（高德 / 百度 / 腾讯 SDK）：
 *  1. 先读各 provider 的 `getLastKnownLocation` —— 地图 App 一打开先显示的就是这份；
 *  2. 再 `requestLocationUpdates` 连续订阅，在时间窗口里挑最准的一份。
 *
 * **不要用 `LocationManager.getCurrentLocation()`**（API 30 新增的一次性接口）：
 * 它是新的 Binder 方法，很多「系统级 / 框架级定位模拟」（LSPosed 虚拟定位、KernelSU 模块）
 * 只拦老路径 `requestLocationUpdates` / `getLastKnownLocation`。走 getCurrentLocation
 * 会绕过模拟、拿到真实位置 —— 表现正是「别的 App 都显示模拟位置，只有这个 App 坐标不对」。
 *
 * 其它踩过的坑：
 *  - **优先用 `fused` provider**：比单独请求 GPS 快得多也准得多；只查 GPS 在室内基本拿不到。
 *  - **要"最准的"，不是"最先到的"**：WiFi/基站定位来得快但粗（几百米），先到就返回会拿到最粗的。
 *  - 多个 provider 并发订阅，不要只请求第一个。
 *  - 缓存不要卡「几分钟内」这种过严的条件，明明有可用定位也会被丢掉。
 */
object LocationHelper {

    /** `LocationManager.FUSED_PROVIDER` 是 API 31 才有的常量，低版本用字符串 */
    private const val FUSED = "fused"

    private val PROVIDERS = listOf(
        FUSED,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    /** 精度好到这个程度就不必再等了（米） */
    private const val GOOD_ACCURACY_M = 30f

    /** 精度差到这个程度就当它是粗略定位，要提醒用户（米） */
    const val COARSE_ACCURACY_M = 200f

    /** 定位来源策略 */
    enum class SourceMode {
        /** 只用 GPS；拿不到才降级到其它 provider 并标注 */
        GPS_ONLY,

        /** 所有 provider 并发，取精度最好的 */
        AUTO
    }

    data class Fix(val location: Location, val provider: String, val fromCache: Boolean) {
        val accuracy: Int get() = location.accuracy.toInt()

        /** 是否属于粗略定位（WiFi/基站） */
        val coarse: Boolean get() = location.accuracy > COARSE_ACCURACY_M

        /** 距现在已经多久。用 elapsedRealtimeNanos 算，不受墙钟校时影响 */
        val ageMs: Long
            get() = runCatching {
                (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            }.getOrDefault(System.currentTimeMillis() - location.time)

        val ageText: String
            get() {
                val s = ageMs / 1000L
                val m = ageMs / 60_000L
                return when {
                    s < 60 -> "刚刚"
                    m < 60 -> "$m 分钟前"
                    else -> "${m / 60} 小时前"
                }
            }

        val stale: Boolean get() = ageMs > 5 * 60_000L
    }

    /** 新鲜到可以拿来就用 */
    private const val FRESH_MS = 2 * 60_000L

    /** 够新到值得直接秒回（免得用户干等实时定位） */
    private const val GOOD_ENOUGH_MS = 30 * 60_000L

    /**
     * 缓存最多接受到多久。
     * 故意放得很宽：教室坐标半天前和现在基本是同一个地方，
     * 卡「1 小时内」会把明明可用的定位白白丢掉。
     */
    private const val CACHE_MAX_AGE_MS = 24 * 60 * 60_000L

    fun manager(ctx: Context): LocationManager? =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun enabledProviders(lm: LocationManager): List<String> =
        PROVIDERS.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    /**
     * 最佳缓存定位：在 24 小时内的缓存里**取精度最好的那个**。
     * （不是取最新的：同一时刻的 fused 和 gps 其实是同一次定位，
     *   取精度好的那个更有意义。）
     */
    @SuppressLint("MissingPermission")
    fun bestCached(lm: LocationManager, maxAgeMs: Long = CACHE_MAX_AGE_MS): Fix? =
        PROVIDERS
            .mapNotNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { p to it }
            }
            .map { (p, l) -> Fix(l, p, true) }
            .filter { it.ageMs in 0..maxAgeMs }
            .minByOrNull { it.accuracy }

    /**
     * 取一次定位（和地图 App 一样：上次位置 + 订阅更新）。
     *
     * 若已有 30 分钟内的缓存且精度可接受，直接秒回；
     * 否则订阅 `requestLocationUpdates`，在 `timeoutMs` 窗口内收集、取精度最好的，
     * 一旦拿到足够准的（≤ [GOOD_ACCURACY_M] 米）就提前返回。
     *
     * [useCache] = false 时**不秒回**、也不回退到「很旧的」系统缓存；
     * 但 2 分钟内刚到过的那份仍会当候选（地图 App 也是这么用的，模拟定位往往就走这条）。
     */
    @SuppressLint("MissingPermission")
    suspend fun current(
        ctx: Context,
        timeoutMs: Long = 12_000L,
        mode: SourceMode = SourceMode.GPS_ONLY,
        acceptCachedImmediately: Boolean = true,
        useCache: Boolean = true
    ): Fix? = coroutineScope {
        val lm = manager(ctx) ?: return@coroutineScope null
        val enabled = enabledProviders(lm)
        LogStore.add("[定位] 策略=$mode 可用 provider: ${enabled.joinToString()}", true)
        if (enabled.isEmpty()) return@coroutineScope null

        // 1) 各 provider 的「上次位置」：地图 App 打开时先秒回这份，这里只做候选 + 日志
        val lastKnown = enabled.mapNotNull { p ->
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { Fix(it, p, true) }
        }
        lastKnown.forEach {
            LogStore.add(
                "[定位] 上次位置 ${it.provider}：${describe(it.location)} ${it.ageText}${mockText(it.location)}",
                true
            )
        }

        val cached = if (useCache) bestCached(lm) else null
        LogStore.add(
            "[定位] 缓存：" + (
                cached?.let { "${it.provider} ${describe(it.location)} ${it.ageText}" } ?: "无"
                ),
            true
        )
        if (useCache && acceptCachedImmediately && cached != null &&
            cached.ageMs < GOOD_ENOUGH_MS && !cached.coarse &&
            (mode == SourceMode.AUTO || cached.provider == LocationManager.GPS_PROVIDER)
        ) {
            return@coroutineScope cached
        }

        val ch = Channel<Fix>(Channel.UNLIMITED)
        val fixes = ArrayList<Fix>()
        val listeners = LinkedHashMap<String, LocationListener>()
        val looper = Looper.getMainLooper()
        val startedAt = System.currentTimeMillis()

        fun start(pool: List<String>) {
            pool.forEach { p ->
                if (listeners.containsKey(p)) return@forEach
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val base = Fix(location, p, false)
                        val f = if (base.ageMs > FRESH_MS) base.copy(fromCache = true) else base
                        LogStore.add(
                            "[定位] 收到 ${f.provider}：${describe(location)} ${f.ageText}${mockText(location)}",
                            true
                        )
                        ch.trySend(f)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                    override fun onProviderEnabled(provider: String) {}

                    override fun onProviderDisabled(provider: String) {}
                }
                val ok = runCatching {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, looper)
                }.isSuccess
                if (ok) listeners[p] = listener
                LogStore.add("[定位] 订阅 $p：${if (ok) "成功" else "失败"}", ok)
            }
        }

        fun stop() {
            listeners.forEach { (_, l) -> runCatching { lm.removeUpdates(l) } }
            listeners.clear()
        }

        suspend fun awaitFix(deadline: Long) {
            while (true) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0L) return
                val f = withTimeoutOrNull(remain) { ch.receive() } ?: return
                fixes.add(f)
                if (f.accuracy <= GOOD_ACCURACY_M) return
            }
        }

        try {
            // GPS_ONLY：先只订阅 GPS（地图 App 也是先要高精度），室内拿不到再降级
            if (mode == SourceMode.GPS_ONLY && enabled.contains(LocationManager.GPS_PROVIDER)) {
                start(listOf(LocationManager.GPS_PROVIDER))
                awaitFix(startedAt + timeoutMs * 3 / 5)
                stop()
                val gpsBest = fixes.filter { it.provider == LocationManager.GPS_PROVIDER }
                    .minByOrNull { it.accuracy }
                LogStore.add(
                    "[定位] GPS 阶段：" +
                        (gpsBest?.let { "${describe(it.location)} ${it.ageText}" } ?: "没有结果"),
                    gpsBest != null
                )
                // 只有「够准」才提前结束；一般准的 GPS 也继续收其它 provider 的日志，
                // 免得模拟定位只落在某个 provider 上时被漏掉
                if (gpsBest != null && gpsBest.accuracy <= GOOD_ACCURACY_M.toInt()) {
                    return@coroutineScope gpsBest
                }
            }

            val pool = if (mode == SourceMode.GPS_ONLY) {
                enabled.filter { it != LocationManager.GPS_PROVIDER }
            } else enabled
            if (pool.isNotEmpty()) {
                start(pool)
                awaitFix(startedAt + timeoutMs)
            }
        } finally {
            stop()
        }

        // 优先这次实时收到的；没有实时结果才退回「上次位置」（够新，或调用方允许缓存）
        val realtime = fixes.filter { !it.fromCache }
        val gpsRealtime = realtime.filter { it.provider == LocationManager.GPS_PROVIDER }
            .minByOrNull { it.accuracy }
        var result = if (mode == SourceMode.GPS_ONLY && gpsRealtime != null && !gpsRealtime.coarse) {
            // 仅 GPS 模式：GPS 不粗就认 GPS
            gpsRealtime
        } else {
            realtime.minByOrNull { it.accuracy }
        }
        if (result == null) {
            val fallback = (fixes + lastKnown)
                .filter { it.ageMs in 0..CACHE_MAX_AGE_MS && (useCache || it.ageMs < FRESH_MS) }
                .minByOrNull { it.accuracy }
            if (fallback != null) {
                LogStore.add(
                    "[定位] 没有实时结果，退回上次位置：${fallback.provider} " +
                        "${describe(fallback.location)} ${fallback.ageText}",
                    true
                )
            }
            result = fallback
        }

        LogStore.add(
            "[定位] 最终：" + (
                result?.let {
                    "${it.provider} ${describe(it.location)} ${it.ageText}" +
                        (if (it.fromCache) "（上次位置）" else "") +
                        (if (it.coarse) "（粗略定位！）" else "")
                } ?: "无可用定位"
                ),
            result != null
        )
        result
    }

    private fun describe(l: Location): String =
        String.format(Locale.US, "%.6f,%.6f 精度%dm", l.latitude, l.longitude, l.accuracy.toInt())

    @Suppress("DEPRECATION")
    private fun isMock(l: Location): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock else l.isFromMockProvider
    }.getOrDefault(false)

    private fun mockText(l: Location): String = if (isMock(l)) " ·模拟位置" else ""
}
