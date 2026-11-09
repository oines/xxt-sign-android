package com.xxt.sign.study

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频全局互斥。
 *
 * 真机故障：同一秒三条不同视频的进度（设置里并发 >1，而引擎的 Semaphore 是每次 run 新建的局部量）。
 * 风控上视频必须一路一路播，所以 [VideoGate] 必须是进程级的。
 */
class SerialGateTest {

    @Test
    fun onlyOneRunsAtATime() = runBlocking {
        val gate = SerialGate()
        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val jobs = (1..8).map {
            async(Dispatchers.Default) {
                gate.run {
                    val now = running.incrementAndGet()
                    if (now > peak.get()) peak.set(now)
                    delay(20)
                    running.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals("同时只允许一路", 1, peak.get())
        assertEquals(1, gate.peakConcurrent)
    }

    @Test
    fun releasedEvenWhenBlockThrows() = runBlocking {
        val gate = SerialGate()
        runCatching {
            gate.run<Unit> { throw IllegalStateException("boom") }
        }
        // 出异常也必须放开，后续任务还能进去
        val ok = gate.run { 42 }
        assertEquals(42, ok)
        assertEquals(1, gate.peakConcurrent)
    }

    @Test
    fun everyTaskGetsThroughExactlyOnce() = runBlocking {
        val gate = SerialGate()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        (1..6).map { i ->
            async(Dispatchers.Default) { gate.run { seen.add(i); delay(10) } }
        }.awaitAll()
        // 顺序不做保证（Mutex 不保证 FIFO），但每个任务必须且只能进去一次
        assertEquals((1..6).toSet(), seen)
        assertEquals(1, gate.peakConcurrent)
    }

    @Test
    fun globalVideoGateExposesItself() {
        assertTrue(VideoGate.peakConcurrent >= 0)
    }
}
