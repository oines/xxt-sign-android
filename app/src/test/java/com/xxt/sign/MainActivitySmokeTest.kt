package com.xxt.sign

import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
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

    /** 右上角菜单必须真的有四项，而且点了能进去（设置/刷新课程/签到记录/日志） */
    @Test
    fun toolbarMenuHasSettingsRefreshSignRecordsAndLogs() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertNotNull("toolbar 没找到", toolbar)

        val titles = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it).title.toString() }
        assertTrue("菜单缺项：$titles", titles.contains("设置"))
        assertTrue("菜单缺项：$titles", titles.contains("刷新课程"))
        assertTrue("菜单缺项：$titles", titles.contains("签到记录"))
        assertTrue("菜单缺项：$titles", titles.contains("日志"))

        // 签到记录页：空列表显示「还没有签到记录」，且没有任何开关
        toolbar.menu.performIdentifierAction(R.id.action_sign_records, 0)
        shadowOf(activity.mainLooper).idle()
        val fragment = activity.supportFragmentManager.fragments
            .firstOrNull { it.javaClass.simpleName == "SignRecordFragment" }
        assertNotNull("签到记录页没打开", fragment)
        assertNotNull(fragment!!.view)
        val empty = fragment.view!!.findViewById<android.widget.TextView>(R.id.tvEmpty)
        assertEquals("还没有签到记录", empty.text.toString())
        val switches = countSwitches(fragment.view!!)
        assertEquals("签到记录页不该有任何开关", 0, switches)
    }

    private fun countSwitches(v: View): Int {
        var n = 0
        if (v is android.widget.CompoundButton) n++
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) n += countSwitches(v.getChildAt(i))
        }
        return n
    }
}
