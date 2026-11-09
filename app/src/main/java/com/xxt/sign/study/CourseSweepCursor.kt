package com.xxt.sign.study

import com.xxt.sign.core.Settings

/** 体检轮转游标：每轮看下一门课，别每次只盯着第一门 */
object CourseSweepCursor {

    fun next(accountId: String, courseCount: Int): Int {
        if (courseCount <= 0) return 0
        val key = "sweep_cursor_$accountId"
        val last = Settings.rawInt(key, 0)
        val next = (last + 1).mod(courseCount)
        Settings.putRawInt(key, next)
        return next
    }
}
