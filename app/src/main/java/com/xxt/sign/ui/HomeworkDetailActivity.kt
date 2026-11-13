package com.xxt.sign.ui

import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.xxt.sign.R
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.Settings
import com.xxt.sign.databinding.ActivityHomeworkDetailBinding
import com.xxt.sign.sign.SignEngine
import com.xxt.sign.work.AnswerProvider
import com.xxt.sign.work.HomeworkAi
import com.xxt.sign.work.HomeworkApi
import com.xxt.sign.work.HomeworkCourse
import com.xxt.sign.work.HomeworkParser
import com.xxt.sign.work.HomeworkQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 作业题目详情：可以选题、暂存、交卷，也可以问 AI。
 *
 * 打开链路：task-work 状态页 → doHomeWork 地址 → 按 index 一页一题地取。
 *
 * 写请求只有两种，而且都**只会在用户自己点按钮时**发生：
 *  - 「暂存本题」：POST `doNormalHomeWorkSubmit?tempSave=true`
 *  - 「交卷」：先暂存所有已作答的题，再 POST `tempSave=false`
 *
 * 本类不会自动提交，也不会替用户答题：AI 只在用户点「问 AI」（或自己打开
 * 「自动 AI 建议」开关）时查询，仅供用户参考。
 */
class HomeworkDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeworkDetailBinding

    private var api: HomeworkApi? = null
    private var provider: AnswerProvider? = null
    private var doHomeWorkUrl: String? = null

    private var total = 1
    private var index = 0
    private val questions = HashMap<Int, HomeworkQuestion>()

    /** qid -> 用户选的答案（"A" 或 "AB"），只存在内存里，点暂存/交卷才写服务器 */
    private val answers = HashMap<String, String>()

    /** 已经暂存成功的题目 qid，交卷时不用重复暂存 */
    private val savedQids = HashSet<String>()

    /** 服务端返回的最新作答 id（workRelationAnswerId），暂存后会变 */
    private var answerId = ""

    /** 每题只查一次 AI：qid -> 已拿到的结果（含「没查到」），切回该题时直接复用 */
    private val aiCache = HashMap<String, AiResult>()

    private data class AiResult(val answer: String, val source: String, val raw: String)

    private var loadJob: Job? = null
    private var askJob: Job? = null
    private var workJob: Job? = null
    private var typeScanJob: Job? = null
    private var seq = 0
    private var askSeq = 0
    private var submitting = false
    private var submitted = false

    /** 已交卷的作业：task-work 给的入口带 status=，只能查看，不能再暂存/交卷 */
    private var reviewOnly = false

    /** 整份作业是否全部为选择题；只有全选择题才允许交卷（否则会漏掉填空/简答） */
    private var allChoice = false

    /** 列表是否已经扫描过题型（没扫描时不敢让用户交卷，避免半卷提交） */
    private var typeScanned = false

    private val courseTitle: String by lazy { intent.getStringExtra(EXTRA_COURSE_TITLE).orEmpty() }
    private val workTitle: String by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
    private val workId: String by lazy { intent.getStringExtra(EXTRA_WORK_ID).orEmpty() }
    private val courseId: String by lazy { intent.getStringExtra(EXTRA_COURSE_ID).orEmpty() }
    private val classId: String by lazy { intent.getStringExtra(EXTRA_CLASS_ID).orEmpty() }
    private val cpi: String by lazy { intent.getStringExtra(EXTRA_CPI).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeworkDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        allChoice = intent.getBooleanExtra(EXTRA_ALL_CHOICE, false)
        typeScanned = intent.getBooleanExtra(EXTRA_SCANNED, false)
        b.tvDetailCourse.text = courseTitle
        b.tvDetailTitle.text = workTitle.ifBlank { getString(R.string.title_homework_detail) }

        b.btnPrev.setOnClickListener { go(-1) }
        b.btnNext.setOnClickListener { go(1) }
        b.btnAsk.setOnClickListener { askAi(questions[index], forced = true) }
        b.btnSave.setOnClickListener { saveCurrent() }
        b.btnSubmit.setOnClickListener { confirmSubmit() }

        b.switchAutoAi.isChecked = Settings.homeworkAutoAi
        b.switchAutoAi.setOnCheckedChangeListener { _, checked ->
            Settings.homeworkAutoAi = checked
            if (checked) {
                askAi(questions[index], forced = false)
            } else {
                resetAiHint()
            }
        }

        provider = HomeworkAi.provider()
        load()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        askJob?.cancel()
        workJob?.cancel()
        typeScanJob?.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 打开作业

    private fun load() {
        if (workId.isBlank() || courseId.isBlank()) {
            status("缺少作业参数，请返回重进")
            return
        }
        b.detailProgress.visibility = View.VISIBLE
        status("正在打开作业…")
        loadJob = lifecycleScope.launch {
            try {
                val acc = AccountStore.get(Settings.studyAccountId)
                if (acc == null) {
                    status("账号未选择，请先到「账号」页勾选")
                    b.detailProgress.visibility = View.GONE
                    return@launch
                }
                val (session, err) = SignEngine.sessionFor(acc)
                if (session == null) {
                    status("账号不可用：${err.ifBlank { "请重新登录" }}")
                    b.detailProgress.visibility = View.GONE
                    return@launch
                }
                val hw = HomeworkApi(session)
                api = hw
                val resolved = hw.resolve(
                    HomeworkCourse(courseId, classId, cpi, courseTitle),
                    workId
                )
                val url = resolved.doHomeWorkUrl
                if (url == null) {
                    status(resolved.message.ifBlank { "没有找到作答入口" })
                    b.detailProgress.visibility = View.GONE
                    return@launch
                }
                doHomeWorkUrl = url
                answerId = Regex("workAnswerId=(\\d+)").find(url)?.groupValues?.get(1).orEmpty()
                reviewOnly = Regex("[?&]status=").containsMatchIn(url)
                Log.i(TAG, "resolved reviewOnly=$reviewOnly answerId=$answerId url=${url.take(160)}")
                showQuestion(0)
                if (!typeScanned && !reviewOnly) scanTypes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("打开失败：${e.message ?: e.javaClass.simpleName}")
                b.detailProgress.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------ 题目

    /**
     * 扫一遍题型：只有整卷都是选择题时才允许交卷。
     * 列表页已经扫过的（EXTRA_SCANNED）就不再扫；已交卷的查看页没有逐题页面，也不扫。
     */
    private fun scanTypes() {
        val hw = api ?: return
        val url = doHomeWorkUrl ?: return
        typeScanJob?.cancel()
        typeScanJob = lifecycleScope.launch {
            try {
                val scan = hw.scanChoices(url)
                typeScanned = true
                allChoice = scan.allChoice
                Log.i(
                    TAG,
                    "scan types choice=${scan.choiceCount} total=${scan.total} " +
                        "allChoice=${scan.allChoice} pages=${scan.pagesRead}"
                )
                questions[index]?.let { render(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "scan types failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun go(delta: Int) {
        if (submitting) return
        val target = index + delta
        if (target < 0 || target >= total) return
        showQuestion(target)
    }

    private fun showQuestion(target: Int) {
        Log.i(TAG, "showQuestion target=$target current=$index total=$total cached=${questions.containsKey(target)}")
        val cached = questions[target]
        if (cached != null) {
            index = target
            render(cached)
            afterRender(cached)
            return
        }
        val url = doHomeWorkUrl ?: return
        val hw = api ?: return
        val mine = ++seq
        askJob?.cancel()
        b.detailProgress.visibility = View.VISIBLE
        updateButtons()
        status("正在读取第 ${target + 1} 题…")
        loadJob = lifecycleScope.launch {
            try {
                val html = hw.questionHtml(url, target)
                if (mine != seq) return@launch
                if (html == null) {
                    status("读取题目失败（网络或登录态异常），请再点一次「下一题」")
                    b.detailProgress.visibility = View.GONE
                    updateButtons()
                    return@launch
                }
                Log.i(TAG, "fetch target=$target len=${html.length}")
                val q = HomeworkParser.parseQuestion(html, workId, target)
                if (q == null) {
                    status("没解析出题目（页面可能已变化）")
                    b.detailProgress.visibility = View.GONE
                    updateButtons()
                    return@launch
                }
                Log.i(
                    TAG,
                    "parsed target=$target qid=${q.qid} number=${q.number} total=${q.total} " +
                        "type=${q.type} pageIndex=${q.pageIndex} answerField=${q.answerField} " +
                        "fields=${q.formFields.size} stem=${q.stem.take(24)}"
                )
                val previous = questions[index]
                val previousIndex = index
                questions[target] = q
                total = q.total.coerceAtLeast(total).coerceAtLeast(1)
                index = target
                render(q)
                if (previous != null && previousIndex != target && previous.qid == q.qid) {
                    // 服务端没按 index 换题：把情况显示出来，方便用户重试
                    status("服务端返回的仍是上一题，点「下一题」重试")
                }
                afterRender(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("读取失败：${e.message ?: e.javaClass.simpleName}")
                b.detailProgress.visibility = View.GONE
                updateButtons()
            }
        }
    }

    private fun render(q: HomeworkQuestion) {
        b.detailProgress.visibility = View.GONE
        status("")
        // 只读判断：URL 带 status= 只是线索；真正能提交的页面一定带提交表单（隐藏字段）。
        if (reviewOnly && q.formFields.isNotEmpty()) {
            Log.i(TAG, "page has submit form, treat as editable (status= ignored)")
            reviewOnly = false
        } else if (!reviewOnly && q.formFields.isEmpty()) {
            Log.i(TAG, "page has no submit form, treat as read-only")
            reviewOnly = true
        }
        val suffix = when {
            submitted -> " · 已交卷"
            reviewOnly -> " · 已交卷（只读）"
            else -> ""
        }
        // 题型判断：看到一道非选择题就说明整卷不能在这里交；单题作业则看这一题
        if (q.isChoice) {
            if (!typeScanned && q.total <= 1) {
                allChoice = true
                typeScanned = true
            }
        } else {
            allChoice = false
            typeScanned = true
        }
        b.tvQuestionMeta.text =
            "第 ${q.number}/${q.total.coerceAtLeast(total)} 题 · ${q.typeName}$suffix"
        b.tvQuestionStem.text = q.stem.ifBlank { "（题干为空）" }
        buildOptions(q)
        b.tvHint.text = when {
            reviewOnly ->
                "这份作业已经交过卷，App 里只能查看，不能修改或再次提交。" +
                    "需要重做请到网页端点「重做」，再回到这里打开。"
            typeScanned && !allChoice ->
                "这份作业含非选择题，App 里只能暂存选择题答案，不能在这里整卷交卷。" +
                    "请到网页端完成并交卷；交卷按钮已置灰。"
            q.isChoice ->
                "选择选项会记录在本页；点「暂存本题」只保存这道题，点「交卷」会把所有已作答的题一起提交。"
            else ->
                "这道题是「${q.typeName}」，暂不支持在 App 里作答，可以到网页端填写；AI 建议仅供参考。"
        }
        b.scrollDetail.scrollTo(0, 0)
        Log.i(TAG, "render index=$index qid=${q.qid} number=${q.number} answered=${answers[q.qid].orEmpty()} reviewOnly=$reviewOnly")
        updateButtons()
    }

    /** 渲染完题目后：优先显示已经问过的 AI 结果，没有缓存时才按开关自动问 AI */
    private fun afterRender(q: HomeworkQuestion) {
        val cached = aiCache[q.qid]
        if (cached != null) {
            showAi(cached)
        } else if (Settings.homeworkAutoAi) {
            askAi(q, forced = false)
        } else {
            resetAiHint()
        }
    }

    // ------------------------------------------------------------ 选项

    private fun buildOptions(q: HomeworkQuestion) {
        b.llQuestionOptions.removeAllViews()
        if (!q.isChoice) return
        if (q.options.isEmpty()) {
            b.llQuestionOptions.addView(noteView("没有解析到选项"))
            return
        }
        val current = answers[q.qid].orEmpty()
        val editable = !submitted && !submitting && !reviewOnly
        if (q.isMulti) {
            for (opt in q.options) {
                val cb = MaterialCheckBox(this).apply {
                    text = "${opt.key}. ${opt.text}"
                    isChecked = current.contains(opt.key)
                    isEnabled = editable
                    setOnClickListener { toggleMulti(q, opt.key, isChecked) }
                }
                b.llQuestionOptions.addView(cb)
            }
        } else {
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            for (opt in q.options) {
                val rb = MaterialRadioButton(this).apply {
                    id = View.generateViewId()
                    text = "${opt.key}. ${opt.text}"
                    isChecked = current == opt.key
                    isEnabled = editable
                    setOnClickListener { selectSingle(q, opt.key) }
                }
                group.addView(rb)
            }
            b.llQuestionOptions.addView(group)
        }
    }

    private fun selectSingle(q: HomeworkQuestion, key: String) {
        if (submitted || reviewOnly) return
        answers[q.qid] = key
        // 改过答案：之前暂存过的版本作废，交卷前要重新暂存
        savedQids -= q.qid
        Log.i(TAG, "select qid=${q.qid} -> $key")
    }

    private fun toggleMulti(q: HomeworkQuestion, key: String, checked: Boolean) {
        if (submitted || reviewOnly) return
        val selected = answers[q.qid].orEmpty().map { it.toString() }.toMutableSet()
        if (checked) selected.add(key) else selected.remove(key)
        // 按选项顺序拼接，和服务端 H5 的拼接方式一致（如 "AB"）
        answers[q.qid] = q.options.map { it.key }.filter { it in selected }.joinToString("")
        savedQids -= q.qid
        Log.i(TAG, "multi qid=${q.qid} -> ${answers[q.qid]}")
    }

    private fun noteView(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ContextCompat.getColor(this@HomeworkDetailActivity, R.color.muted))
        setPadding(0, dp(4), 0, dp(4))
    }

    // ------------------------------------------------------------ 暂存 / 交卷

    private fun buildForm(q: HomeworkQuestion, tempSave: Boolean): Map<String, String> {
        val form = LinkedHashMap(q.formFields)
        if (q.answerField.isNotBlank()) {
            form[q.answerField] = answers[q.qid].orEmpty()
        }
        if (q.type > 0) form["type${q.qid}"] = q.type.toString()
        form["questionId"] = q.qid
        form["index"] = q.pageIndex.toString()
        form["tempSave"] = tempSave.toString()
        if (answerId.isNotBlank()) form["workRelationAnswerId"] = answerId
        return form
    }

    /** 当前作答地址（把 workAnswerId 换成服务端最新返回的 answerId） */
    private fun urlWithAnswerId(): String {
        val base = doHomeWorkUrl ?: return ""
        return if (answerId.isNotBlank()) {
            base.replace(Regex("workAnswerId=\\d+"), "workAnswerId=$answerId")
        } else {
            base
        }
    }

    /** 重新拉一题的页面（拿到最新的隐藏字段和草稿状态），失败返回 null */
    private suspend fun fetchPage(pageIndex: Int): HomeworkQuestion? {
        val hw = api ?: return null
        val html = hw.questionHtml(urlWithAnswerId(), pageIndex) ?: return null
        return HomeworkParser.parseQuestion(html, workId, pageIndex)
    }

    private fun saveCurrent() {
        val q = questions[index] ?: return
        val hw = api ?: return
        if (submitting || submitted) return
        if (reviewOnly) {
            Toast.makeText(this, "这份作业已经交过卷，不能再暂存", Toast.LENGTH_SHORT).show()
            return
        }
        if (!q.isChoice) {
            Toast.makeText(this, "这道题暂不支持在这里作答", Toast.LENGTH_SHORT).show()
            return
        }
        if (answers[q.qid].isNullOrBlank()) {
            Toast.makeText(this, "先选一个选项再暂存", Toast.LENGTH_SHORT).show()
            return
        }
        submitting = true
        updateButtons()
        workJob = lifecycleScope.launch {
            try {
                status("正在暂存第 ${q.number} 题…")
                val r = hw.submit(buildForm(q, tempSave = true), tempSave = true, referer = urlWithAnswerId())
                Log.i(TAG, "tempSave single qid=${q.qid} ok=${r.ok} msg=${r.message}")
                if (r.ok) {
                    if (r.answerId.isNotBlank()) answerId = r.answerId
                    savedQids += q.qid
                    status("第 ${q.number} 题已暂存")
                } else {
                    status("暂存失败：${r.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("暂存失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                submitting = false
                updateButtons()
            }
        }
    }

    private fun confirmSubmit() {
        if (submitting || submitted) return
        if (reviewOnly) {
            Toast.makeText(this, "这份作业已经交过卷，不能重复提交", Toast.LENGTH_LONG).show()
            return
        }
        if (!typeScanned) {
            Toast.makeText(this, "还没确认题型，先回列表点「刷新作业列表」再试", Toast.LENGTH_LONG).show()
            return
        }
        if (!allChoice) {
            Toast.makeText(this, "这份作业含非选择题，App 里不能整卷交卷，请到网页端完成", Toast.LENGTH_LONG).show()
            return
        }
        val answered = questions.values.count { answers[it.qid].orEmpty().isNotBlank() }
        if (answered == 0) {
            Toast.makeText(this, "还没有作答，先选选项", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("交卷")
            .setMessage(
                "已作答 $answered 题，交卷时会逐题保存后整卷提交。\n" +
                    "交卷后不能再修改，确定现在交卷吗？\n\n" +
                    "助手只会用你选的答案提交，不会替你改答案。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("交卷") { _, _ -> submitAll() }
            .show()
    }

    /**
     * 按 H5 的顺序交卷：
     * 1. 对每道已作答题：重新拉该题页面（拿最新隐藏字段 / 草稿 answerId），填答案，tempSave=true；
     * 2. 最后一题页面上发 tempSave=false 正式交卷（草稿里已是全部答案）。
     *
     * 之前把全部答案塞进一次 POST，服务端只认了第一题；这里逐题暂存更贴近网页端。
     */
    private fun submitAll() {
        val hw = api ?: return
        if (submitting || submitted || reviewOnly) return
        if (doHomeWorkUrl == null) return
        submitting = true
        updateButtons()
        workJob = lifecycleScope.launch {
            try {
                val answered = questions.values
                    .filter { it.isChoice && answers[it.qid].orEmpty().isNotBlank() }
                    .distinctBy { it.qid }
                    .sortedBy { it.pageIndex }
                if (answered.isEmpty()) {
                    status("还没有作答，先选择选项再交卷")
                    return@launch
                }
                // 1) 逐题：重新拉页面 -> 填答案 -> 暂存
                for ((i, cached) in answered.withIndex()) {
                    status("正在保存第 ${cached.number} 题（${i + 1}/${answered.size}）…")
                    val q = fetchPage(cached.pageIndex)
                    if (q == null) {
                        status("读取第 ${cached.number} 题失败，已取消交卷（没有提交）")
                        return@launch
                    }
                    questions[cached.pageIndex] = q
                    val form = LinkedHashMap(q.formFields)
                    form[q.answerField] = answers[q.qid].orEmpty()
                    form["type${q.qid}"] = q.type.toString()
                    form["questionId"] = q.qid
                    form["index"] = q.pageIndex.toString()
                    form["tempSave"] = "true"
                    if (answerId.isNotBlank()) form["workRelationAnswerId"] = answerId
                    Log.i(TAG, "tempSave qid=${q.qid} index=${q.pageIndex} answers=${form[q.answerField]}")
                    val r = hw.submit(form, tempSave = true, referer = urlWithAnswerId())
                    Log.i(TAG, "tempSave qid=${q.qid} ok=${r.ok} msg=${r.message} answerId=${r.answerId}")
                    if (!r.ok) {
                        status("暂存第 ${q.number} 题失败：${r.message}；已取消交卷（没有提交）")
                        return@launch
                    }
                    if (r.answerId.isNotBlank()) answerId = r.answerId
                    savedQids += q.qid
                }
                // 2) 最后一题：H5 式正式交卷（草稿已在服务端，只 POST 当前页）
                val lastPage = answered.last().pageIndex
                status("正在交卷（共 ${answered.size} 题）…")
                val q = fetchPage(lastPage)
                if (q == null) {
                    status("读取最后一题失败，已取消交卷（没有提交）")
                    return@launch
                }
                val form = LinkedHashMap(q.formFields)
                form[q.answerField] = answers[q.qid].orEmpty()
                form["type${q.qid}"] = q.type.toString()
                form["questionId"] = q.qid
                form["index"] = q.pageIndex.toString()
                form["tempSave"] = "false"
                if (answerId.isNotBlank()) form["workRelationAnswerId"] = answerId
                Log.i(
                    TAG,
                    "submit final answered=${answered.size} index=${q.pageIndex} " +
                        "fields=${form.size} answerId=$answerId"
                )
                val r = hw.submit(form, tempSave = false, referer = urlWithAnswerId())
                Log.i(TAG, "submit result ok=${r.ok} msg=${r.message} answerId=${r.answerId}")
                if (r.ok) {
                    submitted = true
                    render(q)
                    status("交卷成功：${r.message}")
                    Toast.makeText(this@HomeworkDetailActivity, "交卷成功", Toast.LENGTH_LONG).show()
                } else {
                    status("交卷失败：${r.message}")
                    Toast.makeText(
                        this@HomeworkDetailActivity,
                        "交卷失败：${r.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("交卷失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                submitting = false
                updateButtons()
            }
        }
    }

    private fun updateButtons() {
        val locked = submitting || submitted || reviewOnly
        b.btnPrev.isEnabled = index > 0 && !submitting
        b.btnNext.isEnabled = index + 1 < total && !submitting
        b.btnAsk.isEnabled = !submitting
        b.btnSave.isEnabled = !locked
        // 只有「确认整卷全是选择题」才允许交卷，避免漏掉填空/简答
        b.btnSubmit.isEnabled = !locked && typeScanned && allChoice
    }

    // ------------------------------------------------------------ AI

    private fun resetAiHint() {
        b.tvAiTitle.text = "AI 建议"
        b.tvAiAnswer.text = "点下方「问 AI」获取建议"
        b.tvAiSource.text = ""
        b.tvAiRaw.text = ""
    }

    private fun showAi(result: AiResult) {
        b.tvAiTitle.text = "AI 建议"
        b.tvAiAnswer.text = result.answer
        b.tvAiSource.text = result.source
        b.tvAiRaw.text = result.raw
    }

    private fun askAi(q: HomeworkQuestion?, forced: Boolean) {
        if (q == null) return
        val p = provider ?: return
        Log.i(TAG, "askAi qid=${q.qid} forced=$forced cached=${aiCache.containsKey(q.qid)}")
        if (!forced) {
            val cached = aiCache[q.qid]
            if (cached != null) {
                showAi(cached)
                return
            }
        }
        askJob?.cancel()
        b.btnAsk.isEnabled = false
        if (!p.configured) {
            b.tvAiTitle.text = "AI 建议"
            b.tvAiAnswer.text = "还没有配置题库 / 大模型"
            b.tvAiSource.text = "到「做题」页 →「答题设置」里配置题库或大模型，再回来点「问 AI」。"
            b.tvAiRaw.text = ""
            b.btnAsk.isEnabled = true
            return
        }
        b.tvAiTitle.text = "AI 建议"
        b.tvAiAnswer.text = "正在问题库 / 大模型…"
        b.tvAiSource.text = HomeworkAi.describe()
        b.tvAiRaw.text = ""
        val qid = q.qid
        val mine = ++askSeq
        askJob = lifecycleScope.launch {
            try {
                p.clearLlmRaw()
                val ans = p.search(q.toWorkQuestion())
                if (mine != askSeq) return@launch
                val result = if (ans == null) {
                    AiResult(
                        answer = "没有找到答案",
                        source = p.lastLlmError.ifBlank { HomeworkAi.describe() },
                        raw = ""
                    )
                } else {
                    val raw = p.lastLlmRaw
                    AiResult(
                        answer = q.prettyAnswer(ans.value).ifBlank { ans.value },
                        source = "来源：${ans.source} · ${HomeworkAi.describe()}",
                        raw = if (raw.isBlank()) "" else "大模型原文：\n$raw"
                    )
                }
                aiCache[qid] = result
                // 用户可能已经切到别的题：只有仍停在这题时才更新界面
                if (mine == askSeq) showAi(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine != askSeq) return@launch
                val result = AiResult(
                    answer = "问 AI 失败",
                    source = e.message ?: e.javaClass.simpleName,
                    raw = ""
                )
                aiCache[qid] = result
                showAi(result)
            } finally {
                if (mine == askSeq) b.btnAsk.isEnabled = !submitting
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    private fun status(text: String) {
        b.tvDetailStatus.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        b.tvDetailStatus.text = text
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val TAG = "XXT-HW"
        const val EXTRA_WORK_ID = "homework_work_id"
        const val EXTRA_COURSE_ID = "homework_course_id"
        const val EXTRA_CLASS_ID = "homework_class_id"
        const val EXTRA_CPI = "homework_cpi"
        const val EXTRA_COURSE_TITLE = "homework_course_title"
        const val EXTRA_TITLE = "homework_title"
        const val EXTRA_ALL_CHOICE = "homework_all_choice"
        const val EXTRA_SCANNED = "homework_scanned"
        const val EXTRA_CHOICE_COUNT = "homework_choice_count"
        const val EXTRA_QUESTION_COUNT = "homework_question_count"
    }
}
