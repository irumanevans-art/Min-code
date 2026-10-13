package dev.min.code.core.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/**
 * 一张「有序列表 + 当前选中」的加密配置表（Claude 供应商表、Codex 供应商表）要做的那几件事。
 *
 * 两张表的增 / 删 / 挪 / 排序 / 复制 / 切换原先各抄了一份，细节一多就会漂（Codex 那边就曾经
 * 没有删除、编辑会把条目挪到末尾）。这里只放**两边完全一样**的规则；
 * 两边确实不一样的字段（Claude 的 `baseUrl` 要剥 `/v1`、Codex 的要保留）由调用方在
 * 传进来之前处理，不在这里分叉。
 *
 * 除 [commit] 外都是列表上的纯函数：越界、找不到的情况原样返回同一张表，
 * 调用方据此可以放心地每次拖动都调（见 [moveInList]）。
 */
internal class ProfileTable<T>(
    val key: Preferences.Key<String>,
    val activeKey: Preferences.Key<String>,
    private val id: (T) -> String,
    private val displayName: (T) -> String,
    /** 复制出来的那条：内容照搬，换 id 与名字，并且不继承统一条目绑定（见 `SettingsStore.duplicateProfile`） */
    private val duplicateOf: (source: T, newId: String, label: String) -> T,
    /** 「用户刚在明文风险确认框上放行了这一条」 */
    private val acknowledged: (T) -> T,
    val decode: (CredentialCipher, String?) -> List<T>,
    private val encode: (CredentialCipher, List<T>) -> String,
) {
    fun move(list: List<T>, itemId: String, delta: Int): List<T> =
        moveInList(list, delta) { id(it) == itemId }

    /**
     * 按 [order] 重排。没提到的条目**留在末尾**而不是被删掉：界面拿到的那份快照和此刻盘上的
     * 可能差一条（另一处刚加了一条、或者刚导入完），照 order 整表覆盖的话那一条就没了。
     */
    fun reorder(list: List<T>, order: List<String>): List<T> {
        val rank = order.withIndex().associate { (i, itemId) -> itemId to i }
        return list.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
    }

    fun remove(list: List<T>, itemId: String): List<T> = list.filterNot { id(it) == itemId }

    /** 按 id 就地覆盖，位置不变——`filterNot + 追加` 会让「改个备注名」把这条挪到最后一行 */
    fun replace(list: List<T>, entry: T): List<T> = list.map { if (id(it) == id(entry)) entry else it }

    /** 有就就地改，没有就追加到末尾 */
    fun upsert(list: List<T>, entry: T): List<T> =
        if (list.any { id(it) == id(entry) }) replace(list, entry) else list + entry

    /**
     * 复制 [sourceId] 并**插在它下面**：复制的意图几乎总是「照着这条改一个变体」，
     * 变体就该待在原件旁边。找不到时原样返回同一张表与 null。
     */
    fun duplicate(list: List<T>, sourceId: String, newId: String): Pair<List<T>, String?> {
        val index = list.indexOfFirst { id(it) == sourceId }
        if (index < 0) return list to null
        val source = list[index]
        val label = duplicateLabel(displayName(source), list.map(displayName).toSet())
        val copy = duplicateOf(source, newId, label)
        return list.toMutableList().apply { add(index + 1, copy) } to newId
    }

    /** [acknowledgeInsecure] 为真时顺手把这一条记成已放行，别再问第二遍 */
    fun acknowledge(list: List<T>, itemId: String, acknowledgeInsecure: Boolean): List<T> =
        if (acknowledgeInsecure) list.map { if (id(it) == itemId) acknowledged(it) else it } else list

    /**
     * 整表落盘，并维护 active。空表连键一起删（不留一块空密文）；
     * active 必须落在表里，否则「选中」每次都退回第一条，用户看到的会漂。
     */
    fun commit(p: MutablePreferences, cipher: CredentialCipher, items: List<T>, wantedActive: String) {
        if (items.isEmpty()) p.remove(key) else p[key] = encode(cipher, items)
        val resolved = resolveActiveIdBy(items, wantedActive, id)
        if (resolved.isBlank()) p.remove(activeKey) else p[activeKey] = resolved
    }
}
