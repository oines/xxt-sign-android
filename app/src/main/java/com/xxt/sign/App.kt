package com.xxt.sign

import android.app.Application
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import com.xxt.sign.study.StudyCheckStore
import com.xxt.sign.study.CourseAuditStore
import com.xxt.sign.study.StudyCourseStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        AccountStore.init(this)
        Settings.init(this)
        StudyCheckStore.init(this)
        StudyCourseStore.init(this)
        CourseAuditStore.init(this)
    }

    companion object {
        lateinit var ctx: Application
            private set
    }
}
