package com.xxt.sign

import android.app.Application
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        AccountStore.init(this)
        Settings.init(this)
    }

    companion object {
        lateinit var ctx: Application
            private set
    }
}
