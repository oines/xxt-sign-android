package com.xxt.sign.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 取设备当前位置。
 *
 * 踩过的坑：
 *  - **优先用 `fused` provider**。Google 已经把 GPS / 网络 / 传感器融合好了，
 *    它比单独请求 GPS 快得多也准得多；只查 GPS 在室内基本拿不到结果。
 *  - **要"最准的"，不是"最先到的"**。WiFi/基站定位来得快但粗（几百米），
 *    GPS 来得慢但准（几米）。先到就返回的话，拿到的正是最粗的那个。
 *    签到位置是有半径校验的，粗定位可能直接落到范围外。
 *  - 多个 provider **并发请求**，不要只请求第一个。
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
                val m = ageMs / 60_000L
                return when {
                    m < 1 -> "刚刚"
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
     * 取一次定位。
     *
     * 若已有 30 分钟内的缓存且精度可接受，直接秒回；
     * 否则并发请求所有 provider，**在 `timeoutMs` 窗口内收集、取精度最好的**，
     * 一旦拿到足够准的（≤ [GOOD_ACCURACY_M] 米）就提前返回。
     */
    @SuppressLint("MissingPermission")
    suspend fun current(
        ctx: Context,
        timeoutMs: Long = 12_000L,
        mode: SourceMode = SourceMode.GPS_ONLY,
        acceptCachedImmediately: Boolean = true
    ): Fix? = coroutineScope {
        val lm = manager(ctx) ?: return@coroutineScope null
        val enabled = enabledProviders(lm)
        LogStore.add("[定位] 策略=$mode 可用 provider: ${enabled.joinToString()}", true)
        if (enabled.isEmpty()) return@coroutineScope null

        val cached = bestCached(lm)
        LogStore.add(
            "[定位] 缓存：" + (
                cached?.let {
                    "${it.provider} 精度${it.accuracy}m ${it.ageText}（${it.location.latitude},${it.location.longitude}）"
                } ?: "无"
                ),
            true
        )
        if (acceptCachedImmediately && cached != null &&
            cached.ageMs < GOOD_ENOUGH_MS && !cached.coarse &&
            (mode == SourceMode.AUTO || cached.provider == LocationManager.GPS_PROVIDER)
        ) {
            return@coroutineScope cached
        }

        val executor = ContextCompat.getMainExecutor(ctx)

        // GPS 优先：先只请 GPS。它准，但室内可能拿不到，所以失败才降级。
        if (mode == SourceMode.GPS_ONLY && enabled.contains(LocationManager.GPS_PROVIDER)) {
            val t0 = System.currentTimeMillis()
            val gps = requestOnce(lm, LocationManager.GPS_PROVIDER, executor, timeoutMs)
            LogStore.add(
                "[定位] GPS 返回 " +
                    (gps?.let { "${it.latitude},${it.longitude} 精度${it.accuracy.toInt()}m" } ?: "null") +
                    "（${System.currentTimeMillis() - t0}ms）",
                gps != null
            )
            if (gps != null) return@coroutineScope Fix(gps, LocationManager.GPS_PROVIDER, false)
            LogStore.add("[定位] GPS 没结果，降级到其它 provider", false)
        }

        val pool = if (mode == SourceMode.GPS_ONLY) {
            enabled.filter { it != LocationManager.GPS_PROVIDER }
        } else enabled

        val ch = Channel<Fix>(Channel.BUFFERED)
        val jobs = pool.map { p ->
            launch(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val loc = requestOnce(lm, p, executor, timeoutMs)
                LogStore.add(
                    "[定位] $p 返回 " +
                        (loc?.let { "${it.latitude},${it.longitude} 精度${it.accuracy.toInt()}m" } ?: "null") +
                        "（${System.currentTimeMillis() - t0}ms）",
                    loc != null
                )
                if (loc != null) ch.send(Fix(loc, p, false))
            }
        }

        var best: Fix? = null
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val remain = end - System.currentTimeMillis()
            val f = withTimeoutOrNull(remain) { ch.receive() } ?: break
            if (best == null || f.accuracy < best!!.accuracy) best = f
            if ((best?.accuracy ?: Int.MAX_VALUE) <= GOOD_ACCURACY_M.toInt()) break
        }
        jobs.forEach { it.cancel() }

        val result = best ?: cached
        LogStore.add(
            "[定位] 最终：" + (
                result?.let {
                    "${it.provider} 精度${it.accuracy}m ${it.ageText} ${it.location.latitude},${it.location.longitude}" +
                        if (it.coarse) "（粗略定位！）" else ""
                } ?: "无可用定位"
                ),
            result != null
        )
        result
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(
        lm: LocationManager,
        provider: String,
        executor: java.util.concurrent.Executor,
        timeoutMs: Long
    ): Location? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                lm.getCurrentLocation(provider, null, executor) { l ->
                    if (cont.isActive) cont.resume(l)
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
