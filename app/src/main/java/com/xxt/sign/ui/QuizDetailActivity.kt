package com.xxt.sign.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.xxt.sign.databinding.ActivityQuizDetailBinding
import com.xxt.sign.work.QuizQuestionLive
import com.xxt.sign.work.QuizRunManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityQuizDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.btnDetailPrev.setOnClickListener { move(-1) }
        b.btnDetailNext.setOnClickListener { move(1) }

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
            b.tvDetailHint.visibility = View.GONE
            b.tvDetailMeta.text = ""
            b.tvDetailWait.text = ""
            b.tvDetailIndex.text = "0/0"
            b.btnDetailPrev.isEnabled = false
            b.btnDetailNext.isEnabled = false
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

        b.tvDetailStem.text = q.stem
        buildOptions(q, effectiveAnswer)
        b.tvDetailLlm.text = llmText(q, effectiveAnswer)
        updateWait(s, q)
    }

    private fun buildOptions(q: QuizQuestionLive, effectiveAnswer: String) {
        b.llDetailOptions.removeAllViews()
        when (q.type) {
            WorkQuestion.TYPE_JUDGE -> {
                addOption("正确", "true", effectiveAnswer, radio = true, qid = q.qid)
                addOption("错误", "false", effectiveAnswer, radio = true, qid = q.qid)
            }
            WorkQuestion.TYPE_SINGLE, WorkQuestion.TYPE_MULTI -> {
                if (q.options.isEmpty()) {
                    b.llDetailOptions.addView(fallbackText("没有解析到选项，答案：${effectiveAnswer.ifBlank { "未答上" }}"))
                } else {
                    val multi = q.type == WorkQuestion.TYPE_MULTI
                    q.options.forEach { option ->
                        addOption(
                            text = "${option.key}. ${option.text}",
                            value = option.key,
                            effectiveAnswer = effectiveAnswer,
                            radio = !multi,
                            qid = q.qid
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

    private fun addOption(text: String, value: String, effectiveAnswer: String, radio: Boolean, qid: String) {
        val checked = if (radio) {
            effectiveAnswer == value
        } else {
            value in selectedKeys(effectiveAnswer)
        }
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
                    val keys = selectedKeys(current).toMutableSet()
                    if (isChecked) keys.add(value) else keys.remove(value)
                    choose(qid, keys.sorted().joinToString(""))
                }
            }
        }
        b.llDetailOptions.addView(view)
    }

    private fun choose(qid: String, value: String) {
        QuizRunManager.setAnswerOverride(qid, value)
        render(QuizRunManager.state.value)
    }

    private fun selectedKeys(answer: String): Set<String> =
        answer.filter { it in 'A'..'Z' }.map { it.toString() }.toSet()

    private fun llmText(q: QuizQuestionLive, effectiveAnswer: String): String {
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
        val isCurrent = s.running && s.detail?.qid == q.qid && s.waitUntil > 0L
        if (!isCurrent) {
            ticker?.cancel()
            ticker = null
            tickerAnchor = ""
            b.tvDetailWait.text = when {
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
