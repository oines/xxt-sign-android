package com.xxt.sign.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.xxt.sign.databinding.ActivityStudyDetailBinding
import com.xxt.sign.study.StudyManager
import com.xxt.sign.study.StudyTaskItem
import com.xxt.sign.study.StudyTaskStatus
import kotlinx.coroutines.launch

class StudyDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStudyDetailBinding
    private val adapter = StudyTaskAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStudyDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    StudyManager.tasks.collect { tasks ->
                        adapter.submit(tasks)
                        binding.tvEmpty.visibility = if (tasks.isEmpty()) {
                            android.view.View.VISIBLE
                        } else {
                            android.view.View.GONE
                        }
                        updateSummary(tasks)
                    }
                }
                launch {
                    StudyManager.state.collect {
                        updateSummary(StudyManager.tasks.value)
                    }
                }
            }
        }
    }

    private fun updateSummary(tasks: List<StudyTaskItem>) {
        val state = StudyManager.state.value
        if (tasks.isEmpty()) {
            binding.tvSummary.text = state.status
            return
        }
        val done = tasks.count { it.status == StudyTaskStatus.DONE }
        val failed = tasks.count { it.status == StudyTaskStatus.FAILED }
        val running = tasks.count { it.status == StudyTaskStatus.RUNNING }
        binding.tvSummary.text = buildString {
            append("视频完成 $done/${tasks.size}")
            if (running > 0) append(" · 进行中 $running")
            if (failed > 0) append(" · 失败 $failed")
            append('\n')
            append(state.status)
        }
    }
}
