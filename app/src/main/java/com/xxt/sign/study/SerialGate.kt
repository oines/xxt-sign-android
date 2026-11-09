package com.xxt.sign.study

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局串行闸门：同一时刻只放一个进去，其余排队。
 *
 * 为什么需要它：刷课引擎里的 `Semaphore` 是**每次 run 新建的局部变量**，
 * 设置里并发填 3 就是 3 路视频同时播放（真机 logcat 三条进度同一秒出现），
 * 风控第一个抓的就是这个。视频播放必须进程级互斥，重试也算。
 *
 * 纯逻辑 + 可观察的峰值，单测直接并发跑一遍就能证明。
 */
class SerialGate {

    private val mutex = Mutex()
    private val active = AtomicInteger(0)
    private val peak = AtomicInteger(0)

    /** 观察用：曾经同时进去过几个（正常恒为 1） */
    val peakConcurrent: Int get() = peak.get()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val now = active.incrementAndGet()
        if (now > peak.get()) peak.set(now)
        try {
            block()
        } finally {
            active.decrementAndGet()
        }
    }
}

/** 视频播放的全局闸门：任何一条路径开播都要先拿它 */
object VideoGate {
    private val gate = SerialGate()

    suspend fun <T> run(block: suspend () -> T): T = gate.run(block)

    val peakConcurrent: Int get() = gate.peakConcurrent
}
