package com.xxt.sign.sign

import com.xxt.sign.core.Account
import com.xxt.sign.core.LogStore
import com.xxt.sign.core.Settings
import com.xxt.sign.net.Course
import com.xxt.sign.net.CxApi
import com.xxt.sign.net.QrPayload
import com.xxt.sign.net.SignOptions
import org.json.JSONObject

/**
 * 「先检测、选一堂课、全部账号签这一堂」的编排。
 *
 * 为什么按**活动**（aid）而不是按账号分组：
 *  - 二维码的 `enc` 是所有账号共用的（它只证明"有人在现场"），而且只有约 10 秒有效期
 *    （`ewmRefreshTime=10`）。所有账号签同一个活动，天然落在同一个窗口里。
 *  - 同一个账号可能同时有多个课的活动（例如高数二维码 + 英语位置）。
 *    分组后张三会在两个组里各出现一次，各签一次，互不干扰。
 */
object SignPlanner {

    /**
     * 这个活动还缺什么才能签。
     * 类型是活动自带的属性（otherId），所以该做的事完全能自动推导，
     * 不需要用户先选模式——只在真正缺东西时问一次。
     */
    enum class Requirement { NONE, QR_CODE, SIGN_CODE, LOCATION, PHOTO }

    /** 一个被检测到的、可签的活动，可能横跨多个账号 */
    data class Detected(
        val aid: String,
        val courseId: String,
        val classId: String,
        val courseName: String,
        val otherId: Int,
        val isSignOut: Boolean,
        val label: String,
        val accountIds: List<String>,
        val accountNames: List<String>,
        val detail: JSONObject
    ) {
        val title: String get() = "$courseName · $label"
        val needsQr: Boolean get() = otherId == CxApi.TYPE_QRCODE
        val accountCount: Int get() = accountIds.size
        val ifPhoto: Int get() = detail.optInt("ifPhoto", detail.optInt("ifphoto", 0))

        /** 服务端已经给了签到码（手势 / 签到码场景） */
        val serverSignCode: String get() = detail.optString("signCode")

        val requirement: Requirement
            get() = when {
                otherId == CxApi.TYPE_QRCODE -> Requirement.QR_CODE

                otherId == CxApi.TYPE_GESTURE || otherId == CxApi.TYPE_CODE ->
                    if (serverSignCode.isBlank()) Requirement.SIGN_CODE else Requirement.NONE

                otherId == CxApi.TYPE_LOCATION ->
                    if (Settings.latitude.isBlank() || Settings.longitude.isBlank())
                        Requirement.LOCATION else Requirement.NONE

                ifPhoto == 1 && Settings.photoPath.isBlank() -> Requirement.PHOTO

                else -> Requirement.NONE
            }
    }

    /**
     * 检测所有账号当前进行中的签到 / 签退，按活动聚合。
     * 这一步**不发任何签到请求**，纯侦察。
     */
    suspend fun detect(
        accounts: List<Account>,
        filter: Set<String>,
        onProgress: (String) -> Unit = {}
    ): List<Detected> {
        val map = LinkedHashMap<String, MutableDetected>()

        accounts.forEachIndexed { i, acc ->
            onProgress("正在检测 ${acc.display}（${i + 1}/${accounts.size}）")
            val (s, err) = SignEngine.sessionFor(acc)
            if (s == null) {
                LogStore.add("[检测] ${acc.display} 登录失败：$err", false)
                return@forEachIndexed
            }
            val api = CxApi(s)
            val acts = runCatching { SignEngine.findAllActive(api, filter) }
                .getOrElse {
                    LogStore.add("[检测] ${acc.display} 查询失败：${it.message}", false)
                    return@forEachIndexed
                }
            acts.forEach { act ->
                val e = map.getOrPut(act.aid) {
                    MutableDetected(
                        aid = act.aid,
                        courseId = act.course.courseId,
                        classId = act.course.classId,
                        courseName = act.course.name.ifBlank { "未知课程" },
                        otherId = act.otherId,
                        isSignOut = act.isSignOut,
                        label = act.label,
                        detail = act.detail
                    )
                }
                e.accountIds.add(acc.id)
                e.accountNames.add(acc.display)
            }
            if (acts.isNotEmpty()) {
                LogStore.add("[检测] ${acc.display} 发现 ${acts.size} 个活动", true)
            }
        }

        return map.values.map { it.build() }
    }

    private class MutableDetected(
        val aid: String,
        val courseId: String,
        val classId: String,
        val courseName: String,
        val otherId: Int,
        val isSignOut: Boolean,
        val label: String,
        val detail: JSONObject
    ) {
        val accountIds = mutableListOf<String>()
        val accountNames = mutableListOf<String>()
        fun build() = Detected(
            aid, courseId, classId, courseName, otherId, isSignOut, label,
            accountIds.toList(), accountNames.toList(), detail
        )
    }

    /**
     * 让所有有该活动的账号逐个签掉它。
     *
     * 分两阶段，为的是“能签的先签上，要滑块的排队一个个滑”：
     *  1. 所有账号先各自无干预签一次。零门槛的当场签上（已签过的也算成功），
     *     不会因为别人的验证码被卡住。
     *  2. 只有服务端真的返回 `validate` 的账号进队列，**一次只弹一个**，标题写清账号+活动，
     *     滑完立刻用该账号自己的会话带 validate 重发；取消/超时只把该账号标成「未签上（需人工）」，
     *     继续下一个，不中断整批。
     *
     * @param qr 二维码活动的扫码结果（其它类型传 null）
     * @param qrEnc 没扫码但通过 refreshQRCode 拿到了 enc 时使用
     * @param photoBytes 拍照签到用图
     * @param askHuman 弹人机验证的实现；传 null 表示没人守着（如巡检），只标记不弹
     */
    suspend fun execute(
        target: Detected,
        accounts: List<Account>,
        qr: QrPayload? = null,
        signCode: String? = null,
        photoBytes: ByteArray? = null,
        qrEnc: String? = null,
        onProgress: (String) -> Unit = {},
        onResult: (SignEngine.Outcome) -> Unit = {},
        askHuman: (suspend (SignFlow.CaptchaPrompt) -> String?)? = null
    ): List<SignEngine.Outcome> {
        val targets = accounts.filter { it.id in target.accountIds }
        val results = mutableListOf<SignEngine.Outcome>()
        // 扫码 / 拿到 enc 的时刻只算一次：动态二维码的 enc 只有 ewmRefreshTime（实测 5/10/20 秒），
        // 多账号顺序签时后面的账号早就过期了，得让 CxApi 知道真实时间。
        val encAt = System.currentTimeMillis()

        fun emit(o: SignEngine.Outcome) {
            results.add(o)
            onResult(o)
            LogStore.add("[${o.title}] ${o.detail}", o.ok)
        }

        // 阶段一：无人干预尝试。要验证码的先记下来，不打扰用户
        val pending = mutableListOf<PendingSign>()
        targets.forEachIndexed { i, acc ->
            onProgress("正在签 ${acc.display}（${i + 1}/${targets.size}）· ${target.title}")
            val (s, err) = SignEngine.sessionFor(acc)
            if (s == null) {
                emit(fail(acc, target, "登录失败：$err"))
                return@forEachIndexed
            }
            val api = CxApi(s)

            // 用本账号的会话重新拉一次活动详情：可能带上"我是否已签"等账号相关状态
            val act = runCatching { SignEngine.activityByAid(api, target.aid) }
                .getOrElse {
                    // 拉不到就用检测阶段的详情，至少 courseId/classId 是对的
                    SignEngine.ActiveActivity(
                        target.detail, Course(target.courseId, target.classId), JSONObject()
                    )
                }

            val opt = SignOptions(
                // 二维码：enc 先看扫到的那份，再退回 refreshQRCode 拿到的那份；rcode 只能是扫码原文
                enc = qr?.enc?.takeIf { it.isNotBlank() } ?: qrEnc,
                rcode = qr?.raw,
                // 手势 / 签到码：优先用用户输入的，否则用服务端给的
                signCode = signCode?.takeIf { it.isNotBlank() }
                    ?: target.serverSignCode.takeIf { it.isNotBlank() },
                aid = target.aid,
                photoBytes = photoBytes,
                // 扫码入口是 15，课堂入口是 0
                appType = if (qr != null) CxApi.APP_TYPE_SCAN else CxApi.APP_TYPE_COURSE,
                encAt = if (qr != null || qrEnc != null) encAt else 0L
            )

            val o = runCatching { SignEngine.signOneActivity(acc, api, act, opt) }
                .getOrElse { fail(acc, target, "异常：${it.message ?: it.javaClass.simpleName}") }

            // 已经签上（成功 / 已签过 / 判失败）的都直接出结果，只有“要验证码”的进队列
            if (o.status == SignEngine.SignStatus.NEED_CAPTCHA) {
                pending.add(PendingSign(acc, api, act, opt))
            } else {
                emit(o)
            }

            // 账号间隔，避免同一秒齐刷刷提交
            if (i < targets.size - 1) com.xxt.sign.core.Jitter.betweenAccounts()
        }

        // 阶段二：要滑块的账号排队，一次一个
        pending.forEachIndexed { i, p ->
            val who = "${p.acc.display} · ${target.title}"
            if (askHuman == null) {
                // 没人守着（只可能是巡检），不要弹也不要阻塞，直接标记
                emit(needsHuman(p.acc, target, "服务端要求人机验证"))
                return@forEachIndexed
            }
            onProgress("$who 需要人机验证（${i + 1}/${pending.size}），请滑一下")
            val validate = runCatching {
                HumanCaptcha.ask(
                    SignFlow.CaptchaPrompt(
                        p.acc.id, p.acc.display, target.title, target.aid,
                        "服务端要求人机验证"
                    ),
                    askHuman
                )
            }.getOrNull()

            if (validate.isNullOrBlank()) {
                // 超时/取消只影响这一个账号，继续下一个
                emit(needsHuman(p.acc, target, "人机验证未完成"))
                return@forEachIndexed
            }

            onProgress("${p.acc.display} 验证通过，正在重发签到…")
            val r = runCatching {
                SignEngine.signOneActivity(p.acc, p.api, p.act, p.opt, validate)
            }.getOrElse { fail(p.acc, target, "异常：${it.message ?: it.javaClass.simpleName}") }

            emit(
                when {
                    r.ok -> r.copy(detail = r.detail + "（人工过验证码）")
                    r.status == SignEngine.SignStatus.NEED_CAPTCHA -> r.copy(
                        detail = r.detail + "（滑块后仍要求验证）",
                        status = SignEngine.SignStatus.NEED_HUMAN
                    )
                    else -> r
                }
            )
        }
        return results
    }

    /** 排队等人工的账号：连它自己的会话一起留着，validate 只能用在它自己身上 */
    private class PendingSign(
        val acc: Account,
        val api: CxApi,
        val act: SignEngine.ActiveActivity,
        val opt: SignOptions
    )

    private fun needsHuman(acc: Account, target: Detected, why: String) = SignEngine.Outcome(
        acc.id, acc.display, "${target.title} ｜ $why，未签上（需人工）", false,
        SignEngine.SignStatus.NEED_HUMAN
    )

    private fun fail(acc: Account, target: Detected, msg: String) = SignEngine.Outcome(
        acc.id, acc.display, "${target.title} ｜ $msg", false, SignEngine.SignStatus.FAILED
    )

    /**
     * best-effort：试一次 refreshQRCode 拿动态二维码的 enc。
     * 拿不到（未开始 / 已结束 / 无权限）就返回 null，由 UI 退回「请扫二维码」。
     * 这里不抛异常、不报错，失败不影响原流程。
     */
    suspend fun tryRefreshEnc(accounts: List<Account>, aid: String): String? {
        accounts.forEach { acc ->
            val (s, _) = SignEngine.sessionFor(acc)
            if (s == null) return@forEach
            val enc = runCatching { CxApi(s).refreshQRCode(aid) }.getOrNull()
            if (!enc.isNullOrBlank()) return enc
        }
        return null
    }
}
