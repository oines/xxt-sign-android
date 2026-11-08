package com.xxt.sign.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.ActivityQuizBinding
import com.xxt.sign.databinding.DialogQuizSettingsBinding
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyChapter
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.work.AndroidGlyphSource
import com.xxt.sign.work.AnswerProvider
import com.xxt.sign.work.FontBytes
import com.xxt.sign.work.FontDecoder
import com.xxt.sign.work.LlmClient
import com.xxt.sign.work.QuizConfig
import com.xxt.sign.work.QuizEngine
import com.xxt.sign.work.QuizResult
import com.xxt.sign.work.WorkApi
import com.xxt.sign.work.WorkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 章节测验页：扫描勾选章节里的测验 → 勾选 → 开始答题。
 *
 * 这里只做编排，协议在 `work` 包里；危险的默认值（只暂存）由 [Settings.quizStoreOnly] 控制。
 */
class QuizActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuizBinding
    private val adapter = QuizAdapter { item, checked -> onToggle(item, checked) }
    private val quizItems = mutableListOf<QuizItem>()
    private val chapters = mutableListOf<ChapterRef>()

    private var accountId = ""
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        accountId = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
        chapters.addAll(parseChapters(intent.getStringExtra(EXTRA_CHAPTERS).orEmpty()))

        binding.swAuto.isChecked = Settings.quizAutoMode
        binding.swAuto.setOnCheckedChangeListener { _, v ->
            Settings.quizAutoMode = v
            updateMode()
            if (v) {
                // 挂机要有人在后头跑：直接把前台巡检服务拉起来
                com.xxt.sign.MonitorService.start(this)
                LogStore.add("[测验] 挂机模式已打开：前台服务会自动巡检并交卷", true)
            }
        }

        binding.btnScan.setOnClickListener { scan() }
        binding.btnAnswer.setOnClickListener { answer() }
        binding.btnSettings.setOnClickListener { showSettings() }

        updateMode()
        binding.tvStatus.text = if (chapters.isEmpty()) {
            "没有拿到章节列表，先回刷课页勾选章节"
        } else {
            "已选 ${chapters.size} 个章节，点「扫描」找测验"
        }
        binding.tvEmpty.visibility = if (quizItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateMode() {
        val submit = if (Settings.quizStoreOnly) "只暂存" else "自动交卷"
        val auto = if (Settings.quizAutoMode) "挂机开启（巡检每 ${Settings.quizPatrolMinutes} 分钟）" else "挂机关闭，仅手动"
        binding.tvMode.text = "当前：$auto · $submit"
    }

    // ------------------------------------------------------------ 扫描

    private fun scan() {
        if (running) return toast("正在跑，先等一下")
        if (chapters.isEmpty()) return toast("没有章节可扫描")
        val account = AccountStore.get(accountId) ?: return toast("账号不在了")

        running = true
        setBusy(true)
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true

        lifecycleScope.launch {
            try {
                val (session, err) = withContext(Dispatchers.IO) { SignEngine.sessionFor(account) }
                if (session == null) {
                    binding.tvStatus.text = "登录失败：$err"
                    return@launch
                }
                val studyApi = StudyApi(session)
                val workApi = WorkApi(session)
                val found = mutableListOf<QuizItem>()

                chapters.forEachIndexed { index, ref ->
                    binding.tvStatus.text = "扫描 ${index + 1}/${chapters.size}：${ref.courseTitle} · ${ref.chapterTitle}"
                    val jobs = withContext(Dispatchers.IO) {
                        runCatching { studyApi.getWorkJobs(ref.toCourse(), ref.toChapter()) }
                            .getOrDefault(emptyList())
                    }
                    for (job in jobs) {
                        if (quizItems.any { it.job.key == job.key } || found.any { it.job.key == job.key }) continue
                        val page = withContext(Dispatchers.IO) {
                            runCatching { workApi.fetch(job) }.getOrNull()
                        }
                        val parsed = page?.let { runCatching { WorkParser.parse(it) }.getOrNull() }
                        val submitted = parsed?.submitted == true
                        found.add(
                            QuizItem(
                                job = job,
                                courseTitle = ref.courseTitle,
                                questionCount = parsed?.questionCount ?: 0,
                                state = if (submitted) "已交卷" else "未开始",
                                checked = !submitted,
                                submitted = submitted
                            )
                        )
                    }
                }

                quizItems.clear()
                quizItems.addAll(found)
                adapter.submit(quizItems.toList())
                binding.tvEmpty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                binding.tvStatus.text = if (found.isEmpty()) {
                    "这些章节里没有章节测验"
                } else {
                    "发现 ${found.size} 个测验，勾选后点「开始」"
                }
            } finally {
                running = false
                setBusy(false)
                binding.progress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------ 答题

    private fun setBusy(busy: Boolean) {
        binding.btnScan.isEnabled = !busy
        binding.btnAnswer.isEnabled = !busy
    }

    private fun answer() {
        if (running) return toast("正在跑，先等一下")
        val targets = quizItems.filter { it.checked && !it.submitted }
        if (targets.isEmpty()) return toast("先勾选要做的测验")
        val account = AccountStore.get(accountId) ?: return toast("账号不在了")

        // 挂机模式下不打断用户；手动模式才确认一次
        if (!Settings.quizStoreOnly && !Settings.quizAutoMode) {
            MaterialAlertDialogBuilder(this)
                .setTitle("要真的交卷吗？")
                .setMessage("设置里已关闭「只暂存」，这次会正式提交到学习通。\n\n建议先确认题库与字体反解都正常。")
                .setPositiveButton("继续") { _, _ -> runAnswering(account.id, targets) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        runAnswering(account.id, targets)
    }

    private fun runAnswering(accountId: String, targets: List<QuizItem>) {
        val account = AccountStore.get(accountId) ?: return
        running = true
        setBusy(true)
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true

        lifecycleScope.launch {
            try {
                val (session, err) = withContext(Dispatchers.IO) { SignEngine.sessionFor(account) }
                if (session == null) {
                    binding.tvStatus.text = "登录失败：$err"
                    return@launch
                }

                val config = QuizConfig(
                    storeOnly = Settings.quizStoreOnly,
                    perQuestionMinSec = Settings.quizPerQuestionMin,
                    perQuestionMaxSec = Settings.quizPerQuestionMax,
                    submitWaitMinSec = Settings.quizSubmitWaitMin,
                    submitWaitMaxSec = Settings.quizSubmitWaitMax,
                    gapSeconds = Settings.quizGapSeconds,
                    fontThreshold = Settings.quizFontThreshold,
                    hitThreshold = Settings.quizHitThreshold
                )
                val provider = AnswerProvider(
                    provider = Settings.quizProvider,
                    endpoint = Settings.quizEndpoint,
                    token = Settings.quizToken,
                    llm = buildLlm()
                )
                if (!provider.configured) {
                    LogStore.add("[测验] 没配题库，只会跳过或留空（题库/风控 里设置）", false)
                }

                val engine = QuizEngine(
                    api = WorkApi(session),
                    answers = provider,
                    config = config,
                    decoderFactory = { b64 ->
                        FontBytes.decode(b64)?.let { bytes ->
                            FontDecoder(
                                source = AndroidGlyphSource(this@QuizActivity, bytes),
                                size = 16,
                                threshold = Settings.quizFontThreshold
                            )
                        }
                    },
                    log = { msg, ok -> LogStore.add(msg, ok) }
                )

                targets.forEachIndexed { index, item ->
                    binding.tvStatus.text = "答题 ${index + 1}/${targets.size}：${item.title}"
                    val r = runCatching { engine.run(item.job) }
                        .getOrElse {
                            QuizResult(
                                item.title, false, !config.storeOnly, 0, item.questionCount,
                                "异常：${it.message ?: it.javaClass.simpleName}"
                            )
                        }
                    val pos = quizItems.indexOfFirst { it.job.key == item.job.key }
                    if (pos >= 0) {
                        val newState = when {
                            r.submitted && r.formal -> "已提交（${r.answered}/${r.total}）"
                            r.submitted -> "已暂存（${r.answered}/${r.total}）"
                            else -> r.message
                        }
                        quizItems[pos] = quizItems[pos].copy(state = newState)
                        adapter.update(pos, quizItems[pos])
                    }
                    LogStore.add(
                        "[测验] ${item.title} 汇总：题库 ${r.bankHits} / LLM ${r.llmHits} / 留空 ${r.blanks}",
                        r.submitted
                    )
                    binding.tvStatus.text = "${item.title}：${r.message}"
                }
                LogStore.add("[测验] 本轮结束，共处理 ${targets.size} 份", true)
            } finally {
                running = false
                setBusy(false)
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun onToggle(item: QuizItem, checked: Boolean) {
        val pos = quizItems.indexOfFirst { it.job.key == item.job.key }
        if (pos < 0) return
        quizItems[pos] = quizItems[pos].copy(checked = checked)
        adapter.update(pos, quizItems[pos])
    }

    // ------------------------------------------------------------ 设置

    private fun showSettings() {
        val v = DialogQuizSettingsBinding.inflate(layoutInflater)
        v.etProvider.setText(Settings.quizProvider)
        // 言溪 / 柠檬 的端点固定，类型填对了就不用再查一遍域名
        v.etEndpoint.setText(
            Settings.quizEndpoint.ifBlank { defaultEndpointFor(Settings.quizProvider) }
        )
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
        v.etLlmLimit.setText(Settings.quizLlmDailyLimit.toString())

        // 题库申请/部署入口：点一下跳系统浏览器
        bindLink(v.linkYanxi, "https://tk.enncy.cn/")
        bindLink(v.linkYanxiToken, "https://tk.enncy.cn/")
        bindLink(v.linkLemon, "https://lemtk.xyz/")
        bindLink(v.linkLemonToken, "https://lemtk.xyz/")
        bindLink(v.linkTikuAdapter, "https://github.com/DokiDoki1103/tikuAdapter")
        bindLink(v.linkTikuToken, "https://github.com/DokiDoki1103/tikuAdapter")
        bindLink(v.linkDeepSeek, "https://platform.deepseek.com/")

        MaterialAlertDialogBuilder(this)
            .setTitle("题库与风控")
            .setView(v.root)
            .setPositiveButton("保存") { _, _ ->
                val provider = v.etProvider.text?.toString()?.trim()?.lowercase().orEmpty()
                    .ifBlank { "off" }
                val endpoint = v.etEndpoint.text?.toString()?.trim().orEmpty()
                    .ifBlank { defaultEndpointFor(provider) }
                Settings.quizProvider = provider
                Settings.quizEndpoint = endpoint
                v.etEndpoint.setText(endpoint)
                Settings.quizToken = v.etToken.text?.toString()?.trim().orEmpty()
                Settings.quizLlmEnabled = v.swLlm.isChecked
                Settings.quizLlmKey = v.etLlmKey.text?.toString()?.trim().orEmpty()
                Settings.quizLlmBaseUrl = v.etLlmBaseUrl.text?.toString()?.trim().orEmpty()
                    .ifBlank { LlmClient.DEFAULT_BASE_URL }
                Settings.quizLlmModel = v.etLlmModel.text?.toString()?.trim().orEmpty()
                    .ifBlank { LlmClient.DEFAULT_MODEL }
                v.etLlmLimit.text?.toString()?.toIntOrNull()?.let { Settings.quizLlmDailyLimit = it }
                v.etQMin.text?.toString()?.toIntOrNull()?.let { Settings.quizPerQuestionMin = it }
                v.etQMax.text?.toString()?.toIntOrNull()?.let { Settings.quizPerQuestionMax = it }
                v.etSMin.text?.toString()?.toIntOrNull()?.let { Settings.quizSubmitWaitMin = it }
                v.etSMax.text?.toString()?.toIntOrNull()?.let { Settings.quizSubmitWaitMax = it }
                v.etGap.text?.toString()?.toIntOrNull()?.let { Settings.quizGapSeconds = it }
                v.etThreshold.text?.toString()?.toFloatOrNull()?.let { Settings.quizFontThreshold = it }
                Settings.quizStoreOnly = v.swStoreOnly.isChecked
                updateMode()
                toast(if (v.swStoreOnly.isChecked) "已保存（只暂存）" else "已保存（正式交卷已打开）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 工具

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** LLM 只在用户打开开关且填了 key 时才启用 */
    private fun buildLlm(): LlmClient? {
        if (!Settings.quizLlmEnabled) return null
        val key = Settings.quizLlmKey.trim()
        if (key.isEmpty()) return null
        return LlmClient(
            apiKey = key,
            baseUrl = Settings.quizLlmBaseUrl,
            model = Settings.quizLlmModel
        )
    }

    /** 言溪 / 柠檬的地址是固定的，类型写对就能直接用 */
    private fun defaultEndpointFor(provider: String): String = when (provider.trim().lowercase()) {
        "yanxi" -> "tk.enncy.cn"
        "lemon" -> "lemtk.xyz"
        "auto" -> "" // 内置源自带默认地址
        else -> ""
    }

    /** 点开跳系统浏览器，不内嵌 WebView */
    private fun bindLink(tv: TextView, url: String) {
        tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tv.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { toast("没有可用的浏览器") }
        }
    }

    /** 从刷课页带过来的章节清单 */
    data class ChapterRef(
        val courseId: String,
        val clazzId: String,
        val cpi: String,
        val courseTitle: String,
        val chapterId: String,
        val chapterTitle: String
    ) {
        fun toCourse() = StudyCourse(courseId, clazzId, cpi, courseTitle)
        fun toChapter() = StudyChapter(chapterId, chapterTitle, 0, false, false)
    }

    companion object {
        private const val EXTRA_ACCOUNT = "account_id"
        private const val EXTRA_CHAPTERS = "chapters"

        fun intent(ctx: Context, accountId: String, chaptersJson: String): Intent =
            Intent(ctx, QuizActivity::class.java)
                .putExtra(EXTRA_ACCOUNT, accountId)
                .putExtra(EXTRA_CHAPTERS, chaptersJson)

        /** 刷课页把勾选的章节序列化过来 */
        fun chaptersToJson(list: List<ChapterRef>): String {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("courseId", c.courseId)
                        .put("clazzId", c.clazzId)
                        .put("cpi", c.cpi)
                        .put("courseTitle", c.courseTitle)
                        .put("chapterId", c.chapterId)
                        .put("chapterTitle", c.chapterTitle)
                )
            }
            return arr.toString()
        }

        fun parseChapters(json: String): List<ChapterRef> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ChapterRef(
                    courseId = o.optString("courseId"),
                    clazzId = o.optString("clazzId"),
                    cpi = o.optString("cpi"),
                    courseTitle = o.optString("courseTitle"),
                    chapterId = o.optString("chapterId"),
                    chapterTitle = o.optString("chapterTitle")
                )
            }
        }.getOrDefault(emptyList())
    }
}
