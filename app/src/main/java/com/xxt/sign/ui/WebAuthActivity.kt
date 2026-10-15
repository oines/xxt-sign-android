package com.xxt.sign.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.databinding.ActivityWebAuthBinding
import com.xxt.sign.net.CxSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 人工兜底页。
 *
 * 学习通的验证码（滑块 / 文字点选 / 图标点选）来自 captcha.chaoxing.com 的自适应风控，
 * 类型还会变，硬解没有意义。这里直接把官方页面用带 Cookie 的 WebView 打开，
 * 让人自己过一下验证码，完事后把 WebView 里的 Cookie 收回来 —— 风控状态往往就
 * 记在 Cookie 里，后续账号可能就不用再验了。
 *
 * 登录和签到共用这一个页面，只是传进来的 url 不同。
 */
class WebAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWebAuthBinding

    private val accountId: String get() = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: "人工验证"
        binding.toolbar.setNavigationOnClickListener { finish() }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        val web = binding.web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean = false
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        // 把这个账号已有的 Cookie 注进 WebView，页面就是已登录状态
        AccountStore.get(accountId)?.let { acc ->
            val session = CxSession(acc)
            session.jar.all().forEach { c ->
                val host = c.domain.removePrefix(".")
                runCatching { cm.setCookie("https://$host", "${c.name}=${c.value}; path=/") }
            }
            cm.flush()
        }

        web.loadUrl(url)

        binding.btnDone.setOnClickListener { finishWith(true) }
        binding.btnFail.setOnClickListener { finishWith(false) }
    }

    private fun finishWith(ok: Boolean) {
        binding.btnDone.isEnabled = false
        lifecycleScope.launch {
            val okNow = withContext(Dispatchers.IO) { harvestAndCheck() }
            setResult(if (ok && okNow) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    /** 把 WebView 里所有 .chaoxing.com 的 Cookie 收回账号，再校验会话 */
    private suspend fun harvestAndCheck(): Boolean {
        val acc = AccountStore.get(accountId) ?: return false
        val session = CxSession(acc)
        val cm = CookieManager.getInstance()

        HOSTS.forEach { host ->
            val raw = runCatching { cm.getCookie("https://$host") }.getOrNull() ?: return@forEach
            raw.split(';').forEach { kv ->
                val i = kv.indexOf('=')
                if (i > 0) {
                    session.jar.set(kv.substring(0, i).trim(), kv.substring(i + 1).trim())
                }
            }
        }

        val valid = session.adoptCookies()
        AccountStore.upsert(acc)
        return valid
    }

    companion object {
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        private val HOSTS = listOf(
            "chaoxing.com",
            "passport2.chaoxing.com",
            "i.chaoxing.com",
            "mobilelearn.chaoxing.com",
            "mooc1.chaoxing.com",
            "mooc1-api.chaoxing.com"
        )

        const val URL_LOGIN =
            "https://passport2.chaoxing.com/mlogin?fid=&newversion=true&refer=http%3A%2F%2Fi.chaoxing.com"

        fun intent(ctx: Context, accountId: String, url: String, title: String): Intent =
            Intent(ctx, WebAuthActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)

        /** 官方 PC 签到页，带上活动参数后人可以手动完成整个签到流程 */
        fun signUrl(courseId: String, classId: String, aid: String, fid: String, uid: String): String =
            "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/preSign" +
                "?courseId=$courseId&classId=$classId&activePrimaryId=$aid&fid=${fid.ifBlank { "0" }}&uid=$uid"
    }
}
