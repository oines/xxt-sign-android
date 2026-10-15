package com.xxt.sign.sign

import com.xxt.sign.core.Account
import com.xxt.sign.core.AccountStore
import com.xxt.sign.core.LogStore
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.SignOptions

/**
 * 多账号巡检编排：每个账号自己把名下所有进行中的活动签一遍。
 * 主要用于**无人值守的自动巡检**（MonitorService）和「全部签」场景。
 *
 * 需要「选一堂课、全部账号签这一堂」时用 [SignPlanner]。
 */
class SignRunner(
    private val accounts: List<Account>,
    private val opt: SignOptions,
    private val onProgress: (String) -> Unit = {},
    private val onResult: (SignEngine.Outcome) -> Unit = {},
    private val askHuman: suspend (SignFlow.CaptchaPrompt) -> String? = { null },
    private val quiet: Boolean = false
) {

    private val rnd = java.util.Random()

    suspend fun run(): List<SignEngine.Outcome> {
        val all = mutableListOf<SignEngine.Outcome>()
        accounts.forEachIndexed { idx, acc ->
            onProgress("正在处理 ${acc.display}（${idx + 1}/${accounts.size}）")
            val rs = runCatching { runAccount(acc) }
                .getOrElse { listOf(fail(acc, "异常：${it.message ?: it.javaClass.simpleName}")) }
            rs.forEach {
                onResult(it)
                if (!quiet || it.detail.contains("成功")) LogStore.add("[${it.title}] ${it.detail}", it.ok)
            }
            all.addAll(rs)

            if (idx < accounts.size - 1 && accounts.size > 1) {
                com.xxt.sign.core.Jitter.betweenAccounts()
            }
        }
        return all
    }

    private suspend fun runAccount(acc: Account): List<SignEngine.Outcome> {
        val (session, err) = SignEngine.sessionFor(acc)
        if (session == null) return listOf(fail(acc, "登录失败：$err"))

        val api = CxApi(session)

        // 定向模式：扫码 / 指定活动
        if (!opt.aid.isNullOrBlank()) {
            val act = runCatching { SignEngine.activityByAid(api, opt.aid!!) }
                .getOrElse { return listOf(fail(acc, "拉取活动失败：${it.message}")) }
            return listOf(
                SignFlow.signWithCaptcha(acc, api, act, opt, onProgress, askHuman)
            )
        }

        val acts = runCatching { SignEngine.findAllActive(api, opt.courseFilter) }
            .getOrElse { return listOf(fail(acc, "查询活动失败：${it.message}")) }

        val targeted = acts.filter { act ->
            when {
                !opt.signCode.isNullOrBlank() ->
                    act.otherId == CxApi.TYPE_GESTURE || act.otherId == CxApi.TYPE_CODE
                !opt.enc.isNullOrBlank() -> act.otherId == CxApi.TYPE_QRCODE
                else -> true
            }
        }

        if (targeted.isEmpty()) {
            val why = when {
                !opt.signCode.isNullOrBlank() -> "未检测到进行中的手势/签到码活动"
                !opt.enc.isNullOrBlank() -> "未检测到进行中的二维码签到"
                else -> "未检测到进行中的签到/签退"
            }
            return listOf(
                SignEngine.Outcome(
                    acc.id, acc.display, why, false, SignEngine.SignStatus.NO_ACTIVITY
                )
            )
        }

        onProgress("${acc.display}：发现 ${targeted.size} 个活动，逐个签")
        // 逐个签，中间插入拟人停顿；不要背靠背连着发
        val out = mutableListOf<SignEngine.Outcome>()
        targeted.forEachIndexed { i, act ->
            if (i > 0) com.xxt.sign.core.Jitter.betweenActivities()
            out.add(
                SignFlow.signWithCaptcha(acc, api, act, opt, onProgress, askHuman)
            )
        }
        return out
    }

    private fun fail(acc: Account, msg: String) =
        SignEngine.Outcome(acc.id, acc.display, msg, false, SignEngine.SignStatus.FAILED)
}
