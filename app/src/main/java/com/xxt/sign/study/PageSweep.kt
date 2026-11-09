package com.xxt.sign.study

/**
 * 翻 `knowledge/cards` 分页时的退出条件。
 *
 * 老代码把「这一页解析出 0 个任务点」当退出条件，可 `num` 翻过尾之后服务端会把同一页反复返回，
 * 永远不会是空的 —— 于是每章都老老实实翻满 61 页。一门 91 章的课 = 91 × 61 × ~0.7s 限速，
 * 真机上就表现成「正在把文档任务点做掉」卡 4 分钟、零日志、CPU 0%、线程全 sleeping。
 *
 * 现在只看「有没有新东西」：连续 [quietPages] 页没有新任务点就停，另有 [maxPages] 硬上限。
 * 纯逻辑，单测直接喂数字即可。
 */
class PageSweep(
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val quietPages: Int = 2
) {
    var pages: Int = 0
        private set

    private var quiet = 0

    /** 喂这一页新增（按 key 去重后）的任务点数；返回 true = 继续翻下一页 */
    fun afterNew(newItems: Int): Boolean {
        pages++
        quiet = if (newItems > 0) 0 else quiet + 1
        return pages < maxPages && quiet < quietPages
    }

    val hitLimit: Boolean get() = pages >= maxPages

    companion object {
        /** 一章真的不止 24 页的话，基本可以断定服务端在重复发同一页 */
        const val DEFAULT_MAX_PAGES = 24
    }
}
