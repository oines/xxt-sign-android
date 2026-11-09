package com.xxt.sign.study

import android.content.SharedPreferences
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings

/**
 * 体检结果缓存：首页不联网也能显示「还差几个任务点」，
 * 巡检跑完后写回，界面跟着变。
 *
 * 格式定义在 [CourseAuditJson]（读写共用，避免漏写 rowsVersion 这种事故）。
 */
object CourseAuditStore {

    private lateinit var sp: SharedPreferences

    fun init(context: android.content.Context) {
        sp = context.getSharedPreferences("xxt_course_audit", 0)
    }

    fun save(accountId: String, audits: List<CourseAudit>) {
        if (accountId.isBlank() || !::sp.isInitialized) return
        sp.edit().putString(accountId, CourseAuditJson.encode(audits)).apply()
    }

    fun load(accountId: String): List<CourseAudit> {
        if (accountId.isBlank() || !::sp.isInitialized) return emptyList()
        val raw = sp.getString(accountId, "") ?: return emptyList()
        return CourseAuditJson.decode(raw)
    }

    fun loadCurrent(): List<CourseAudit> {
        val account = AccountStore.get(Settings.studyAccountId) ?: return emptyList()
        return load(account.id)
    }

    /** 存一轮、读回来：单测和真机自检都用它确认格式没走样 */
    fun roundTrip(audits: List<CourseAudit>): List<CourseAudit> = CourseAuditJson.decode(CourseAuditJson.encode(audits))

    /** 全绿了几门 / 一共几门 / 还差多少任务点 */
    fun summary(audits: List<CourseAudit>): Triple<Int, Int, Int> {
        if (audits.isEmpty()) return Triple(0, 0, 0)
        val green = audits.count { it.allGreen }
        return Triple(green, audits.size, audits.sumOf { it.remaining })
    }
}
