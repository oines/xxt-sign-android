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

    /** 五个 tab 不能复用同一个图标（真机上出现过两个对勾、两个播放键） */
    @Test
    fun bottomNavigationIconsAreDistinct() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)

        val ids = mutableListOf<Int>()
        for (i in 0 until nav.menu.size()) {
            val icon = nav.menu.getItem(i).icon ?: continue
            ids.add(org.robolectric.Shadows.shadowOf(icon).createdFromResId)
        }
        assertTrue("底栏图标拿不到 drawable 资源", ids.size == nav.menu.size())
        assertTrue("底栏图标有重复：$ids", ids.distinct().size == ids.size)
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
