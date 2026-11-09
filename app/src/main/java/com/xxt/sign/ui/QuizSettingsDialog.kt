package com.xxt.sign.ui

import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.DialogQuizSettingsBinding
import com.xxt.sign.work.LlmClient
import kotlinx.coroutines.launch

/**
 * 题库 / 风控 / 大模型设置。原来是测验页里的一个弹窗，
 * 现在测验页不再是 tab，统一收到右上角设置里。
 */
object QuizSettingsDialog {

    private data class ProviderOption(val label: String, val value: String, val bank: Boolean)

    private val providerOptions = listOf(
        ProviderOption("不查题库（只走大模型，大模型关则留空）", "off", false),
        ProviderOption("内置免费题库（免配置）", "auto", true),
        ProviderOption("言溪题库", "yanxi", true),
        ProviderOption("柠檬题库", "lemon", true),
        ProviderOption("tikuAdapter（自建）", "tikuadapter", true),
        ProviderOption("纯大模型（不查题库）", "llm", false)
    )

    private val effortOptions = listOf(
        "低（low，最省最快）" to "low",
        "中（medium，平衡）" to "medium",
        "高（high，更稳但更慢）" to "high"
    )

    private fun defaultEndpointFor(provider: String): String = when (provider.trim().lowercase()) {
        "yanxi" -> "tk.enncy.cn"
        "lemon" -> "lemtk.xyz"
        else -> ""
    }

    fun show(fragment: Fragment) {
        val ctx = fragment.requireContext()
        val v = DialogQuizSettingsBinding.inflate(fragment.layoutInflater)
        var chosen = providerOptions.firstOrNull { it.value == Settings.quizProvider } ?: providerOptions[1]

        val dropdown = v.etProvider as MaterialAutoCompleteTextView
        dropdown.setSimpleItems(providerOptions.map { it.label }.toTypedArray())
        dropdown.setText(chosen.label, false)

        fun syncBankFields() {
            v.bankFields.visibility = if (chosen.bank) View.VISIBLE else View.GONE
        }
        dropdown.setOnItemClickListener { _, _, position, _ ->
            chosen = providerOptions.getOrElse(position) { providerOptions[1] }
            syncBankFields()
            if (v.etEndpoint.text.isNullOrBlank()) v.etEndpoint.setText(defaultEndpointFor(chosen.value))
        }
        syncBankFields()

        v.etEndpoint.setText(Settings.quizEndpoint.ifBlank { defaultEndpointFor(chosen.value) })
        v.etToken.setText(Settings.quizToken)
        v.etQMin.setText(Settings.quizPerQuestionMin.toString())
        v.etQMax.setText(Settings.quizPerQuestionMax.toString())
        v.etSMin.setText(Settings.quizSubmitWaitMin.toString())
        v.etSMax.setText(Settings.quizSubmitWaitMax.toString())
        v.etGap.setText(Settings.quizGapSeconds.toString())
        v.etThreshold.setText(Settings.quizFontThreshold.toString())
        v.swStoreOnly.isChecked = Settings.quizStoreOnly
        v.swLlm.isChecked = Settings.quizLlmEnabled
        v.etLlmKey.setText(Settings.quizLlmKey)
        v.etLlmBaseUrl.setText(Settings.quizLlmBaseUrl)
        v.etLlmModel.setText(Settings.quizLlmModel)

        val effortDropdown = v.etLlmEffort as MaterialAutoCompleteTextView
        var chosenEffort = effortOptions.firstOrNull { it.second == Settings.quizLlmEffort } ?: effortOptions.first()
        effortDropdown.setSimpleItems(effortOptions.map { it.first }.toTypedArray())
        effortDropdown.setText(chosenEffort.first, false)
        effortDropdown.setOnItemClickListener { _, _, position, _ ->
            chosenEffort = effortOptions.getOrElse(position) { effortOptions.first() }
        }
        v.btnTestLlm.setOnClickListener {
            val key = v.etLlmKey.text?.toString()?.trim().orEmpty()
            val base = v.etLlmBaseUrl.text?.toString()?.trim().orEmpty()
            val model = v.etLlmModel.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                v.tvLlmTest.text = "先填 key"
                v.tvLlmTest.setTextColor(ctx.getColor(com.xxt.sign.R.color.err))
                return@setOnClickListener
            }
            v.tvLlmTest.text = "正在测试…"
            v.tvLlmTest.setTextColor(ctx.getColor(com.xxt.sign.R.color.muted))
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val probe = com.xxt.sign.work.LlmClient(
                    apiKey = key,
                    baseUrl = base.ifBlank { com.xxt.sign.work.LlmClient.DEFAULT_BASE_URL },
                    model = model.ifBlank { com.xxt.sign.work.LlmClient.DEFAULT_MODEL },
                    effort = chosenEffort.second
                ).probe()
                if (!fragment.isAdded) return@launch
                v.tvLlmTest.text = probe.detail
                v.tvLlmTest.setTextColor(
                    ctx.getColor(if (probe.ok) com.xxt.sign.R.color.ok else com.xxt.sign.R.color.err)
                )
            }
        }
        bindLinks(ctx, v.tvLinks)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("题库与风控")
            .setView(v.root)
            .setPositiveButton("保存") { _, _ ->
                val provider = chosen.value
                val endpoint = v.etEndpoint.text?.toString()?.trim().orEmpty()
                    .ifBlank { defaultEndpointFor(provider) }
                Settings.quizProvider = provider
                Settings.quizEndpoint = endpoint
                Settings.quizToken = v.etToken.text?.toString()?.trim().orEmpty()
                Settings.quizLlmEnabled = v.swLlm.isChecked
                Settings.quizLlmKey = v.etLlmKey.text?.toString()?.trim().orEmpty()
                Settings.quizLlmBaseUrl = v.etLlmBaseUrl.text?.toString()?.trim().orEmpty()
                    .ifBlank { LlmClient.DEFAULT_BASE_URL }
                Settings.quizLlmModel = v.etLlmModel.text?.toString()?.trim().orEmpty()
                    .ifBlank { LlmClient.DEFAULT_MODEL }
                Settings.quizLlmEffort = chosenEffort.second
                v.etQMin.text?.toString()?.toIntOrNull()?.let { Settings.quizPerQuestionMin = it }
                v.etQMax.text?.toString()?.toIntOrNull()?.let { Settings.quizPerQuestionMax = it }
                v.etSMin.text?.toString()?.toIntOrNull()?.let { Settings.quizSubmitWaitMin = it }
                v.etSMax.text?.toString()?.toIntOrNull()?.let { Settings.quizSubmitWaitMax = it }
                v.etGap.text?.toString()?.toIntOrNull()?.let { Settings.quizGapSeconds = it }
                v.etThreshold.text?.toString()?.toFloatOrNull()?.let { Settings.quizFontThreshold = it }
                Settings.quizStoreOnly = v.swStoreOnly.isChecked
                android.widget.Toast.makeText(
                    ctx,
                    if (v.swStoreOnly.isChecked) "已保存（只暂存）" else "已保存（自动提交测验）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 申请入口收成一行小字，点哪段跳哪 */

    private fun bindLinks(ctx: android.content.Context, tv: TextView) {
        val items = listOf(
            "言溪" to "https://tk.enncy.cn/",
            "柠檬" to "https://lemtk.xyz/",
            "tikuAdapter" to "https://github.com/DokiDoki1103/tikuAdapter",
            "DeepSeek" to "https://platform.deepseek.com/"
        )
        val prefix = "申请/部署入口："
        val text = prefix + items.joinToString(" · ") { it.first }
        val ss = SpannableString(text)
        var cursor = prefix.length
        items.forEach { (label, url) ->
            val start = cursor
            val end = start + label.length
            ss.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                        )
                    }
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = 0xFF1A73E8.toInt()
                    ds.isUnderlineText = true
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(0xFF1A73E8.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = end + 3
        }
        tv.text = ss
        tv.movementMethod = LinkMovementMethod.getInstance()
    }
}
