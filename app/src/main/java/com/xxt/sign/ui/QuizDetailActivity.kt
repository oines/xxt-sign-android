package com.xxt.sign.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.xxt.sign.databinding.ActivityQuizDetailBinding
import com.xxt.sign.work.QuizQuestionLive
import com.xxt.sign.work.QuizRunManager
import com.xxt.sign.work.WorkOption
import com.xxt.sign.work.WorkQuestion
import kotlinx.coroutines.launch

/**
 * 做题详情独立页：从做题页的紧凑卡片进入，不占用章节列表空间。
 *
 * 只读取 [QuizRunManager.state]，可以前后切换本次运行已经答过的题；在这里改选项
 * 会通过 [QuizRunManager.setAnswerOverride] 影响最终提交，但不会打断引擎的停留/提交节奏。
 */
class QuizDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityQuizDetailBinding

    /** -1 表示还没初始化，第一次渲染时跳到最后一题 */
    private var index = -1

    /** 用户手动切过题后不再自动跟随最新题 */
    private var followLatest = true

    private var ticker: CountDownTimer? = null
    private var tickerAnchor = ""

    /** 题目 qid -> 渲染时用到的选项，供多选点击时重新计算已选集合 */
    private val optionCache = mutableMapOf<String, List<WorkOption>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityQuizDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnDetailPrev.setOnClickListener { move(-1) }
        b.btnDetailNext.setOnClickListener { move(1) }
        b.btnDetailSkip.setOnClickListener {
            QuizRunManager.skipWait()
            showStatus("已跳过本题等待，继续下一题…")
        }
        b.btnDetailSubmit.setOnClickListener { confirmSubmit() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                QuizRunManager.state.collect { render(it) }
            }
        }
    }

    private fun move(delta: Int) {
        val list = QuizRunManager.state.value.questions
        if (list.isEmpty()) return
        followLatest = false
        index = (index + delta).coerceIn(0, list.lastIndex)
        render(QuizRunManager.state.value)
    }

    /** 手动交卷：不再等剩余题目，当前已识别/已手改的答案立即提交，未答的题留空 */
    private fun confirmSubmit() {
        if (!QuizRunManager.isRunning()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("提前交卷")
            .setMessage("当前还没答的题会留空，确定现在交卷？")
            .setPositiveButton("交卷") { _, _ ->
                QuizRunManager.submitNow()
                showStatus("正在提交当前答案…")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showStatus(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun render(s: QuizRunManager.State) {
        val list = s.questions
        if (list.isEmpty()) {
            ticker?.cancel()
            ticker = null
            tickerAnchor = ""
            b.tvDetailEmpty.visibility = View.VISIBLE
            b.tvDetailEmpty.text = when {
                s.running -> "等待下一题…"
                s.finished -> "本次做题已结束"
                else -> "还没有题目详情"
            }
            b.tvDetailStem.visibility = View.GONE
            b.llDetailOptions.visibility = View.GONE
            b.tvDetailLlm.visibility = View.GONE
            b.tvDetailSearch.visibility = View.GONE
            b.tvDetailHint.visibility = View.GONE
            b.tvDetailRepairNote.visibility = View.GONE
            b.tvDetailWarning.visibility = View.GONE
            b.tvDetailMeta.text = ""
            b.tvDetailWait.text = ""
            b.tvDetailIndex.text = "0/0"
            b.btnDetailPrev.isEnabled = false
            b.btnDetailNext.isEnabled = false
            updateActions(s)
            return
        }

        b.tvDetailEmpty.visibility = View.GONE
        b.tvDetailStem.visibility = View.VISIBLE
        b.llDetailOptions.visibility = View.VISIBLE
        b.tvDetailLlm.visibility = View.VISIBLE
        b.tvDetailHint.visibility = View.VISIBLE

        if (followLatest || index !in list.indices) index = list.lastIndex
        val q = list[index]
        val effectiveAnswer = s.overrides[q.qid] ?: q.answer

        b.tvDetailIndex.text = "${index + 1}/${list.size}"
        b.btnDetailPrev.isEnabled = index > 0
        b.btnDetailNext.isEnabled = index < list.lastIndex

        b.tvDetailMeta.text = listOf(
            q.courseTitle,
            q.chapterTitle,
            WorkQuestion.typeName(q.type),
            if (q.source.isBlank()) "" else "来源：${q.source}"
        ).filter { it.isNotBlank() }.joinToString(" · ")

        // 大模型纠错后的题干/选项优先显示；没有纠错结果就显示原始解析结果。
        val repaired = q.correctedStem.isNotBlank() || q.correctedOptions.isNotEmpty()
        b.tvDetailRepairNote.visibility = if (repaired) View.VISIBLE else View.GONE
        val inline = if (q.options.isEmpty() && q.correctedOptions.isEmpty()) parseInlineOptions(q.stem) else null
        val displayOptions = when {
            q.correctedOptions.isNotEmpty() -> q.correctedOptions
            inline != null -> inline.second
            else -> q.options
        }
        val displayStem = when {
            q.correctedStem.isNotBlank() -> q.correctedStem
            inline != null -> inline.first
            else -> q.stem
        }

        b.tvDetailStem.text = displayStem
        buildOptions(q, displayOptions, effectiveAnswer)
        b.tvDetailLlm.text = llmText(q, effectiveAnswer)
        b.tvDetailSearch.visibility = if (q.searchSummary.isBlank()) View.GONE else View.VISIBLE
        b.tvDetailSearch.text = if (q.searchSummary.isBlank()) {
            ""
        } else {
            "联网搜索摘要：\n${q.searchSummary}"
        }
        b.tvDetailWarning.visibility = if (q.warning.isBlank()) View.GONE else View.VISIBLE
        b.tvDetailWarning.text = if (q.warning.isBlank()) "" else "⚠ ${q.warning}"
        updateWait(s, q)
        updateActions(s)
    }

    /** 运行中才允许手动跳题/提前交卷 */
    private fun updateActions(s: QuizRunManager.State) {
        val running = s.running
        b.btnDetailSkip.visibility = if (running) View.VISIBLE else View.GONE
        b.btnDetailSubmit.visibility = if (running) View.VISIBLE else View.GONE
        b.btnDetailSkip.isEnabled = running
        b.btnDetailSubmit.isEnabled = running
    }

    private fun buildOptions(q: QuizQuestionLive, options: List<WorkOption>, effectiveAnswer: String) {
        b.llDetailOptions.removeAllViews()
        optionCache[q.qid] = options
        when (q.type) {
            WorkQuestion.TYPE_JUDGE -> {
                val judge = judgeValue(effectiveAnswer)
                addOption("正确", "true", judge == "true", radio = true, qid = q.qid)
                addOption("错误", "false", judge == "false", radio = true, qid = q.qid)
            }
            WorkQuestion.TYPE_SINGLE, WorkQuestion.TYPE_MULTI -> {
                if (options.isEmpty()) {
                    b.llDetailOptions.addView(fallbackText("没有解析到选项，答案：${effectiveAnswer.ifBlank { "未答上" }}"))
                } else {
                    val multi = q.type == WorkQuestion.TYPE_MULTI
                    val matched = matchedKeys(effectiveAnswer, options)
                    options.forEach { option ->
                        addOption(
                            text = "${option.key}. ${option.text}",
                            value = option.key,
                            checked = if (multi) {
                                option.key in matched
                            } else {
                                optionMatches(effectiveAnswer, option.key, option.text)
                            },
                            radio = !multi,
                            qid = q.qid,
                            optionText = option.text
                        )
                    }
                }
            }
            else -> {
                b.llDetailOptions.addView(
                    fallbackText("题型：${WorkQuestion.typeName(q.type)}；当前答案：${effectiveAnswer.ifBlank { "未答上" }}\n这类题暂不支持在这里改答案")
                )
            }
        }
    }

    private fun addOption(
        text: String,
        value: String,
        checked: Boolean,
        radio: Boolean,
        qid: String,
        optionText: String = ""
    ) {
        val view: View = if (radio) {
            MaterialRadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                isChecked = checked
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener { choose(qid, value) }
            }
        } else {
            MaterialCheckBox(this).apply {
                id = View.generateViewId()
                this.text = text
                isChecked = checked
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener {
                    val current = QuizRunManager.state.value.overrides[qid]
                        ?: QuizRunManager.state.value.questions.firstOrNull { it.qid == qid }?.answer
                        ?: ""
                    val keys = matchedKeys(current, optionCache[qid].orEmpty()).toMutableSet()
                    if (isChecked) keys.add(value) else keys.remove(value)
                    choose(qid, keys.sorted().joinToString(""))
                }
            }
        }
        b.llDetailOptions.addView(view)
        // 部分 Material 版本在 attach 前设置 isChecked 不一定会刷新选中态，attach 后再设一次。
        if (checked && view is CompoundButton) view.isChecked = true
    }

    private fun choose(qid: String, value: String) {
        QuizRunManager.setAnswerOverride(qid, value)
        render(QuizRunManager.state.value)
    }

    /** 把答案文本归一化：全角字母转半角、去空白、转大写 */
    private fun normalizeAnswer(value: String): String {
        val sb = StringBuilder(value.length)
        value.forEach { c ->
            when {
                c in 'Ａ'..'Ｚ' -> sb.append('A' + (c - 'Ａ'))
                c in 'ａ'..'ｚ' -> sb.append('A' + (c - 'ａ'))
                c.isWhitespace() -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString().uppercase()
    }

    /** 判断某个选项是否就是答案：兼容 "A"、"A."、"答案：A"、"A、选项原文"、选项原文等情况 */
    private fun optionMatches(answer: String, key: String, optionText: String): Boolean {
        val a = normalizeAnswer(answer)
        if (a.isBlank()) return false
        val k = normalizeAnswer(key)
        if (k.isBlank()) return false
        val letters = a.filter { it in 'A'..'Z' }
        if (letters == k) return true
        if (letters.length == 1 && letters[0].toString() == k) return true
        if (optionText.isNotBlank() && a.contains(normalizeAnswer(optionText))) return true
        if (a.contains("选项$k") || a.contains("答案$k")) return true
        return false
    }

    private fun matchedKeys(answer: String, options: List<WorkOption>): Set<String> {
        if (answer.isBlank() || options.isEmpty()) return emptySet()
        val byContent = options.filter { optionMatches(answer, it.key, it.text) }.map { it.key }.toSet()
        if (byContent.isNotEmpty()) return byContent
        return selectedKeys(answer).filter { key -> options.any { it.key.equals(key, true) } }.toSet()
    }

    private fun judgeValue(answer: String): String? {
        val a = normalizeAnswer(answer)
        if (a.isBlank()) return null
        return when {
            a.contains("错") || a.contains("不对") || a.contains("否") ||
                a.contains("FALSE") || a.contains("×") || a == "F" -> "false"
            a.contains("正确") || a.contains("对") || a.contains("是") ||
                a.contains("TRUE") || a.contains("√") || a == "T" -> "true"
            else -> null
        }
    }

    private fun selectedKeys(answer: String): Set<String> =
        normalizeAnswer(answer).filter { it in 'A'..'Z' }.map { it.toString() }.toSet()

    /**
     * 兜底拆题干里的内联选项：要求至少 A、B 两个连续字母开头，
     * 避免把题干里的普通 "A." 误判成选项。
     */
    private fun parseInlineOptions(stem: String): Pair<String, List<WorkOption>>? {
        val regex = Regex("""(?:^|[\s（(【\[、，。？?！!：:；;])([A-H])\s*[.．、)）]\s*""")
        val matches = regex.findAll(stem).toList()
        if (matches.size < 2) return null
        val options = matches.mapIndexed { i, m ->
            val start = m.range.last + 1
            val end = matches.getOrNull(i + 1)?.groups?.get(1)?.range?.first ?: stem.length
            WorkOption(m.groupValues[1], stem.substring(start, end).trim())
        }.filter { it.text.isNotBlank() }
        if (options.size < 2 || options.first().key != "A") return null
        val firstLetter = matches.first().groups[1]?.range?.first ?: return null
        val question = stem.substring(0, firstLetter).trim().ifBlank { stem }
        return question to options
    }

    private fun llmText(q: QuizQuestionLive, effectiveAnswer: String): String {
        if (q.source == "识别中") {
            return "题干已识别，正在查题库/大模型…"
        }
        val parts = mutableListOf<String>()
        when {
            q.llmRaw.isNotBlank() -> parts += "大模型输出：\n${q.llmRaw.trim()}"
            q.llmError.isNotBlank() -> parts += "大模型错误：${q.llmError.trim()}"
            q.source.isNotBlank() && q.source != "手动" -> parts += "本题来源：${q.source}"
        }
        if (q.answer.isNotBlank() && effectiveAnswer != q.answer) {
            parts += "原答案：${q.answer} → 手动改为：$effectiveAnswer"
        } else if (effectiveAnswer.isNotBlank()) {
            parts += "当前答案：$effectiveAnswer"
        } else {
            parts += "当前答案：未答上"
        }
        if (q.llmRaw.isBlank() && q.llmError.isBlank() && q.source.startsWith("LLM")) {
            parts += "（大模型未返回可展示的原文）"
        }
        return parts.joinToString("\n\n")
    }

    private fun updateWait(s: QuizRunManager.State, q: QuizQuestionLive) {
        // 题干刚解析出来、还在查题库/大模型：先把详情显示出来，不要显示成“已停止”
        if (s.running && s.searching && s.detail?.qid == q.qid) {
            ticker?.cancel()
            ticker = null
            tickerAnchor = ""
            b.tvDetailWait.text = "识别题目中…"
            return
        }
        val isCurrent = s.running && s.detail?.qid == q.qid && s.waitUntil > 0L
        if (!isCurrent) {
            ticker?.cancel()
            ticker = null
            tickerAnchor = ""
            b.tvDetailWait.text = when {
                !s.running && s.finished -> "已结束"
                !s.running -> "已停止"
                q.waitSeconds > 0 -> "本题停留 ${q.waitSeconds}s（已过）"
                else -> ""
            }
            return
        }

        val anchor = "${q.qid}|${s.waitUntil}|${s.waitLabel}"
        if (anchor == tickerAnchor) return
        tickerAnchor = anchor
        ticker?.cancel()
        val remaining = (s.waitUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        ticker = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val live = QuizRunManager.state.value
                if (!live.running || live.detail?.qid != q.qid) {
                    cancel()
                    return
                }
                val sec = ((millisUntilFinished + 999) / 1000).toInt()
                b.tvDetailWait.text = "${live.waitLabel.ifBlank { "等待" }} ${sec}s"
            }

            override fun onFinish() {
                val live = QuizRunManager.state.value
                if (live.running && live.detail?.qid == q.qid) {
                    b.tvDetailWait.text = "${live.waitLabel.ifBlank { "等待" }} 0s"
                }
            }
        }.start()
    }

    private fun fallbackText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        ticker?.cancel()
        ticker = null
        super.onDestroy()
    }
}
