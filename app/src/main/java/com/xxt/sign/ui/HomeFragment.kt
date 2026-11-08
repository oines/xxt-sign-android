package com.xxt.sign.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xxt.sign.MainActivity
import com.xxt.sign.MonitorService
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.FragmentHomeBinding
import com.xxt.sign.study.StudyManager
import com.xxt.sign.work.QuizHandledStore
import com.xxt.sign.work.QuizRuntime
import com.xxt.sign.work.QuizStatsStore
import kotlinx.coroutines.launch

/**
 * 首页 = 结果面板。
 *
 * 用户只有一个诉求：把这些没营养的活交出去，自己不管。
 * 所以首屏只回答两件事：做完了多少 / 还有哪些必须本人出手。
 * 课程、章节、测验明细都在二级页面。
 */
class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.swAuto.isChecked = Settings.quizAutoMode
        b.swAuto.setOnCheckedChangeListener { _, on ->
            if (on && !Settings.quizAfkWarned) {
                // 第一次打开：说清楚它会替你交卷，确认一次以后不再问
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("打开挂机后，它会替你交卷")
                    .setMessage(
                        "开启后 App 会自动做视频、章节测验和签到，测验会直接提交到学习通，不再逐份问你。\n\n" +
                            "不放心的话，可以先到「测验 → 设置」里打开「只暂存」，确认没问题再关掉。"
                    )
                    .setPositiveButton("知道了，开始") { _, _ ->
                        Settings.quizAfkWarned = true
                        Settings.quizAutoMode = true
                        MonitorService.start(requireContext())
                        render()
                    }
                    .setNegativeButton("先不用", null)
                    .setOnCancelListener { render() }
                    .show()
                b.swAuto.isChecked = false
                return@setOnCheckedChangeListener
            }
            Settings.quizAutoMode = on
            if (on) MonitorService.start(requireContext()) else MonitorService.stop(requireContext())
            render()
        }
        b.btnStop.setOnClickListener {
            MonitorService.stop(requireContext())
            QuizRuntime.stop()
            render()
        }
        b.btnQuiz.setOnClickListener { (activity as? MainActivity)?.openTab(R.id.nav_quiz) }
        b.btnLog.setOnClickListener { (activity as? MainActivity)?.openLogs() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { QuizRuntime.state.collect { render() } }
                launch { StudyManager.state.collect { render() } }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    private fun render() {
        if (_b == null) return
        val auto = Settings.quizAutoMode
        val quiz = QuizRuntime.state.value
        val study = StudyManager.state.value

        val running = quiz.running || study.running
        b.tvState.text = when {
            !auto -> "未开始"
            running -> "挂机中"
            else -> "挂机中 · 待命中"
        }
        b.swAuto.isChecked = auto
        // 「停止」常驻：任何会写数据的动作都要能一键停下
        b.btnStop.visibility = View.VISIBLE
        b.btnStop.isEnabled = running || MonitorService.running || auto

        b.tvStatus.text = when {
            quiz.running && quiz.text.isNotBlank() -> quiz.text
            study.running && study.current.isNotBlank() -> "正在刷课：${study.current}"
            study.running -> "正在刷课…"
            auto -> "正在巡检，有作业会自己处理"
            else -> "加好账号后打开上面的开关就开始"
        }

        val papers = QuizStatsStore.papers()
        val answered = QuizStatsStore.answered()
        b.tvSummary.text = if (papers == 0) {
            "本周：还没开始"
        } else {
            "本周：交了 $papers 份测验，答上 $answered 题"
        }

        renderHuman(auto)
    }

    /** 「需要你本人」最多 3 条：人脸/滑块、位置签到要到教室 */
    private fun renderHuman(auto: Boolean) {
        b.llHuman.removeAllViews()
        val accountId = Settings.studyAccountId
        val items = mutableListOf<Pair<String, String>>()

        val pending = if (accountId.isBlank()) emptySet() else QuizHandledStore.humanKeys(accountId)
        if (pending.isNotEmpty()) {
            items.add("有 ${pending.size} 份测验要你本人过一下" to "可能需要人脸或滑块，点开测验页处理")
        }
        if (Settings.latitude.isBlank() || Settings.longitude.isBlank()) {
            items.add("位置签到要你在教室里" to "到教室后打开 App，会自动用当前定位")
        }
        if (!auto) {
            items.add("挂机还没打开" to "打开首页开关，剩下的交给它")
        }

        b.tvHumanEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.take(3).forEach { (title, sub) ->
            val tv = TextView(requireContext()).apply {
                text = "$title\n$sub"
                setTextColor(requireContext().getColor(R.color.primary))
                textSize = 13f
                setPadding(0, 12, 0, 12)
                setOnClickListener { (activity as? MainActivity)?.openTab(R.id.nav_quiz) }
            }
            b.llHuman.addView(tv)
        }
    }
}
