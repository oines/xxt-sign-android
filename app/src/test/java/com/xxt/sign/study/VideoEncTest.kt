package com.xxt.sign.study

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEncTest {

    @Test
    fun encMatchesReferenceImplementation() {
        // 由参考实现的 Python 代码计算：
        // md5("[111][222][333][444][10000][d_yHJ!$pdA~5][100000][0_100]")
        assertEquals(
            "36e649a2d4ed6306eb9b37caf9984db2",
            buildVideoEnc(
                clazzId = "111",
                uid = "222",
                jobId = "333",
                objectId = "444",
                playingTime = 10,
                duration = 100
            )
        )
    }
}
