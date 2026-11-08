package com.xxt.sign

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.xxt.sign.databinding.ActivityMainBinding
import com.xxt.sign.ui.AccountsFragment
import com.xxt.sign.ui.HomeFragment
import com.xxt.sign.ui.LogFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            val route = when (item.itemId) {
                R.id.nav_home -> Route.HOME
                R.id.nav_accounts -> Route.ACCOUNTS
                else -> return@setOnItemSelectedListener false
            }
            showTab(route)
            true
        }

        // 底部高亮必须由 Route 反推，别处直接 showTab 之后也能点回同一个 tab
        binding.bottomNav.setOnItemReselectedListener { item -> routeOf(item.itemId)?.let { showTab(it) } }

        // 日志是二级页：按返回键应该回首页，不能直接退出 App
        // （真机上从日志页返回会直接回桌面，还会把桌面搜索框唤醒）
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
            if (currentRoute == Route.LOG) {
                binding.bottomNav.selectedItemId = R.id.nav_home
                showTab(Route.HOME)
            } else {
                finish()
            }
            }
        })

        // 冷启动落在首页（结果面板）；旋转/换主题后回到原来那一页
        val restored = savedInstanceState?.getString(KEY_ROUTE)
            ?.let { name -> Route.entries.firstOrNull { it.name == name } }
            ?: Route.HOME
        showTab(restored)
        // 日志页在底栏里没有对应项，别去动选中态（否则会触发监听把内容切走）
        if (restored != Route.LOG) binding.bottomNav.selectedItemId = idOf(restored)

        // 右上角：设置 / 刷新课程 / 日志，界面上不摆第二个按钮
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    val home = supportFragmentManager.findFragmentByTag(Route.HOME.tag)
                    if (home != null) {
                        com.xxt.sign.ui.QuizSettingsDialog.show(home)
                    } else {
                        showTab(Route.HOME)
                        supportFragmentManager.executePendingTransactions()
                        supportFragmentManager.findFragmentByTag(Route.HOME.tag)
                            ?.let { com.xxt.sign.ui.QuizSettingsDialog.show(it) }
                    }
                    true
                }
                R.id.action_refresh -> {
                    refreshCourses()
                    true
                }
                R.id.action_logs -> {
                    openLogs()
                    true
                }
                else -> false
            }
        }
    }

    private var currentRoute: Route = Route.HOME

    /** 供其它页面跳 tab（例如刷课页的「章节测验」按钮） */
    fun openTab(itemId: Int) {
        val id = when (itemId) {
            R.id.nav_home, R.id.nav_accounts -> itemId
            else -> R.id.nav_home
        }
        binding.bottomNav.selectedItemId = id
    }

    /** 右上角「刷新课程」：拉一遍课程 + 章节，首页立刻能看到 */
    private fun refreshCourses() {
        val account = com.xxt.sign.core.AccountStore.get(com.xxt.sign.core.Settings.studyAccountId)
        if (account == null) {
            android.widget.Toast.makeText(this, "先到账号页加一个账号", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.widget.Toast.makeText(this, "正在刷新课程…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val (session, err) = com.xxt.sign.sign.SignEngine.sessionFor(account)
                    if (session == null) return@runCatching err to null
                    val api = com.xxt.sign.study.StudyApi(session)
                    val courses = api.getCourses()
                    val chapters = mutableMapOf<String, List<com.xxt.sign.study.StudyChapter>>()
                    courses.forEach { c ->
                        chapters[c.key] = runCatching { api.getChapters(c) }.getOrDefault(emptyList())
                    }
                    com.xxt.sign.study.StudyCourseStore.saveCourses(account.id, courses, chapters)
                    "" to courses.size
                }.getOrElse { (it.message ?: "刷新失败") to null }
            }
            val toast = if (result.second == null) result.first else "已刷新 ${result.second} 门课程"
            android.widget.Toast.makeText(this@MainActivity, toast, android.widget.Toast.LENGTH_SHORT).show()
            showTab(Route.HOME)
        }
    }

    /** 日志是二级页：不在底栏里，从首页/测验页进来 */
    fun openLogs() = showTab(Route.LOG)

    private fun routeOf(itemId: Int): Route? = when (itemId) {
        R.id.nav_home -> Route.HOME
        R.id.nav_accounts -> Route.ACCOUNTS
        else -> null
    }

    private fun idOf(route: Route): Int = when (route) {
        Route.HOME -> R.id.nav_home
        // 账号外的页面在底栏里没有对应项，给个已知 id 不让它崩
        else -> R.id.nav_home
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ROUTE, currentRoute.name)
        super.onSaveInstanceState(outState)
    }

    /** 底栏最多 5 项（BottomNavigationView 的硬限制），其余页面走二级入口 */
    private enum class Route(val tag: String, val titleRes: Int) {
        HOME("tab_home", R.string.tab_home),
        ACCOUNTS("tab_accounts", R.string.tab_accounts),
        LOG("tab_log", R.string.tab_log)
    }

    private fun showTab(route: Route) {
        currentRoute = route
        binding.toolbar.title = getString(route.titleRes)

        val fm = supportFragmentManager
        val target: Fragment = fm.findFragmentByTag(route.tag) ?: when (route) {
            Route.HOME -> HomeFragment()
            Route.ACCOUNTS -> AccountsFragment()
            Route.LOG -> LogFragment()
        }

        val ft = fm.beginTransaction()
        for (r in Route.entries) {
            val f = fm.findFragmentByTag(r.tag) ?: continue
            if (f !== target) ft.hide(f)
        }
        if (target.isAdded) ft.show(target) else ft.add(R.id.container, target, route.tag)
        ft.commitNow()
    }

    companion object {
        private const val KEY_ROUTE = "current_route"
    }
}
