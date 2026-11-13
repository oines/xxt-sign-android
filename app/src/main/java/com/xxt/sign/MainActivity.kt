package com.xxt.sign

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.xxt.sign.databinding.ActivityMainBinding
import com.xxt.sign.ui.AccountsFragment
import com.xxt.sign.ui.HomeworkFragment
import com.xxt.sign.ui.LogFragment
import com.xxt.sign.ui.QuizFragment
import com.xxt.sign.ui.SignFragment
import com.xxt.sign.ui.StudyFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** false = 「做题」页显示章节测验；true = 显示作业。工具栏最右侧按钮切换 */
    private var homeworkMode = false
    private var currentTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        // 顶部工具栏最右侧的「作业 / 章节测验」切换。
        // 直接挂在 Toolbar 上（本 Activity 没有 setSupportActionBar，options menu 不会显示）。
        binding.toolbar.inflateMenu(R.menu.menu_quiz_mode)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_quiz_mode) {
                homeworkMode = !homeworkMode
                showTab(R.id.nav_quiz)
                true
            } else {
                false
            }
        }

        // 先放第一个页面，再设置选中项；showTab 用 tag 查找保证幂等
        showTab(R.id.nav_accounts)
        binding.bottomNav.selectedItemId = R.id.nav_accounts
    }

    /** 刷新工具栏切换按钮：只在「做题」页显示，标题表示点了会切到哪 */
    private fun updateModeMenu() {
        val item = binding.toolbar.menu.findItem(R.id.action_quiz_mode) ?: return
        item.isVisible = currentTab == R.id.nav_quiz
        item.title = if (homeworkMode) {
            getString(R.string.action_quiz_mode_back)
        } else {
            getString(R.string.action_quiz_mode)
        }
    }

    /** 用 tag 查找已存在的 Fragment，避免重建时重复 add；commitNow 保证同步生效 */
    private fun showTab(itemId: Int) {
        currentTab = itemId
        // 「做题」页有两种内容：章节测验（原有）和作业（新），用一个标题栏按钮切换
        val showHomework = itemId == R.id.nav_quiz && homeworkMode
        val tag = when (itemId) {
            R.id.nav_accounts -> TAG_ACCOUNTS
            R.id.nav_sign -> TAG_SIGN
            R.id.nav_study -> TAG_STUDY
            R.id.nav_quiz -> if (showHomework) TAG_HOMEWORK else TAG_QUIZ
            R.id.nav_log -> TAG_LOG
            else -> return
        }

        binding.toolbar.title = when (itemId) {
            R.id.nav_accounts -> getString(R.string.tab_accounts)
            R.id.nav_sign -> getString(R.string.tab_sign)
            R.id.nav_study -> getString(R.string.tab_study)
            R.id.nav_quiz -> if (showHomework) getString(R.string.title_homework) else getString(R.string.tab_quiz)
            else -> getString(R.string.tab_log)
        }

        val fm = supportFragmentManager
        val target: Fragment = fm.findFragmentByTag(tag) ?: when (itemId) {
            R.id.nav_accounts -> AccountsFragment()
            R.id.nav_sign -> SignFragment()
            R.id.nav_study -> StudyFragment()
            R.id.nav_quiz -> if (showHomework) HomeworkFragment() else QuizFragment()
            else -> LogFragment()
        }

        val ft = fm.beginTransaction()
        for (t in ALL_TAGS) {
            val f = fm.findFragmentByTag(t) ?: continue
            if (f !== target) ft.hide(f)
        }
        if (target.isAdded) ft.show(target) else ft.add(R.id.container, target, tag)
        ft.commitNow()
        updateModeMenu()
    }

    companion object {
        private const val TAG_ACCOUNTS = "tab_accounts"
        private const val TAG_SIGN = "tab_sign"
        private const val TAG_STUDY = "tab_study"
        private const val TAG_QUIZ = "tab_quiz"
        private const val TAG_HOMEWORK = "tab_homework"
        private const val TAG_LOG = "tab_log"
        private val ALL_TAGS =
            listOf(TAG_ACCOUNTS, TAG_SIGN, TAG_STUDY, TAG_QUIZ, TAG_HOMEWORK, TAG_LOG)
    }
}
