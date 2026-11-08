package com.xxt.sign

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.xxt.sign.databinding.ActivityMainBinding
import com.xxt.sign.ui.AccountsFragment
import com.xxt.sign.ui.HomeFragment
import com.xxt.sign.ui.LogFragment
import com.xxt.sign.ui.QuizFragment
import com.xxt.sign.ui.SignFragment
import com.xxt.sign.ui.StudyFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }

        // 先放第一个页面，再设置选中项；showTab 用 tag 查找保证幂等
        showTab(R.id.nav_home)
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    /** 供其它页面跳 tab（例如刷课页的「章节测验」按钮） */
    fun openTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    /** 用 tag 查找已存在的 Fragment，避免重建时重复 add；commitNow 保证同步生效 */
    private fun showTab(itemId: Int) {
        val tag = when (itemId) {
            R.id.nav_home -> TAG_HOME
            R.id.nav_accounts -> TAG_ACCOUNTS
            R.id.nav_sign -> TAG_SIGN
            R.id.nav_study -> TAG_STUDY
            R.id.nav_quiz -> TAG_QUIZ
            R.id.nav_log -> TAG_LOG
            else -> return
        }

        binding.toolbar.title = when (itemId) {
            R.id.nav_home -> getString(R.string.tab_home)
            R.id.nav_accounts -> getString(R.string.tab_accounts)
            R.id.nav_sign -> getString(R.string.tab_sign)
            R.id.nav_study -> getString(R.string.tab_study)
            R.id.nav_quiz -> getString(R.string.tab_quiz)
            else -> getString(R.string.tab_log)
        }

        val fm = supportFragmentManager
        val target: Fragment = fm.findFragmentByTag(tag) ?: when (itemId) {
            R.id.nav_home -> HomeFragment()
            R.id.nav_accounts -> AccountsFragment()
            R.id.nav_sign -> SignFragment()
            R.id.nav_study -> StudyFragment()
            R.id.nav_quiz -> QuizFragment()
            else -> LogFragment()
        }

        val ft = fm.beginTransaction()
        for (t in ALL_TAGS) {
            val f = fm.findFragmentByTag(t) ?: continue
            if (f !== target) ft.hide(f)
        }
        if (target.isAdded) ft.show(target) else ft.add(R.id.container, target, tag)
        ft.commitNow()
    }

    companion object {
        private const val TAG_HOME = "tab_home"
        private const val TAG_ACCOUNTS = "tab_accounts"
        private const val TAG_SIGN = "tab_sign"
        private const val TAG_STUDY = "tab_study"
        private const val TAG_QUIZ = "tab_quiz"
        private const val TAG_LOG = "tab_log"
        private val ALL_TAGS = listOf(TAG_HOME, TAG_ACCOUNTS, TAG_SIGN, TAG_STUDY, TAG_QUIZ, TAG_LOG)
    }
}
