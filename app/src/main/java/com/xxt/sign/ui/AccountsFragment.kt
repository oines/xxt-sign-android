package com.xxt.sign.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xxt.sign.R
import com.xxt.sign.core.Account
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.databinding.DialogAccountBinding
import com.xxt.sign.databinding.FragmentAccountsBinding
import com.xxt.sign.net.CxSession
import com.xxt.sign.sign.SignEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AccountsFragment : Fragment() {

    private var _b: FragmentAccountsBinding? = null
    private val b get() = _b!!

    /** 人机验证回调：拿到 validate 后继续原来的动作 */
    private var onCaptchaValidate: ((String) -> Unit)? = null

    private val captchaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val v = res.data?.getStringExtra(CaptchaActivity.RESULT_VALIDATE)
        val cb = onCaptchaValidate
        if (res.resultCode == android.app.Activity.RESULT_OK && !v.isNullOrBlank() && cb != null) {
            cb(v)
        } else {
            toast("未完成人机验证")
        }
    }

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) toast("网页登录成功，会话已同步") else toast("未完成网页登录")
    }

    private val adapter by lazy {
        AccountAdapter(
            onRelogin = { acc ->
                if (acc.authType == Account.AUTH_SMS) {
                    toast("${acc.display} 是验证码登录，请删除后重新用验证码添加")
                } else {
                    toast("正在重新登录 ${acc.display} …")
                    viewLifecycleOwner.lifecycleScope.launch {
                        val r = SignEngine.loginAll(listOf(acc)) { }.firstOrNull()
                        toast(r?.detail ?: "完成")
                    }
                }
            },
            onDelete = { acc ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除账号")
                    .setMessage("确定删除 ${acc.display} 吗？")
                    .setPositiveButton("删除") { _, _ -> AccountStore.remove(acc.id) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAccountsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter
        b.btnAdd.setOnClickListener { showAddDialog() }
        b.btnRefreshAll.setOnClickListener {
            val list = AccountStore.accounts.value
            if (list.isEmpty()) return@setOnClickListener toast("还没有账号")
            toast("正在刷新 ${list.size} 个账号的会话…")
            viewLifecycleOwner.lifecycleScope.launch {
                val rs = SignEngine.checkAll(list)
                toast("会话正常 ${rs.count { it.ok }}/${rs.size}")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            AccountStore.accounts.collect { list ->
                adapter.submit(list)
                b.tvCount.text = "共 ${list.size} 个账号"
                b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                b.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ------------------------------------------------------------ 添加账号

    private fun showAddDialog() {
        val d = DialogAccountBinding.inflate(layoutInflater)
        var mode = 0 // 0 密码，1 短信，2 网页

        fun applyMode() {
            d.tilPassword.visibility = if (mode == 0) View.VISIBLE else View.GONE
            d.rowSmsCode.visibility = if (mode == 1) View.VISIBLE else View.GONE
            d.tvHint.text = when (mode) {
                1 -> "验证码登录不会把密码留在本机。若提示需要图形验证，请换「网页登录」。"
                2 -> "网页登录最稳：直接用官方页面，遇到滑块/点选验证码手动过一下就行，也不需要存密码。"
                else -> "密码方式会自动复用 Cookie 会话，正常只需登录一次。"
            }
        }

        d.toggleMode.check(R.id.btnModePwd)
        d.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.btnModeSms -> 1
                R.id.btnModeWeb -> 2
                else -> 0
            }
            applyMode()
        }
        applyMode()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加学习通账号")
            .setView(d.root)
            .setPositiveButton("登录", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val phone = d.etPhone.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty()) return@setOnClickListener toast("请输入手机号")

            val code = d.etCode.text?.toString()?.trim().orEmpty()
            val pwd = d.etPassword.text?.toString().orEmpty()
            if (mode == 1 && code.isEmpty()) return@setOnClickListener toast("请先获取并输入短信验证码")
            if (mode == 0 && pwd.isEmpty()) return@setOnClickListener toast("请输入密码")

            val acc = Account(
                phone = phone,
                password = if (mode == 0) pwd else "",
                authType = when (mode) {
                    1 -> Account.AUTH_SMS
                    2 -> Account.AUTH_WEB
                    else -> Account.AUTH_PWD
                }
            )
            AccountStore.upsert(acc)
            dialog.dismiss()

            if (mode == 2) {
                openWebLogin(acc)
                return@setOnClickListener
            }

            toast("正在登录…")
            viewLifecycleOwner.lifecycleScope.launch {
                val session = CxSession(acc)
                val err = if (mode == 1) session.loginBySmsCode(code) else session.login()
                AccountStore.upsert(acc)
                if (err.isEmpty()) {
                    toast("登录成功（${acc.display}）")
                } else {
                    LogStore.add("[${acc.phone}] $err", false)
                    offerWebLogin(acc, err)
                }
            }
        }

        d.btnSendCode.setOnClickListener {
            val phone = d.etPhone.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty()) return@setOnClickListener toast("请先填手机号")

            fun doSend(validate: String) {
                d.btnSendCode.isEnabled = false
                d.btnSendCode.text = "发送中…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val err = CxSession(Account(phone = phone, authType = Account.AUTH_SMS))
                        .sendSmsCode(validate)

                    if (err.startsWith("需要人机验证")) {
                        d.btnSendCode.text = "需人机验证"
                        onCaptchaValidate = { v -> doSend(v) }
                        captchaLauncher.launch(
                            CaptchaActivity.intent(
                                requireContext(), null,
                                CaptchaActivity.CAPTCHA_LOGIN,
                                CaptchaActivity.TYPE_CLICK,
                                "发送短信验证码需要人机验证，按提示点选即可"
                            )
                        )
                        return@launch
                    }

                    if (err.isNotEmpty()) {
                        toast(err)
                        d.btnSendCode.text = "获取验证码"
                        d.btnSendCode.isEnabled = true
                        return@launch
                    }
                    toast("验证码已发送，请注意查收")
                    for (i in 60 downTo 1) {
                        if (!isAdded) return@launch
                        d.btnSendCode.text = "${i}s 后重发"
                        delay(1000)
                    }
                    d.btnSendCode.text = "重新获取"
                    d.btnSendCode.isEnabled = true
                }
            }

            doSend("")
        }
    }

    /** 登录失败且看起来是被验证码拦了，就直接把人送去官方网页登录 */
    private fun offerWebLogin(acc: Account, err: String) {
        val looksLikeCaptcha = err.contains("验证码") || err.contains("会话") ||
            err.lowercase().contains("captcha")
        if (!looksLikeCaptcha) {
            toast(err)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("需要人工验证")
            .setMessage("$err\n\n学习通用的验证码是滑块/点选，类型还会变，程序无法自动过。用官方网页登录一下即可，遇到验证码手动滑一下。")
            .setPositiveButton("网页登录") { _, _ -> openWebLogin(acc) }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun openWebLogin(acc: Account) {
        webLoginLauncher.launch(
            WebAuthActivity.intent(
                requireContext(), acc.id, WebAuthActivity.URL_LOGIN, "网页登录 · ${acc.phone}"
            )
        )
    }
}
