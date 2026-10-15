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
     * @param qr 二维码活动的扫码结果（其它类型传 null）
     * @param photoBytes 拍照签到用图
     */
    suspend fun execute(
        target: Detected,
        accounts: List<Account>,
        qr: QrPayload? = null,
        signCode: String? = null,
        photoBytes: ByteArray? = null,
        onProgress: (String) -> Unit = {},
        onResult: (SignEngine.Outcome) -> Unit = {},
        askHuman: suspend (SignFlow.CaptchaPrompt) -> String? = { null },
        autoSolve: Boolean = true
    ): List<SignEngine.Outcome> {
        val targets = accounts.filter { it.id in target.accountIds }
        val results = mutableListOf<SignEngine.Outcome>()

        fun emit(o: SignEngine.Outcome) {
            results.add(o)
            onResult(o)
            LogStore.add("[${o.title}] ${o.detail}", o.ok)
        }

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
                // 二维码：enc 与 rcode 都用扫到的那一份，所有账号共用
                enc = qr?.enc,
                rcode = qr?.raw,
                // 手势 / 签到码：优先用用户输入的，否则用服务端给的
                signCode = signCode?.takeIf { it.isNotBlank() }
                    ?: target.serverSignCode.takeIf { it.isNotBlank() },
                aid = target.aid,
                photoBytes = photoBytes
            )

            val o = runCatching {
                SignFlow.signWithCaptcha(acc, api, act, opt, onProgress, askHuman, autoSolve)
            }.getOrElse { fail(acc, target, "异常：${it.message ?: it.javaClass.simpleName}") }
            emit(o)

            // 账号间隔，避免同一秒齐刷刷提交
            if (i < targets.size - 1) com.xxt.sign.core.Jitter.betweenAccounts()
        }
        return results
    }

    private fun fail(acc: Account, target: Detected, msg: String) = SignEngine.Outcome(
        acc.id, acc.display, "${target.title} ｜ $msg", false, SignEngine.SignStatus.FAILED
    )
}
