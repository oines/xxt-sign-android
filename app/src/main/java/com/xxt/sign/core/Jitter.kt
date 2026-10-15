package com.xxt.sign.core

import kotlinx.coroutines.delay
import java.util.Random

/**
 * 拟人化的随机延迟。
 *
 * 为什么不用 `Random.nextLong(a, b)`：那是**均匀分布**，而真人操作不是——
 * 大部分动作偏快，偶尔会停一下（看一眼屏幕、想一下、手滑了重来）。
 * 而且固定间隔（比如每次都 400ms）比没有延迟更可疑，那个常数本身就成了特征。
 *
 * 这里用三角分布（两个均匀随机取平均，峰值在中间偏前）模拟"多数快、少数慢"，
 * 再以一定概率叠一次长停顿。
 */
object Jitter {

    private val rnd = Random()

    fun sample(minMs: Long, maxMs: Long): Long {
        if (maxMs <= minMs) return minMs
        // 三角分布叠加，偏向快的一侧
        val r = (rnd.nextDouble() + rnd.nextDouble()) / 2.0
        var ms = minMs + ((maxMs - minMs) * r).toLong()
        // 约 12% 概率多停一下，模拟"停顿"
        if (rnd.nextInt(100) < 12) ms += rnd.nextLong(600, 2500)
        return ms.coerceIn(minMs, maxMs + 2500)
    }

    suspend fun pause(minMs: Long, maxMs: Long) = delay(sample(minMs, maxMs))

    /** 请求之间的微停顿：不必太久，但要避免固定值 */
    suspend fun betweenRequests() = pause(180, 650)

    /** 同一个账号连签多个活动之间的停顿 */
    suspend fun betweenActivities() = pause(900, 2600)

    /**
     * 账号之间的停顿。
     * 用设置里的 jitterSeconds 当上限，下限给足 1.2 秒，
     * 避免"多账号在同一秒内齐刷刷提交"这种最典型的机器人特征。
     */
    suspend fun betweenAccounts() {
        val j = Settings.jitterSeconds
        if (j <= 0) return
        pause(1200, j * 1000L)
    }

    /** 并发请求前的错峰启动，避免"正好 N 个一批"的规律 */
    suspend fun staggerStart() = pause(0, 350)
}
