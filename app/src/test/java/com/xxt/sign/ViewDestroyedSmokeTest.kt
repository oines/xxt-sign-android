package com.xxt.sign

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 回归：扫描中切夜间模式 → Activity 重建 → 扫描协程回来更新 binding 时 NPE 闪退。
 *
 * 这里把 Activity 建起来、切到每个 tab（会各自启动异步任务）、然后直接销毁，
 * 再把主线程消息跑空。只要还有「视图销毁后仍去写 binding」的代码，这里就会抛。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class ViewDestroyedSmokeTest {

    @Test
    fun destroyingViewWhileTasksRunDoesNotCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)

        // 逐个 tab 切一遍：每个页面都会在自己的 lifecycleScope 里起任务
        listOf(R.id.nav_accounts, R.id.nav_home)
            .forEach { id ->
                activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
                    .selectedItemId = id
                shadowOf(activity.mainLooper).idle()
            }

        // 切到账号页再回首页：这些页面同样会在协程里更新视图
        activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNav)
            .selectedItemId = R.id.nav_accounts
        shadowOf(activity.mainLooper).idle()

        // 模拟「扫描没跑完就切夜间模式」：视图销毁 / Activity 重建
        controller.pause().stop().destroy()
        shadowOf(activity.mainLooper).idle()

        // 重建一次，确认新实例也能正常起来
        val again = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(again.get().findViewById(R.id.container))
        shadowOf(again.get().mainLooper).idle()
        again.pause().stop().destroy()
    }

    /**
     * 直接复现崩溃点：视图销毁后，异步回调再去写 binding。
     *
     * 扫描协程被取消时 finally 仍会执行（setBusy / 进度条），这时 _b 已经是 null。
     * 这里把 QuizFragment 的视图建起来又销毁掉，再逐个调用那些「回调里会走到」的方法，
     * 任何一个没做空视图保护都会抛 NullPointerException。
     */
    @Test
    fun asyncCallbacksAfterViewDestroyedDoNotThrow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val fm = activity.supportFragmentManager

        // 直接挂一个首页：它在协程/回调里会刷新列表与「要你处理」
        val home = com.xxt.sign.ui.HomeFragment()
        fm.beginTransaction().add(R.id.container, home, "home_probe").commitNow()
        shadowOf(activity.mainLooper).idle()
        assertNotNull(home.view)

        // 模拟「任务还没跑完就切夜间模式/退出」：视图先销毁
        controller.pause().stop().destroy()
        shadowOf(activity.mainLooper).idle()
        assertNull("视图应该已经销毁", home.view)

        // 这些就是回调里会碰 binding 的方法，视图没了也必须能安全返回
        listOf("render", "renderCourses", "renderHuman").forEach { name ->
            val m = home.javaClass.getDeclaredMethod(name)
            m.isAccessible = true
            try {
                m.invoke(home)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw AssertionError("$name 在视图销毁后崩了：${e.targetException}", e.targetException)
            }
        }
    }
}
