package com.xxt.sign.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.ActivityCourseBinding
import com.xxt.sign.study.CourseAuditStore
import com.xxt.sign.study.CourseRunQueue
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.study.StudyCourseStore
import kotlinx.coroutines.launch

/**
 * 课程页：一个 开始/暂停 按钮 + 进度，别的都不放。
 *
 * 文案只有两种字：开始 / 暂停。状态行只说人话（正在刷视频、正在做测验、等你到教室签个到）。
 */
class CourseActivity : AppCompatActivity() {

    private lateinit var b: ActivityCourseBinding
    private var course: StudyCourse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCourseBinding.inflate(layoutInflater)
        setContentView(b.root)

        val key = intent.getStringExtra(EXTRA_COURSE_KEY).orEmpty()
        val account = AccountStore.get(Settings.studyAccountId)
        course = account?.let { StudyCourseStore.load(it.id) }
            ?.courses?.firstOrNull { it.key == key }

        b.toolbar.setNavigationOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        val c = course
        if (c == null) {
            b.toolbar.title = "没找到这门课"
            b.btnToggle.isEnabled = false
            return
        }
        b.toolbar.title = c.title
        if (AccountStore.get(Settings.studyAccountId) == null) {
            // 没有账号就别让按钮看起来能点
            b.tvStateLine.text = "先去「账号」页加一个账号"
            b.btnToggle.isEnabled = false
        } else {
            b.btnToggle.setOnClickListener { CourseRunQueue.toggle(c) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CourseRunQueue.states.collect { render() }
            }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = course ?: return
        val run = CourseRunQueue.stateOf(c.key)
        val audit = CourseAuditStore.loadCurrent().firstOrNull { it.courseId == c.courseId }

        val done = run?.donePoints ?: audit?.doneTaskPoints ?: 0
        val total = run?.totalPoints ?: audit?.taskPoints ?: 0

        // 一行就够：在跑就写在跑什么；没跑就只说进度，不再多写一句「没在跑」
        val runningText = when {
            run != null && run.running -> run.message
            run != null && run.phase == CourseRunQueue.Phase.STUCK -> run.message
            else -> ""
        }
        val progressText = if (total > 0) "任务点 $done/$total" else ""
        val line = listOf(runningText, progressText).filter { it.isNotBlank() }.joinToString(" · ")
        b.tvStateLine.visibility = if (line.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvStateLine.text = line

        b.tvProgressText.visibility = android.view.View.GONE
        b.progress.visibility = if (total > 0) android.view.View.VISIBLE else android.view.View.GONE
        b.progress.setProgressCompat(if (total > 0) done * 100 / total else 0, false)

        val needs = run?.needsYou.orEmpty().ifBlank { if ((audit?.humanPending ?: 0) > 0) "有要你本人处理的事" else "" }
        b.tvNeedsYou.visibility = if (needs.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        b.tvNeedsYou.text = needs

        val running = run?.running == true || run?.phase == CourseRunQueue.Phase.QUEUED
        b.btnToggle.text = if (running) "暂停" else "开始"
    }

    companion object {
        const val EXTRA_COURSE_KEY = "course_key"
    }
}
