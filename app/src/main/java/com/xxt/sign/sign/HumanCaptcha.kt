package com.xxt.sign.sign

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 人工滑块的**串行闸门**。
 *
 * 为什么需要它：
 *  - 验证码页面是全局唯一的（一次只能显示一个），而 validate 又是**和账号会话绑定**的，
 *    两个账号同时弹窗时，后弹的会盖掉前一个的挂起点，滑出来的 validate 会发给错的账号。
 *  - 巡检服务与手动签到可能同时跑，光靠「上层按账号串行」不够。
 *
 * 所以所有要打扰人的地方都从这里过：同时只会有一个人在滑，滑完立刻把 validate
 * 交还给**发起这次请求的那个账号**，其它账号继续排队。
 */
object HumanCaptcha {

    private val mutex = Mutex()

    /**
     * @param askHuman 真正弹窗并等待的实现（通常是 SignFragment 的页面跳转）
     * @return validate；用户取消 / 超时 / 页面不在 → null，调用方只把这一个账号标记为需要人工
     */
    suspend fun ask(
        prompt: SignFlow.CaptchaPrompt,
        askHuman: suspend (SignFlow.CaptchaPrompt) -> String?
    ): String? = mutex.withLock { askHuman(prompt) }
}
