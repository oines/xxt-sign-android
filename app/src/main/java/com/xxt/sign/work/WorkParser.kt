package com.xxt.sign.work

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 章节测验页面解析。
 *
 * 页面结构（实测）：
 *  - 题量在 `<div class="ceyan_name">`，文本形如「题量: 10」
 *  - 每道题是一个 `<div class="singleQuesId" id="question<qid>">`，**类名全都一样**
 *  - 真实题型在 `input#answertype<qid>` 的 value：0 单选 / 1 多选 / 2 填空 / 3 判断 / 4 简答
 *  - 选项是 `<span class="num_option" data="A|true|false">`，判断题的 data 第一段是 true/false
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

        return WorkPage(
            title = title.ifBlank { "章节测验" },
            questionCount = parseQuestionCount(doc) ?: questions.size,
            questions = questions,
            formFields = parseFormFields(doc),
            submitted = isSubmittedPage(html, questions.size),
            fontBase64 = parseFontBase64(html)
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
        val hints = listOf("已交卷", "查看详情", "查看已做答", "本次得分", "成绩详情", "record-list")
        return hints.any { html.contains(it) }
    }

    private fun parseQuestion(block: Element): WorkQuestion? {
        val qid = block.id().removePrefix("question").takeIf { it.isNotBlank() } ?: return null

        val type = block.selectFirst("input#answertype$qid")?.attr("value")?.toIntOrNull()
            ?: block.selectFirst("input[name=answertype$qid]")?.attr("value")?.toIntOrNull()
            ?: WorkQuestion.TYPE_SINGLE

        val options = block.select("span.num_option").mapNotNull { span ->
            val key = span.attr("data").split('|').firstOrNull()?.trim().orEmpty()
            if (key.isBlank()) return@mapNotNull null
            WorkOption(key = key, text = span.text().trim())
        }

        // 题干：复制一份，把选项/表单控件全部剔掉再取文本，避免把选项拼进题干
        val clone = block.clone()
        clone.select("span.num_option, input, textarea, select, script, style").remove()
        val stem = clone.text().trim()

        return WorkQuestion(qid = qid, type = type, stem = stem, options = options)
    }

    /**
     * 提交时要原样带回的表单字段：页面 form 里 name **不以 answer 开头**的控件。
     * 包括隐藏域（workAnswerId、enc、cpi、ktoken……）。
     */
    fun parseFormFields(doc: Document): Map<String, String> {
        val form = doc.select("form").firstOrNull { f ->
            f.attr("action").contains("addStudentWorkNew") ||
                f.selectFirst("div.singleQuesId") != null
        } ?: doc.selectFirst("form")

        val out = LinkedHashMap<String, String>()
        val scope: Element = form ?: doc.body() ?: doc
        scope.select("input[name], textarea[name], select[name]").forEach { el ->
            val name = el.attr("name").trim()
            if (name.isBlank() || name.startsWith("answer")) return@forEach
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

/** 成绩回查（record-list）的宽松解析结果 */
data class RecordSummary(val times: Int, val score: String) {
    val seen: Boolean get() = times > 0
}

/**
 * record 页解析。
 *
 * 只保证「交了几次 + 分数」这两个通用信息；逐题对错的页面结构随版本变化大，
 * 这里只做宽松匹配，解析不到就返回空列表，不影响主流程。
 */
object RecordParser {

    fun parseList(html: String): RecordSummary {
        val times = Regex("times=(\\d+)").findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?: Regex("第\\s*(\\d+)\\s*次").findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .maxOrNull()
            ?: 0

        val score = Regex("(\\d+(?:\\.\\d+)?)\\s*分").find(html)?.groupValues?.getOrNull(1).orEmpty()
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
