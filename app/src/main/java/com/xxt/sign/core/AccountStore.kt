package com.xxt.sign.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * 账号存储。
 *
 * 这里有两道防护，因为「一堆账号被静默清空」是不可接受的失败模式：
 *  1. 解析失败时把原始内容备份到另一个 key，而不是静默返回空列表（否则下一次写入就把账号全覆盖了）；
 *  2. persist 默认拒绝把非空账号覆盖成空列表，只有显式删除才允许。
 */
object AccountStore {

    private lateinit var sp: SharedPreferences

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts

    fun init(c: Context) {
        sp = c.getSharedPreferences("xxt_prefs", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_ACCOUNTS, "[]") ?: "[]"
        _accounts.value = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Account.from(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // 读档失败不要直接丢：先把原始内容备份到别的 key，再继续。
            // （旧实现在这里静默返回空列表，一旦接着发生一次写入，用户账号就全没了）
            runCatching { sp.edit().putString(KEY_BACKUP, raw).apply() }
            LogStore.add(
                "[严重] 账号数据解析失败（原始长度 ${raw.length}）：${e.message}。" +
                    "原始内容已备份到 $KEY_BACKUP",
                false
            )
            emptyList()
        }
    }

    /** 是否存在读档失败备份（可在界面提示用户） */
    fun hasCorruptBackup(): Boolean =
        !(sp.getString(KEY_BACKUP, "") ?: "").isBlank()

    private fun persist(list: List<Account>, allowEmpty: Boolean = false) {
        if (list.isEmpty() && !allowEmpty) {
            val prev = runCatching {
                JSONArray(sp.getString(KEY_ACCOUNTS, "[]") ?: "[]").length()
            }.getOrDefault(0)
            if (prev > 0) {
                // 该路径不应发生：把它当成 bug 拦截，并留下现场
                LogStore.add("[严重] 拒绝把 $prev 个账号覆盖成空列表（疑似 bug，已阻止）", false)
                return
            }
        }

        val before = _accounts.value.size
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
        _accounts.value = list
        if (before != list.size) {
            LogStore.add("[存储] 账号数 $before → ${list.size}", true)
        }
    }

    @Synchronized
    fun upsert(a: Account) {
        val list = _accounts.value.toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        // 存副本，确保新列表与旧列表内容确实不同，StateFlow 才会因为内容变化而发射
        val stored = a.copy()
        if (i >= 0) list[i] = stored else list.add(stored)
        persist(list)
    }

    /** 只有用户显式删除才允许把列表写空 */
    @Synchronized
    fun remove(id: String) =
        persist(_accounts.value.filterNot { it.id == id }, allowEmpty = true)

    /**
     * 返回 Store 中存储实例的副本。
     * 必须返回副本：调用方（CxSession）会就地修改字段，
     * 如果改的是 Store 里那个实例，StateFlow 会因为新旧值 equals 相等而静默不发射，
     * 界面就永远停在“未登录”。
     */
    fun get(id: String): Account? = _accounts.value.firstOrNull { it.id == id }?.copy()

    /** 读档失败标记：仅在 init 解析失败时用于提示，不阻塞写入 */
    private const val KEY_BACKUP = "accounts_corrupt_backup"

    private const val KEY_ACCOUNTS = "accounts"
}
