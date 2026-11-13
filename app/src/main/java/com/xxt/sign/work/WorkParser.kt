package com.xxt.sign.work

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * 章节测验页面解析。
 *
 * 页面结构（实测）：
 *  - 题量在 `<div class="ceyan_name">`，文本形如「题量: 10」
 *  - 每道题是一个 `<div class="singleQuesId" id="question<qid>">`，**类名全都一样**
 *  - 真实题型在 `input#answertype<qid>` 的 value：0 单选 / 1 多选 / 2 填空 / 3 判断 / 4 简答
 *  - 选项是 `<span class="num_option" data="A|true|false">`（多选版本用 `num_option_dx`，
 *    同一份页面里两种类名会同时出现，所以两个都要收）
 *  - 字体反解用的 TTF 内嵌在 `style#cxSecretStyle` 的 `@font-face{src:url(data:font/ttf;base64,...)}`
 *
 * 全部是纯字符串/JSoup 处理，不碰网络，单测可直接喂 HTML。
 */
object WorkParser {

    private val BASE64 = Regex("base64,([A-Za-z0-9+/=\\s]+)")

    fun parse(html: String): WorkPage {
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.ceyan_name h1, .ceyan_name .ceyanName")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim().orEmpty()

        val questions = doc.select("div.singleQuesId").mapNotNull { parseQuestion(it) }
        val formFields = parseFormFields(doc)

        return WorkPage(
            title = title.ifBlank { "章节测验" },
            questionCount = parseQuestionCount(doc) ?: questions.size,
            questions = questions,
            formFields = formFields,
            submitted = isSubmittedPage(html, questions.size),
            fontBase64 = parseFontBase64(html),
            formAction = parseFormAction(doc),
            blankFields = parseBlankFields(formFields, questions.map { it.qid })
        )
    }

    /** 「题量: 10」；拿不到返回 null，由调用方退回按题目块数量算 */
    fun parseQuestionCount(doc: Document): Int? {
        val text = doc.selectFirst("div.ceyan_name")?.text().orEmpty()
        if (text.isBlank()) return null
        return Regex("题量\\s*[:：]\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * 已交卷的判定：服务端不再给题目，而是直接渲染 record 页，
     * 页面里会出现「已交卷 / 查看详情 / 得分」这类字样。
     */
    fun isSubmittedPage(html: String, questionCount: Int): Boolean {
        if (questionCount > 0) return false
        val hints = listOf("已交卷", "查看详情", "查看已做答", "本次得分", "成绩详情", "record-list", "次作答")
        return hints.any { html.contains(it) }
    }

    private fun parseQuestion(block: Element): WorkQuestion? {
        // 新版页面有时没有 id="question<qid>"，qid 在 singleQuesId 的 data 属性上。
        val qid = block.id().removePrefix("question").takeIf { it.isNotBlank() }
            ?: block.attr("data").trim().takeIf { it.isNotBlank() }
            ?: return null

        val type = block.selectFirst("input#answertype$qid")?.attr("value")?.toIntOrNull()
            ?: block.selectFirst("input[name=answertype$qid]")?.attr("value")?.toIntOrNull()
            // 旧版题型在 div.TiMu 的 data 上（0 单选 / 1 多选 / 2 填空 / 3 判断 / 4 简答）
            ?: block.select("div.TiMu[data]").firstOrNull { it !== block }?.attr("data")?.toIntOrNull()
            ?: WorkQuestion.TYPE_SINGLE

        // 单选/判断用 num_option，多选用 num_option_dx；同一份卷子里两种都有
        val options = parseOptions(block)

        // 题干：复制一份，把选项行/表单控件全部剔掉再取文本，避免把选项拼进题干。
        // 注意选项正文有时不在 span 里，而在同一 <li> 内，所以要先删整行，不能只删 span。
        val clone = block.clone()
        removeOptionMarkup(clone)
        clone.select("input, textarea, select, script, style").remove()
        val stem = clone.text().trim()

        return WorkQuestion(qid = qid, type = type, stem = stem, options = options)
    }

    private fun parseOptions(block: Element): List<WorkOption> {
        val spans = (block.select("span.num_option") + block.select("span.num_option_dx")).distinct()
        return spans.mapNotNull { span ->
            val key = span.attr("data").split('|').firstOrNull()?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            WorkOption(key = key, text = optionText(span, key))
        }.distinctBy { it.key }
    }

    /**
     * 选项正文可能在：
     *  - li / span 的 aria-label（超星部分页面选项文字只放在无障碍属性里，span 本身为空）
     *  - 父级 li 内（`<li><span>A</span><div>甲</div></li>`）
     *  - span 后的文本节点 / 后续兄弟节点
     *  - span 自身文本
     */
    private fun optionText(span: Element, key: String): String {
        val parent = span.parent()
        val row = span.parents().firstOrNull { it.tagName() == "li" } ?: parent

        // 1. aria-label 优先级最高：真机上出现过 span 只有字母、正文只在 aria-label 里的页面
        val aria = sequenceOf(span, row, parent)
            .filterNotNull()
            .map { it.attr("aria-label").trim() }
            .firstOrNull { it.isNotBlank() }
        if (aria != null) {
            val cleaned = cleanOptionText(aria, key)
            if (cleaned.isNotBlank()) return cleaned
        }

        // 2. 整行（li）取文本，去掉选项字母 span 后剩下的就是正文
        if (row != null && row.tagName() == "li") {
            val rowText = row.clone()
                .also { it.select("span.num_option, span.num_option_dx").remove() }
                .text()
                .trim()
            if (rowText.isNotBlank()) return cleanOptionText(rowText, key)
        }

        // 3. 也可能是 span 后直接跟文本节点：<div class="options"><span>A</span>甲<span>B</span>乙</div>
        //    必须先看紧接着的文本节点，否则 nextElementSibling 会拿到下一个选项的 span。
        val nextNode = span.nextSibling()
        if (nextNode is TextNode && nextNode.text().isNotBlank()) {
            return cleanOptionText(nextNode.text(), key)
        }
        var sibling = span.nextElementSibling()
        while (sibling != null) {
            val text = sibling.text().trim()
            if (text.isNotBlank()) return cleanOptionText(text, key)
            sibling = sibling.nextElementSibling()
        }
        val own = span.text().trim()
        if (own.isNotBlank()) return cleanOptionText(own, key).ifBlank { own }
        return cleanOptionText(parent?.text()?.trim().orEmpty(), key)
    }

    /** aria-label 常带 "A. 选项正文" 前缀，去掉字母壳和后缀的「选择」 */
    private fun cleanOptionText(raw: String, key: String): String {
        var text = raw.replace(Regex("\\s+"), " ").trim()
        if (key.isNotBlank()) {
            val prefixes = listOf("$key.", "$key．", "$key、", "$key)", "$key）", "$key ")
            prefixes.firstOrNull { text.startsWith(it, ignoreCase = true) }?.let {
                text = text.substring(it.length).trim()
            }
        }
        if (text.endsWith("选择")) text = text.dropLast(2).trim()
        return text
    }

    /** 删掉选项所在的整行（li / 带 option 类名的块），避免选项正文残留在题干 */
    private fun removeOptionMarkup(root: Element) {
        val rows = mutableListOf<Element>()
        rows += root.select("li").filter { it.selectFirst("span.num_option, span.num_option_dx") != null }
        rows += root.select("div, p, section").filter { el ->
            el.className().lowercase().contains("option") &&
                el.selectFirst("span.num_option, span.num_option_dx") != null
        }
        rows.forEach { it.remove() }
        root.select("span.num_option, span.num_option_dx").remove()
    }

    /** 提交用的 form（新版 mooc2 是 `#form1`，老版是 `#submitForm`） */
    private fun findForm(doc: Document): Element? = doc.select("form").firstOrNull { f ->
        f.attr("action").contains("addStudentWorkNew") ||
            f.selectFirst("div.singleQuesId") != null
    } ?: doc.selectFirst("form")

    /** form 的 action 原文：带 token / totalQuestionNum / workid 这些 query，提交时优先用它 */
    fun parseFormAction(doc: Document): String =
        findForm(doc)?.attr("action")?.trim().orEmpty()

    /**
     * 提交时要原样带回的表单字段：页面 form 里**所有** name 非空的控件。
     *
     * 这里绝对不能按 `answer` 前缀过滤：`answerId`、`answertype<qid>`、
     * `answer<qid>`、`answerEditor<qid><空号>` 都是服务端点数用的，
     * 少一个就回「无效的提交，参数异常！」或「无效的参数：code-2！」（真机已复现）。
     */
    fun parseFormFields(doc: Document): Map<String, String> {
        val form = findForm(doc)
        val out = LinkedHashMap<String, String>()
        val scope: Element = form ?: doc.body() ?: doc
        scope.select("input[name], textarea[name], select[name]").forEach { el ->
            val name = el.attr("name").trim()
            if (name.isBlank()) return@forEach
            when (el.tagName()) {
                "select" -> {
                    val opt = el.selectFirst("option[selected]") ?: el.selectFirst("option")
                    out[name] = opt?.attr("value").orEmpty()
                }
                "textarea" -> out[name] = el.text()
                else -> {
                    val type = el.attr("type").lowercase()
                    if (type in setOf("submit", "button", "reset", "file", "image")) return@forEach
                    out[name] = el.attr("value")
                }
            }
        }
        return out
    }

    /**
     * 填空题的空位字段：实测没有 `answer<qid>`，只有 `answerEditor<qid><空号>`
     * （例：`answerEditor4047582721` = 题 404758272 的第 1 空）。
     * 按已解析出的 qid 做最长前缀匹配，避免把 9 位 qid 和空号粘错。
     */
    fun parseBlankFields(fields: Map<String, String>, qids: List<String>): Map<String, List<String>> {
        if (qids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        fields.keys.asSequence()
            .filter { it.startsWith("answerEditor") }
            .forEach { name ->
                val qid = qids.filter { name.startsWith("answerEditor$it") }.maxByOrNull { it.length }
                    ?: return@forEach
                out.getOrPut(qid) { mutableListOf() }.add(name)
            }
        return out.mapValues { (qid, names) ->
            names.sortedBy { name -> name.removePrefix("answerEditor$qid").toIntOrNull() ?: 0 }
        }
    }

    /**
     * 从 `style#cxSecretStyle` 里抠出内嵌 TTF 的 base64。
     * 同一份卷子字体固定，跨卷随机，所以缓存粒度是「一份卷子」。
     */
    fun parseFontBase64(html: String): String? {
        val style = Regex(
            "<style[^>]*id=\"cxSecretStyle\"[^>]*>(.*?)</style>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html)?.groupValues?.getOrNull(1)
            ?: if (html.contains("cxSecretStyle")) html else return null

        val raw = BASE64.find(style)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace(Regex("\\s"), "")
    }

    /** 日志里用的短题干 */
    fun shortStem(stem: String, limit: Int = 24): String {
        val s = stem.replace(Regex("\\s+"), " ").trim()
        return if (s.length <= limit) s else s.take(limit) + "…"
    }
}

/** 成绩回查的解析结果 */
data class RecordSummary(val times: Int, val score: String) {
    val seen: Boolean get() = times > 0
}

/**
 * record 页解析。
 *
 * 章测的回查页是 `selectWorkQuestionYiPiYue`（交卷后再拉一次卷子就会 302 过去）：
 *   `<span class="ans_number">第1次作答</span>`
 *   `<span class="achievement">本次成绩<i>80</i>分</span>`
 * `/mooc-ans/work/record-list` 对章测是死的（实测回「此作业已被删除」），不走它。
 */
object RecordParser {

    fun parseList(html: String): RecordSummary {
        val times = Regex("第\\s*(\\d+)\\s*次作答").findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?: Regex("times=(\\d+)").findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull()
            ?: Regex("第\\s*(\\d+)\\s*次").findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull()
            ?: 0

        val score = Regex("class=\"achievement\"[^>]*>[\\s\\S]{0,40}?<i[^>]*>(\\d+(?:\\.\\d+)?)</i>")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("本次成绩[\\s\\S]{0,80}?(\\d+(?:\\.\\d+)?)\\s*分")
                .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("(\\d+(?:\\.\\d+)?)\\s*分").find(html)?.groupValues?.getOrNull(1).orEmpty()
        return RecordSummary(times = times, score = score)
    }

    /** 逐题的「我的答案 / 正确答案」，用于事后核对；解析不到返回空列表 */
    fun parseDetail(html: String): List<Pair<String, String>> {
        val mine = Regex("我的答案\\s*[:：]\\s*([^<\\n]{0,60})").findAll(html)
            .map { it.groupValues[1].trim() }.toList()
        val right = Regex("正确答案\\s*[:：]\\s*([^<\\n]{0,60})").findAll(html)
            .map { it.groupValues[1].trim() }.toList()
        return mine.zip(right)
    }
}
