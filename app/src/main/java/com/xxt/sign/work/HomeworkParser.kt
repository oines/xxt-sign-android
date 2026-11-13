package com.xxt.sign.work

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 「作业」H5 页面解析（只读）。
 *
 * 页面结构（2026-11 用真实账号 + 桌面 UA 实测，见 probe7/probe8）：
 *  - 列表页 `mooc-ans/work/task-list?...`：每条是
 *    `<li onclick="goTask(this);" data="<详情URL>" data1="<workId>">`，
 *    里面有 `<p>标题</p>`、状态 `<span>未交|待批阅|已完成</span>`、
 *    截止 `<span class="fr">剩余N小时N分钟</span>`。
 *  - 状态页 `work/phone/task-work` 里能拿到 `doHomeWork` 地址（带 enc、workAnswerId）。
 *  - 作答页 `work/phone/doHomeWork?...&index=N`：**一页一题**，服务端渲染。
 *    题目容器 `<div class="wid750 singleQuesId" data="<qid>">`；
 *    题干在 `div.timuStyle`（可能带 `div.ans-cc`）；
 *    题型标题在 `h2.titType`（如「7. 多选题」）；
 *    题型码在 `input[name=type<qid>] value`（0 单选 / 1 多选 / 4 简答 / 8 其它）；
 *    选项统一取 `span[orichoice]`（单选 `span.choose`、多选 `span.dxcheck`），
 *    选项正文在同 id 的 `div.centerSpan`，退化为 `aria-label`（「选项A.xxx」）；
 *    当前题号在 `input#index`（0 起），题数在 `<b>N/M</b>`；单题作业没有这个标记。
 *
 * 纯 JSoup，不碰网络，可直接喂 HTML 单测。
 */
object HomeworkParser {

    private val NUM_PREFIX = Regex("^\\s*\\d+\\s*[.、．]\\s*")
    private val OPTION_PREFIX = Regex("^\\s*选项\\s*[A-Za-z]?\\s*[.、．:：]?\\s*")
    private val FRACTION = Regex("(\\d+)\\s*/\\s*(\\d+)")
    private val DO_HOMEWORK = Regex("(/work/phone/doHomeWork\\?[^\"'\\s<>]+)")

    // ------------------------------------------------------------ 列表

    /** 解析一页作业列表；[course] 用来给每条打上课程上下文 */
    fun parseList(html: String, course: HomeworkCourse): List<HomeworkItem> {
        val doc = Jsoup.parse(html)
        return doc.select("li[data1]").mapNotNull { li ->
            val workId = li.attr("data1").trim()
            if (workId.isBlank() || !workId.all { it.isDigit() }) return@mapNotNull null
            val text = li.select("p").firstOrNull()?.text()?.trim().orEmpty()
            val spans = li.select("span")
            val status = spans.firstOrNull { !it.hasClass("fr") }?.text()?.trim().orEmpty()
            val deadline = spans.firstOrNull { it.hasClass("fr") }?.text()?.trim().orEmpty()
            HomeworkItem(
                course = course,
                workId = workId,
                title = text.ifBlank { "作业 $workId" },
                status = status,
                deadline = deadline
            )
        }
    }

    /** 列表分页总数（页面里 `input#totalPage` 或 answer 里的 `totalPage`） */
    fun totalPages(html: String): Int {
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("input#totalPage")?.attr("value")
            ?: Regex("\"totalPage\"\\s*:\\s*\"?(\\d+)").find(html)?.groupValues?.get(1)
            ?: return 1
        return raw.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    // ------------------------------------------------------------ 详情

    /** 从 task-work 状态页里抠出 doHomeWork 地址 */
    fun doHomeWorkUrl(html: String): String? {
        val m = DO_HOMEWORK.find(html) ?: return null
        return m.groupValues[1].replace("&amp;", "&")
    }

    /**
     * 解析一页（一道题）。
     *
     * @param index 请求时用的 0 起下标，页面没有 `input#index` 时用它兜底
     */
    fun parseQuestion(html: String, workId: String, index: Int = 0): HomeworkQuestion? {
        val doc = Jsoup.parse(html)
        val box = doc.selectFirst("div.singleQuesId")
            ?: doc.selectFirst("div.wid750")
            ?: return null
        val qid = box.attr("data").trim().ifBlank {
            doc.selectFirst("input#questionId")?.attr("value")?.trim().orEmpty()
        }
        if (qid.isBlank()) return null

        val total = parseTotal(doc)
        val rawIndex = doc.selectFirst("input#index")?.attr("value")?.toIntOrNull() ?: index
        val number = (rawIndex + 1).coerceAtLeast(1).let { if (total > 1) it.coerceAtMost(total) else 1 }

        val typeName = parseTypeName(box, doc).ifBlank { "题目" }
        val type = doc.selectFirst("input[name=type$qid]")?.attr("value")?.toIntOrNull()
            ?.let(::normalizeType)
            ?: typeCodeOf(typeName)

        return HomeworkQuestion(
            workId = workId,
            number = number,
            total = total,
            qid = qid,
            type = type,
            typeName = typeName,
            stem = parseStem(box),
            options = parseOptions(box),
            pageIndex = rawIndex,
            formFields = parseFormFields(doc),
            answerField = parseAnswerField(doc, qid)
        )
    }

    /**
     * 解析 `form#submitTest` 里的隐藏字段。
     *
     * 提交时要把这些字段原样带回去（`tempSave`、答题字段、`index` 等会由调用方覆盖）。
     */
    fun parseFormFields(doc: Document): Map<String, String> {
        val form = doc.selectFirst("form#submitTest") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (el in form.select("input, textarea, select")) {
            val name = el.attr("name").trim()
            if (name.isEmpty()) continue
            val value = when (el.tagName().lowercase()) {
                "textarea" -> el.text()
                "select" -> el.selectFirst("option[selected]")?.attr("value")
                    ?: el.selectFirst("option")?.attr("value").orEmpty()
                else -> when (el.attr("type").lowercase()) {
                    "checkbox", "radio" -> if (el.hasAttr("checked")) el.attr("value") else continue
                    else -> el.attr("value")
                }
            }
            out[name] = value
        }
        return out
    }

    /** 选择题的答题字段：单选 `answer<qid>`，多选 `answers<qid>` */
    fun parseAnswerField(doc: Document, qid: String): String {
        val multi = doc.selectFirst("input[name=answers$qid]")
        if (multi != null) return "answers$qid"
        val single = doc.selectFirst("input[name=answer$qid]")
        if (single != null) return "answer$qid"
        return ""
    }

    private fun parseTotal(doc: Document): Int {
        val exact = Regex("^\\d+\\s*/\\s*\\d+$")
        val b = doc.select("b").firstOrNull { exact.matches(it.text().trim()) }
            ?: doc.select("span.focusSpan").firstOrNull { FRACTION.containsMatchIn(it.text()) }
            ?: return 1
        val m = FRACTION.find(b.text()) ?: return 1
        val total = m.groupValues[2].toIntOrNull() ?: return 1
        return if (total in 1..200) total else 1
    }

    private fun parseTypeName(box: Element, doc: Document): String {
        val raw = box.selectFirst("h2.titType")?.text()?.trim()
            ?: doc.selectFirst("h2.titType")?.text()?.trim()
            ?: doc.selectFirst("span.focusSpan")?.attr("aria-label")?.trim().orEmpty()
        return NUM_PREFIX.replace(raw, "").trim()
    }

    private fun parseStem(box: Element): String {
        val el = box.selectFirst("div.timuStyle")
            ?: box.selectFirst("div.ans-cc")
            ?: return ""
        val clone = el.clone()
        clone.select("script, style, input, textarea, select, span.focusSpan").remove()
        return clone.text().trim()
    }

    private fun parseOptions(box: Element): List<WorkOption> {
        val out = LinkedHashMap<String, WorkOption>()
        for (span in box.select("span[orichoice]")) {
            val key = span.attr("orichoice").trim().uppercase()
            if (key.isBlank() || out.containsKey(key)) continue
            val text = optionText(box, key, span)
            out[key] = WorkOption(key, text)
        }
        return out.values.toList()
    }

    private fun optionText(box: Element, key: String, span: Element): String {
        // 1) 同 id 的 div.centerSpan 才是正文（实测 <div aria-hidden="true" id="A" class="centerSpan workWrap">）
        box.select("div[id]").firstOrNull { it.id().equals(key, ignoreCase = true) }
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        // 2) aria-label：「选项A.资产阶级改良运动」
        val aria = OPTION_PREFIX.replace(span.attr("aria-label"), "").trim()
        if (aria.isNotEmpty()) return aria
        // 3) 兜底：span 自己的文字（常常只有 A）或父节点文字
        return span.text().trim().ifBlank {
            span.parent()?.text()?.trim().orEmpty()
        }
    }

    // ------------------------------------------------------------ 状态页（分数 / 重做）

    /**
     * task-work 状态页里的成绩：`<p class="Number"> 10 <span>分</span> </p>`。
     * 还没批阅、没有分数时返回空串。
     */
    fun parseScore(html: String): String {
        val doc = Jsoup.parse(html)
        val text = doc.selectFirst("p.Number")?.text()?.trim().orEmpty()
        if (text.isBlank()) return ""
        return Regex("\\d+(?:\\.\\d+)?").find(text)?.value.orEmpty()
    }

    /** task-work 页里是否还有「重做」入口（服务端按剩余重做次数决定是否渲染） */
    fun parseRedoable(html: String): Boolean =
        html.contains("onclick=\"redo()\"") || html.contains("onclick='redo()'")

    /** 从 doHomeWork 地址里取 workAnswerId（重做接口要带） */
    fun workAnswerId(url: String): String =
        Regex("workAnswerId=(\\d+)").find(url)?.groupValues?.get(1).orEmpty()

    // ------------------------------------------------------------ 题型映射

    /** 作业题型码 → 章节测验题型码；8（其它）和未知类型都归到 4（简答） */
    fun normalizeType(raw: Int): Int = when (raw) {
        0, 1, 2, 3 -> raw
        4 -> 4
        else -> 4
    }

    fun typeCodeOf(name: String): Int = when {
        name.contains("单选") -> 0
        name.contains("多选") || name.contains("不定项") -> 1
        name.contains("填空") -> 2
        name.contains("判断") -> 3
        else -> 4 // 简答 / 论述 / 名词解释 / 计算 / 其它
    }
}
