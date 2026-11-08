package com.xxt.sign.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.xxt.sign.MonitorService
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import com.xxt.sign.core.SignEvents
import com.xxt.sign.databinding.FragmentHomeBinding
import com.xxt.sign.study.CourseAuditStore
import com.xxt.sign.study.CourseRunQueue
import com.xxt.sign.study.StudyCourseStore
import com.xxt.sign.work.QuizHandledStore
import kotlinx.coroutines.launch

/**
 * 首页 = 课程列表 + 要你处理的动态。
 *
 * 没有全局开关：用户点一门课进去按「开始」才跑，不点就什么都不跑。
 */
class HomeFragment : Fragment() {

    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!

    private var refreshing = false

    private val adapter = HomeCourseAdapter { course ->
        startActivity(
            Intent(requireContext(), CourseActivity::class.java)
                .putExtra(CourseActivity.EXTRA_COURSE_KEY, course.key)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.rvCourses.layoutManager = LinearLayoutManager(requireContext())
        b.rvCourses.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CourseRunQueue.states.collect { renderCourses() } }
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
        renderCourses()
        renderHuman()
    }

    private fun renderCourses() {
        if (_b == null) return
        val account = AccountStore.get(Settings.studyAccountId)
        val snapshot = account?.let { StudyCourseStore.load(it.id) }
        val courses = snapshot?.courses.orEmpty()
        val audits = account?.let { CourseAuditStore.load(it.id) }.orEmpty()
        val runs = CourseRunQueue.states.value

        val active = runs.values.filter { it.running }
        val stuck = runs.values.firstOrNull { it.phase == CourseRunQueue.Phase.STUCK }
        val stateText = when {
            account == null -> "还没登录账号"
            active.isNotEmpty() -> active.first().message
            stuck != null -> stuck.message
            else -> ""
        }
        b.tvState.visibility = if (stateText.isBlank()) View.GONE else View.VISIBLE
        b.tvState.text = stateText
        // 不解释「怎么用」：能看见课程列表就够了
        b.tvStatus.visibility = View.GONE

        adapter.submit(courses, audits, runs)
        // 新装/换账号第一次进来：自己刷一次，不用用户点右上角
        if (courses.isEmpty() && account != null && !refreshing) {
            refreshing = true
            (activity as? com.xxt.sign.MainActivity)?.refreshCoursesSilently()
        }
        if (courses.isNotEmpty()) refreshing = false
        b.tvCoursesEmpty.visibility = if (courses.isEmpty()) View.VISIBLE else View.GONE
        b.rvCourses.visibility = if (courses.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 「要你处理」：签到要到教室、人脸、测验卡住、作业要自己交 */
    private fun renderHuman() {
        if (_b == null) return
        b.llHuman.removeAllViews()
        val accountId = Settings.studyAccountId
        val items = mutableListOf<Pair<String, String>>()

        val signCard = SignEvents.pending(requireContext())
        if (signCard != null) items.add(signCard.title to signCard.detail)

        val pending = if (accountId.isBlank()) emptySet() else QuizHandledStore.humanKeys(accountId)
        if (pending.isNotEmpty()) {
            items.add("有 ${pending.size} 份测验要你本人过一下" to "可能要人脸或滑块")
        }
        val audits = CourseAuditStore.loadCurrent()
        val needMe = audits.filter { it.humanPending > 0 }
        if (needMe.isNotEmpty()) {
            val head = needMe.first()
            val gap = head.gaps.firstOrNull { it.kind == com.xxt.sign.study.CourseAudit.Kind.HUMAN }
            items.add(
                "${needMe.size} 门课里有它做不了的事" to
                    (gap?.let { "${head.courseTitle} · ${it.chapterTitle}" }
                        ?: "作业、考试、要上传的东西得你自己交")
            )
        }

        b.tvHumanTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        b.llHuman.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.take(3).forEach { (title, sub) ->
            val tv = TextView(requireContext()).apply {
                text = "$title\n$sub"
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.primary))
                setPadding(0, 10, 0, 10)
            }
            b.llHuman.addView(tv)
        }

        // 后台巡检常驻：有签到就自己冒出来，不需要用户点
        if (accountId.isNotBlank() && !MonitorService.running) {
            MonitorService.start(requireContext())
        }
    }
}
