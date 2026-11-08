package com.xxt.sign.ui

import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.xxt.sign.MonitorService
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.DialogQuizSettingsBinding
import com.xxt.sign.databinding.FragmentQuizBinding
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.study.StudyApi
import com.xxt.sign.study.StudyChapter
import com.xxt.sign.study.StudyCourse
import com.xxt.sign.study.StudyCourseStore
import com.xxt.sign.work.AndroidGlyphSource
import com.xxt.sign.work.AndroidReferenceFonts
import com.xxt.sign.work.AnswerProvider
import com.xxt.sign.work.FontBytes
import com.xxt.sign.work.FontDecoder
import com.xxt.sign.work.LlmClient
import com.xxt.sign.work.QuizConfig
import com.xxt.sign.work.QuizEngine
import com.xxt.sign.work.QuizResult
import com.xxt.sign.work.QuizListStore
import com.xxt.sign.work.QuizRuntime
import com.xxt.sign.work.QuizStatsStore
import com.xxt.sign.work.WorkApi
import com.xxt.sign.work.WorkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 章节测验页（底部导航「测验」）。
 *
 * 章节来源是刷课页勾选的章节（[Settings.studySelectedChapters] + 本地课程缓存），
 * 所以这里不需要额外传参；扫描只查这些章节，避免一次性打几百个请求。
 */
class QuizFragment : Fragment() {

    private var _b: FragmentQuizBinding? = null
    private val b get() = _b!!

    private val adapter = QuizAdapter({ item, checked -> onToggle(item, checked) }, { item -> retry(item) })
    private val quizItems = mutableListOf<QuizItem>()
    private val chapters = mutableListOf<ChapterRef>()

    private var running = false
    private var roundTotal = 0
    private var roundDone = 0
    private var roundHuman = 0

    /** 答案来源下拉：把枚举值藏起来，用户只看到人话 */
    private data class ProviderOption(val label: String, val value: String, val bank: Boolean)

    private val providerOptions = listOf(
        ProviderOption("不查题库（都留空）", "off", false),
        ProviderOption("内置免费题库（免配置）", "auto", true),
        ProviderOption("言溪题库", "yanxi", true),
        ProviderOption("柠檬题库", "lemon", true),
        ProviderOption("tikuAdapter（自建）", "tikuadapter", true),
        ProviderOption("纯大模型（不查题库）", "llm", false)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentQuizBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        b.swAuto.isChecked = Settings.quizAutoMode
        b.swAuto.setOnCheckedChangeListener { _, on ->
            Settings.quizAutoMode = on
            updateMode()
            if (on) {
                MonitorService.start(requireContext())
                LogStore.add("[测验] 挂机模式已打开：前台服务会自动巡检并交卷", true)
            }
        }

        b.btnScan.setOnClickListener { scan() }
        b.btnEmptyScan.setOnClickListener { scan() }
        b.btnAnswer.setOnClickListener { answer() }
        b.btnSettings.setOnClickListener { showSettings() }

        updateMode()
        restoreItems()
        refreshChapters()
        b.tvStats.text = "本轮：还没有开始"
        // 进页面自动扫一次，不用先点按钮
        if (chapters.isNotEmpty()) view.postDelayed({ if (isAdded && quizItems.isEmpty()) scan() }, 400)
    }

    override fun onResume() {
        super.onResume()
        refreshChapters()
        updateMode()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshChapters()
            updateMode()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    // ------------------------------------------------------------ 章节

    /**
     * 从刷课页的本地缓存 + 勾选里恢复章节；用户改过勾选后回到这里会自动刷新。
     */
    private fun refreshChapters() {
        if (_b == null) return
        val account = AccountStore.get(Settings.studyAccountId)
        if (account == null) {
            chapters.clear()
            b.tvEmpty.text = "还没有选刷课账号\n先到「账号」页勾选一个账号"
            updateEmpty()
            return
        }
        val snapshot = StudyCourseStore.load(account.id)
        val selected = Settings.studySelectedChapters
        val refs = mutableListOf<ChapterRef>()
        snapshot?.courses?.forEach { course ->
            val list = snapshot.chapters[course.key].orEmpty()
            list.filter { "${course.key}#${it.id}" in selected }.forEach { ch ->
                refs.add(
                    ChapterRef(
                        courseId = course.courseId,
                        clazzId = course.clazzId,
                        cpi = course.cpi,
                        courseTitle = course.title,
                        chapterId = ch.id,
                        chapterTitle = ch.title
                    )
                )
            }
        }
        chapters.clear()
        chapters.addAll(refs)
        b.tvEmpty.text = if (refs.isEmpty()) {
            "还没有可扫描的章节\n先到「刷课」页勾选章节，再回来点扫描"
        } else {
            "已选 ${refs.size} 个章节\n点下面按钮扫描章节测验"
        }
        updateEmpty()
    }

    /** 重启后恢复上次扫描结果与状态 */
    private fun restoreItems() {
        val account = AccountStore.get(Settings.studyAccountId) ?: return
        if (quizItems.isNotEmpty()) return
        val saved = QuizListStore.load(account.id)
        if (saved.isEmpty()) return
        quizItems.clear()
        saved.forEach { s ->
            quizItems.add(
                QuizItem(
                    job = com.xxt.sign.work.WorkJob(
                        courseId = s.courseId, clazzId = s.clazzId, cpi = s.cpi,
                        chapterId = s.chapterId, chapterTitle = s.chapterTitle,
                        jobId = s.jobId, workId = s.workId, enc = s.enc, ktoken = s.ktoken,
                        name = s.name, courseTitle = s.courseTitle
                    ),
                    courseTitle = s.courseTitle,
                    questionCount = s.questionCount,
                    state = s.state,
                    checked = s.checked,
                    submitted = s.submitted,
                    score = s.score,
                    time = s.time,
                    error = s.error
                )
            )
        }
        adapter.submit(quizItems.toList())
        updateEmpty()
    }

    private fun persistItems() {
        val account = AccountStore.get(Settings.studyAccountId) ?: return
        QuizListStore.save(
            account.id,
            quizItems.map { i ->
                QuizListStore.Saved(
                    courseId = i.job.courseId, clazzId = i.job.clazzId, cpi = i.job.cpi,
                    chapterId = i.job.chapterId, chapterTitle = i.job.chapterTitle,
                    jobId = i.job.jobId, workId = i.job.workId, enc = i.job.enc,
                    ktoken = i.job.ktoken, name = i.job.name, courseTitle = i.courseTitle,
                    questionCount = i.questionCount, state = i.state, checked = i.checked,
                    submitted = i.submitted, score = i.score, time = i.time, error = i.error
                )
            }
        )
    }

    private fun updateEmpty() {
        b.emptyBox.visibility = if (quizItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateMode() {
        val submit = if (Settings.quizStoreOnly) "只暂存" else "自动交卷"
        val auto = if (Settings.quizAutoMode) "挂机开启（每 ${Settings.quizPatrolMinutes} 分钟巡检）" else "挂机关闭，仅手动"
        b.tvMode.text = "当前：$auto · $submit"
    }

    // ------------------------------------------------------------ 扫描

    private fun scan() {
        if (running) return toast("正在跑，先等一下")
        refreshChapters()
        if (chapters.isEmpty()) return toast("先到「刷课」页勾选章节")
        val account = AccountStore.get(Settings.studyAccountId) ?: return toast("先在账号页选刷课账号")

        running = true
        setBusy(true)
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = true
        b.tvStep.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (session, err) = withContext(Dispatchers.IO) { SignEngine.sessionFor(account) }
                if (session == null) {
                    b.tvStatus.text = "登录失败：$err"
                    return@launch
                }
                val studyApi = StudyApi(session)
                val workApi = WorkApi(session)
                val found = mutableListOf<QuizItem>()

                chapters.forEachIndexed { index, ref ->
                    b.tvStatus.text = "扫描 ${index + 1}/${chapters.size}：${ref.courseTitle} · ${ref.chapterTitle}"
                    val jobs = withContext(Dispatchers.IO) {
                        runCatching { studyApi.getWorkJobs(ref.toCourse(), ref.toChapter()) }
                            .getOrDefault(emptyList())
                    }
                    for (job in jobs) {
                        val dupKey = "${ref.courseId}:${ref.chapterId}:${job.name}"
                        if (quizItems.any { "${it.job.courseId}:${it.job.chapterId}:${it.title}" == dupKey }) continue
                        if (found.any { "${it.job.courseId}:${it.job.chapterId}:${it.title}" == dupKey }) continue
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
                persistItems()
                updateEmpty()
                b.tvStatus.text = if (found.isEmpty()) {
                    "这些章节里没有章节测验"
                } else {
                    "发现 ${found.size} 个测验，勾选后点「开始」"
                }
            } finally {
                running = false
                setBusy(false)
                b.progress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------ 答题

    private fun setBusy(busy: Boolean) {
        b.btnScan.isEnabled = !busy
        b.btnAnswer.isEnabled = !busy
        b.btnEmptyScan.isEnabled = !busy
    }

    private fun answer() {
        if (running) return toast("正在跑，先等一下")
        val targets = quizItems.filter { it.checked && !it.submitted }
        if (targets.isEmpty()) return toast("先勾选要做的测验")
        val account = AccountStore.get(Settings.studyAccountId) ?: return toast("先在账号页选刷课账号")

        // 挂机模式下不打断用户；手动模式且会真交卷时才确认
        if (!Settings.quizStoreOnly && !Settings.quizAutoMode) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("要真的交卷吗？")
                .setMessage("设置里已关闭「只暂存」，这次会正式提交到学习通。\n\n建议先确认题库与字体反解都正常。")
                .setPositiveButton("继续") { _, _ -> runAnswering(account.id, targets) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        runAnswering(account.id, targets)
    }

    private fun retry(item: QuizItem) {
        if (running) return toast("正在跑，先等一下")
        val account = AccountStore.get(Settings.studyAccountId) ?: return toast("先在账号页选刷课账号")
        runAnswering(account.id, listOf(item))
    }

    private fun runAnswering(accountId: String, targets: List<QuizItem>) {
        val account = AccountStore.get(accountId) ?: return
        running = true
        setBusy(true)
        roundTotal = targets.size
        roundDone = 0
        roundHuman = 0
        b.progress.visibility = View.VISIBLE
        b.progress.isIndeterminate = true
        b.tvStep.visibility = View.VISIBLE
        b.tvStep.text = "准备中…"
        QuizRuntime.start("正在答题：准备中…")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (session, err) = withContext(Dispatchers.IO) { SignEngine.sessionFor(account) }
                if (session == null) {
                    b.tvStatus.text = "登录失败：$err"
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
                    LogStore.add("[测验] 没配题库也没配大模型，只会留空（题库/风控 里设置）", false)
                }

                val engine = QuizEngine(
                    api = WorkApi(session),
                    answers = provider,
                    config = config,
                    decoderFactory = { b64 ->
                        FontBytes.decode(b64)?.let { bytes ->
                            FontDecoder(
                                source = AndroidGlyphSource(requireContext(), bytes),
                                references = AndroidReferenceFonts.list(requireContext()),
                                size = 16,
                                fixedThreshold = Settings.quizFontThreshold
                            )
                        }
                    },
                    log = { msg, ok -> LogStore.add(msg, ok) },
                    onStep = { index, total ->
                        runOnUi { b.tvStep.text = "第 $index/$total 题正在作答…" }
                    }
                )

                targets.forEachIndexed { index, item ->
                    val pos = quizItems.indexOfFirst { it.job.key == item.job.key }
                    if (pos >= 0) {
                        quizItems[pos] = quizItems[pos].copy(running = true, error = "")
                        adapter.update(pos, quizItems[pos])
                    }
                    b.tvStatus.text = "答题 ${index + 1}/${targets.size}：${item.title}"

                    val r = runCatching { engine.run(item.job) }
                        .getOrElse {
                            QuizResult(
                                item.title, false, !config.storeOnly, 0, item.questionCount,
                                "异常：${it.message ?: it.javaClass.simpleName}"
                            )
                        }

                    if (pos >= 0) {
                        val newState = when {
                            r.submitted && r.formal -> "已提交（${r.answered}/${r.total}）"
                            r.submitted -> "已暂存（${r.answered}/${r.total}）"
                            else -> r.message
                        }
                        quizItems[pos] = quizItems[pos].copy(
                            running = false,
                            state = newState,
                            score = r.score,
                            time = now(),
                            submitted = r.submitted,
                            error = if (r.submitted) "" else r.message
                        )
                        adapter.update(pos, quizItems[pos])
                        persistItems()
                    }

                    if (r.submitted) {
                        roundDone++
                        QuizStatsStore.addQuiz(r.answered, r.total)
                    }
                    if (r.needsHuman) roundHuman++
                    QuizRuntime.update("正在答题：${item.title}")
                    updateRound(bank = null)
                    b.tvStatus.text = "${item.title}：${r.message}"
                    LogStore.add(
                        "[测验] ${item.title} 汇总：题库 ${r.bankHits} / LLM ${r.llmHits} / 留空 ${r.blanks}" +
                            if (r.fontReference.isNotBlank()) {
                                "｜字体 ${r.fontReference} 平均 ${"%.2f".format(r.fontMean)} 最低 ${"%.2f".format(r.fontMin)}"
                            } else "",
                        r.submitted
                    )
                }
                b.tvStep.text = "本轮结束"
                QuizRuntime.stop()
                LogStore.add("[测验] 本轮结束，共处理 ${targets.size} 份", true)
            } finally {
                running = false
                setBusy(false)
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun updateRound(bank: Int?) {
        b.tvStats.text = buildString {
            append("本轮：已交 ").append(roundDone).append('/').append(roundTotal)
            if (roundHuman > 0) append(" · 需人工 ").append(roundHuman)
            if (bank != null) append(" · 题库命中 ").append(bank)
        }
    }

    // ------------------------------------------------------------ 设置

    private fun showSettings() {
        val v = DialogQuizSettingsBinding.inflate(layoutInflater)
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
        bindLinks(v.tvLinks)

        MaterialAlertDialogBuilder(requireContext())
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
                v.etQMin.text?.toString()?.toIntOrNull()?.let { Settings.quizPerQuestionMin = it }
                v.etQMax.text?.toString()?.toIntOrNull()?.let { Settings.quizPerQuestionMax = it }
                v.etSMin.text?.toString()?.toIntOrNull()?.let { Settings.quizSubmitWaitMin = it }
                v.etSMax.text?.toString()?.toIntOrNull()?.let { Settings.quizSubmitWaitMax = it }
                v.etGap.text?.toString()?.toIntOrNull()?.let { Settings.quizGapSeconds = it }
                v.etThreshold.text?.toString()?.toFloatOrNull()?.let { Settings.quizFontThreshold = it }
                Settings.quizStoreOnly = v.swStoreOnly.isChecked
                updateMode()
                toast(if (v.swStoreOnly.isChecked) "已保存（只暂存）" else "已保存（自动交卷）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 申请入口收成一行小字，点哪段跳哪 */
    private fun bindLinks(tv: TextView) {
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
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure { toast("没有可用的浏览器") }
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = 0xFF1A73E8.toInt()
                    ds.isUnderlineText = true
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(0xFF1A73E8.toInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cursor = end + 3 // " · "
        }
        tv.text = ss
        tv.movementMethod = LinkMovementMethod.getInstance()
    }

    // ------------------------------------------------------------ 工具

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun runOnUi(block: () -> Unit) {
        val act = activity ?: return
        act.runOnUiThread { if (isAdded) runCatching(block) }
    }

    private fun onToggle(item: QuizItem, checked: Boolean) {
        val pos = quizItems.indexOfFirst { it.job.key == item.job.key }
        if (pos < 0) return
        quizItems[pos] = quizItems[pos].copy(checked = checked)
        adapter.update(pos, quizItems[pos])
    }

    /** LLM 只在开关打开且填了 key 时启用 */
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

    /** 言溪 / 柠檬的地址固定，类型写对就能直接用 */
    private fun defaultEndpointFor(provider: String): String = when (provider.trim().lowercase()) {
        "yanxi" -> "tk.enncy.cn"
        "lemon" -> "lemtk.xyz"
        else -> ""
    }

    /** 点开跳系统浏览器 */
    private fun bindLink(tv: TextView, url: String) {
        tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tv.setOnClickListener {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { toast("没有可用的浏览器") }
        }
    }

    /** 刷课页缓存里的章节引用 */
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
}
