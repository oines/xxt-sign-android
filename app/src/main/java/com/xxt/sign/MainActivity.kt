package com.xxt.sign

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.xxt.sign.databinding.ActivityMainBinding
import com.xxt.sign.ui.AccountsFragment
import com.xxt.sign.ui.LogFragment
import com.xxt.sign.ui.SignFragment

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
        showTab(R.id.nav_accounts)
        binding.bottomNav.selectedItemId = R.id.nav_accounts
    }

    /** 用 tag 查找已存在的 Fragment，避免重建时重复 add；commitNow 保证同步生效 */
    private fun showTab(itemId: Int) {
        val tag = when (itemId) {
            R.id.nav_accounts -> TAG_ACCOUNTS
            R.id.nav_sign -> TAG_SIGN
            R.id.nav_log -> TAG_LOG
            else -> return
        }

        binding.toolbar.title = when (itemId) {
            R.id.nav_accounts -> getString(R.string.tab_accounts)
            R.id.nav_sign -> getString(R.string.tab_sign)
            else -> getString(R.string.tab_log)
        }

        val fm = supportFragmentManager
        val target: Fragment = fm.findFragmentByTag(tag) ?: when (itemId) {
            R.id.nav_accounts -> AccountsFragment()
            R.id.nav_sign -> SignFragment()
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
        private const val TAG_ACCOUNTS = "tab_accounts"
        private const val TAG_SIGN = "tab_sign"
        private const val TAG_LOG = "tab_log"
        private val ALL_TAGS = listOf(TAG_ACCOUNTS, TAG_SIGN, TAG_LOG)
    }
}
