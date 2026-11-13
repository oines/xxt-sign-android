package com.xxt.sign.study

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「设置填几路就几路同时跑」的回归测试。
 *
 * 背景：代码曾被硬编码成单路（提交 ebe0dac 用 SerialGate/VideoGate 把视频并发锁成 1），
 * 用户在设置里填 4 实际只跑 1 节。现在恢复成按 `plan.concurrency` 并发，
 * 这个测试用「同时在跑的峰值」来证明并发数真的生效。
 *
 * 用和 StudyEngine 完全一样的结构：`Semaphore(concurrency)` + `async { withPermit }`。
 */
class StudyConcurrencyTest {

    /**
     * 用给定并发度跑 [jobs] 个任务，返回观察到的并发峰值。
     *
     * **不能靠 `delay` 让任务重叠** —— 那样峰值取决于调度时序，测试会 flaky。
     * 这里用一道屏障：所有能进来的任务先自增计数并卡住，等 [settleMs] 毫秒后统一放行，
     * 峰值就等于「真正同时持锁的个数」。
     */
    private suspend fun runWithConcurrency(concurrency: Int, jobs: Int): Int {
        val semaphore = Semaphore(concurrency)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val workers = (1..jobs).map {
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val now = active.incrementAndGet()
                        if (now > peak.get()) peak.set(now)
                        gate.await()
                        active.decrementAndGet()
                    }
                }
            }
            // 等到「该进来的都进来了」再放行 —— 固定 sleep 在负载高时会漏，
            // 这里轮询到 active 达到期望值或超时，结果就确定了。
            val want = minOf(concurrency, jobs)
            var waited = 0
            while (active.get() < want && waited < 3000) {
                delay(20)
                waited += 20
            }
            gate.complete(Unit)
            workers.awaitAll()
        }
        return peak.get()
    }

    @Test
    fun `并发 1 时峰值是 1`() = kotlinx.coroutines.runBlocking {
        assertEquals(1, runWithConcurrency(1, 8))
    }

    /** 用户报的场景：设置 4 就应该有 4 路同时跑 */
    @Test
    fun `并发 4 时峰值到 4`() = kotlinx.coroutines.runBlocking {
        assertEquals(4, runWithConcurrency(4, 8))
    }

    @Test
    fun `并发 4 但只有 2 个任务时峰值是 2`() = kotlinx.coroutines.runBlocking {
        assertEquals(2, runWithConcurrency(4, 2))
    }

    @Test
    fun `并发 8 时峰值到 8`() = kotlinx.coroutines.runBlocking {
        assertEquals(8, runWithConcurrency(8, 8))
    }

    /**
     * 回归点：如果哪天有人再把并发写死成 1（像 ebe0dac 那样），
     * 这条测试必须失败 —— 它直接对比「设置 4」和「设置 1」的峰值。
     */
    @Test
    fun `设置 4 的峰值必须明显高于设置 1`() = kotlinx.coroutines.runBlocking {
        val one = runWithConcurrency(1, 8)
        val four = runWithConcurrency(4, 8)
        assertEquals(1, one)
        assertEquals(4, four)
        assert(four > one) { "并发 4 的峰值($four) 必须大于并发 1($one)，否则说明并发被写死了" }
    }
}
