package com.xxt.sign

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 启动冒烟测试：真机崩溃过一次（BottomNavigationView 最多 5 项，菜单塞了 6 个，
 * inflate activity_main 直接抛异常），用 Robolectric 把 MainActivity 真正建起来兜住这类问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class MainActivitySmokeTest {

    @Test
    fun launchesAndInflatesLayout() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertNotNull(activity.findViewById<View>(R.id.container))
        assertNotNull(activity.findViewById<View>(R.id.bottomNav))
        assertTrue(!activity.isFinishing)
    }

    /** 底栏项数必须是 1..5，超了就是启动崩溃 */
    @Test
    fun bottomNavigationHasAtMostFiveItems() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
        val size = nav.menu.size()
        assertTrue("底栏项数=$size，必须 ≤5", size in 1..5)
    }
}
