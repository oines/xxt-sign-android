package com.xxt.sign.work

import com.xxt.sign.core.Settings

/**
 * 作业用的 AI 建议来源。
 *
 * 直接复用章节测验的配置和 [AnswerProvider]：题库 / 大模型 / 思考强度
 * 全部和「做题」页共用同一套设置，用户不用在作业页再配一遍。
 *
 * 注意：这里只调用 [AnswerProvider.search]，**不会**调用任何提交接口。
 */
object HomeworkAi {

    /** 按当前设置构造答案来源；一直复用同一个实例（题库连接/lib 会自己缓存） */
    fun provider(): AnswerProvider = AnswerProvider(
        provider = Settings.quizProvider,
        endpoint = Settings.quizEndpoint,
        token = Settings.quizToken,
        llm = buildLlm()
    )

    /** 题库 + 大模型都关掉时给 UI 一句提示 */
    fun describe(): String = when {
        Settings.quizLlmEnabled && Settings.quizLlmKey.isNotBlank() ->
            "题库：${Settings.quizProvider.ifBlank { "auto" }}；大模型：${Settings.quizLlmModel}"
        else -> "只查题库（大模型未开启，可在「做题 → 答题设置」里配置）"
    }

    private fun buildLlm(): LlmClient? {
        if (!Settings.quizLlmEnabled) return null
        val key = Settings.quizLlmKey.trim()
        if (key.isEmpty()) return null
        return LlmClient(
            apiKey = key,
            baseUrl = Settings.quizLlmBaseUrl,
            model = Settings.quizLlmModel,
            effort = Settings.quizLlmEffort,
            webSearch = Settings.quizWebSearch && Settings.quizLlmBaseUrl.contains("deepseek.com", true)
        )
    }
}
