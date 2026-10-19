package com.xxt.sign

import android.app.Application
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import com.xxt.sign.study.StudyCheckStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        AccountStore.init(this)
        Settings.init(this)
        StudyCheckStore.init(this)
    }

    companion object {
        lateinit var ctx: Application
            private set
    }
}
