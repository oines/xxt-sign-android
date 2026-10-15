package com.xxt.sign.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.databinding.ActivityCaptchaBinding
import com.xxt.sign.net.CxSession
import com.xxt.sign.net.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 人机验证页：在 App 内直接跑官方的 captcha SDK（captcha.chaoxing.com/load.min.js），
 * 人在里面滑一下或点一下，SDK 回调给出 validate，我们把它带回业务请求。
 *
 * 这样做的好处：
 *  - 不需要复刻 SDK 的图片签名逻辑（captchaKey / iv 是 JS 算出来的）
 *  - 滑块（签到）和点选（登录）共用同一个页面，只换 captchaId 和 type
 *  - 完全不依赖学习通网页版能不能签到
 */
class CaptchaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptchaBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptchaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val captchaId = intent.getStringExtra(EXTRA_CAPTCHA_ID).orEmpty()
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_SLIDE
        val tip = intent.getStringExtra(EXTRA_TIP).orEmpty()
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID).orEmpty()

        val web = binding.web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        web.settings.setSupportMultipleWindows(false)

        // 验证码服务也会看会话，把账号 Cookie 注进去更稳
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        if (accountId.isNotBlank()) {
            runCatching {
                val acc = AccountStore.get(accountId)
                if (acc != null) {
                    CxSession(acc).jar.all().forEach { c ->
                        cm.setCookie("https://${c.domain.removePrefix(".")}", "${c.name}=${c.value}; path=/")
                    }
                }
            }
            cm.flush()
        }

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVerify(validate: String) {
                if (validate.isBlank()) return
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(RESULT_VALIDATE, validate))
                    finish()
                }
            }

            @JavascriptInterface
            fun onError(msg: String) {
                runOnUiThread { binding.toolbar.subtitle = "异常：$msg" }
            }
        }, "XxtCaptcha")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean = false
        }

        // 关键：file:// 页面去请求 captcha.chaoxing.com 会被 CORS 拦死。
        // 把基准 URL 设成验证码服务域名，页面就与其同源，SDK 的 XHR 才能发出去。
        val cfg = JSONObject()
            .put("captchaId", captchaId)
            .put("type", type)
            .put("tip", tip)
            .toString()

        val rawHtml = runCatching {
            assets.open("captcha.html").bufferedReader().use { it.readText() }
        }.getOrElse {
            binding.toolbar.subtitle = "资源读取失败"
            "<html><body>captcha.html missing</body></html>"
        }

        // WebView 自己加载 SDK 会超时，改用 OkHttp 下下来内联。
        // 关键：SDK 依赖 jQuery，必须先加载 jQuery，否则 SDK 一执行就抛
        // "jQuery is not defined"，导致 initCXCaptcha 永远不出现。
        binding.toolbar.subtitle = "正在准备验证码…"
        lifecycleScope.launch {
            val libs = withContext(Dispatchers.IO) {
                val jq = runCatching {
                    com.xxt.sign.net.Http.base.get(JQUERY_URL).body
                }.getOrDefault("")
                val sdk = runCatching {
                    com.xxt.sign.net.Http.base.get(SDK_URL).body
                }.getOrDefault("")
                jq to sdk
            }
            val (jquery, sdk) = libs

            val libTag = buildString {
                if (jquery.length > 5000) append("<script>").append(jquery).append("</script>")
                else append("<script src=\"").append(JQUERY_URL).append("\"></script>")
                if (sdk.length > 5000) append("<script>").append(sdk).append("</script>")
                else append("<script src=\"").append(SDK_URL).append("\"></script>")
            }
            binding.toolbar.subtitle =
                "jQuery ${jquery.length} / SDK ${sdk.length} 字节"

            val html = rawHtml.replace("<!--LIB-->", libTag).replace("__CONFIG__", cfg)
            web.loadDataWithBaseURL("https://captcha.chaoxing.com/", html, "text/html", "utf-8", null)
        }
    }

    companion object {
        private const val EXTRA_CAPTCHA_ID = "captcha_id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TIP = "tip"
        private const val EXTRA_ACCOUNT_ID = "account_id"

        const val RESULT_VALIDATE = "validate"

        /** 登录场景：点选 */
        const val CAPTCHA_LOGIN = "GcXX5vewqE7DezKGlyvleKCnkTglvGpL"

        /** 签到场景：滑块 */
        const val CAPTCHA_SIGN = "Qt9FIw9o4pwRjOyqM6yizZBh682qN2TU"

        const val TYPE_SLIDE = "slide"
        const val TYPE_CLICK = "textclick"

        /** SDK 依赖 jQuery，必须先加载它 */
        private const val JQUERY_URL = "https://passport2-static.chaoxing.com/js/jquery.min.js"
        private const val SDK_URL = "https://captcha.chaoxing.com/load.min.js"

        fun intent(
            ctx: Context,
            accountId: String?,
            captchaId: String,
            type: String,
            tip: String
        ): Intent = Intent(ctx, CaptchaActivity::class.java)
            .putExtra(EXTRA_ACCOUNT_ID, accountId.orEmpty())
            .putExtra(EXTRA_CAPTCHA_ID, captchaId)
            .putExtra(EXTRA_TYPE, type)
            .putExtra(EXTRA_TIP, tip)
    }
}
