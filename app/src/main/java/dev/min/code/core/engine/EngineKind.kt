package dev.min.code.core.engine

import kotlinx.serialization.Serializable

/** 三个会话引擎。界面不按它写分支，只有 `EngineUi` 一处按它分派（见 [AgentSession] 头注释的三条规矩） */
@Serializable
enum class EngineKind { ClaudeCode, Codex, MinAgent }

/**
 * 会话的全局标识。[id] 是引擎自己的：CLI 的 session uuid、Codex 的 threadId、内核的 SessionId（`s_…`）。
 *
 * 通知 id、草稿、元数据都按 [storageKey] 存，不按 [id]：三个引擎的 id 空间互相独立，理论上可能撞。
 */
@Serializable
data class SessionRef(val engine: EngineKind, val id: String) {
    /**
     * 草稿、元数据、通知 id 的键。Claude Code 沿用裸 id（不迁移旧数据，`ClaudeCodeSessionMetaStore` 与
     * `ComposerDraftStore` 里已经存着的键原样有效），其余带前缀。
     */
    val storageKey: String
        get() = when (engine) {
            EngineKind.ClaudeCode -> id
            EngineKind.Codex -> CODEX_PREFIX + id
            EngineKind.MinAgent -> MIN_PREFIX + id
        }

    companion object {
        private const val CODEX_PREFIX = "codex:"
        private const val MIN_PREFIX = "min:"

        /**
         * [storageKey] 的逆。通知、草稿索引里只剩一个字符串时用它认回会话。
         * 全函数：没有前缀的一律是 Claude Code（旧数据的形状），所以它永不失败、也不会把裸 id 错认成别家。
         * 代价是 CLI 的 session id 以后若出现 `codex:` / `min:` 开头就会错认 —— 它是 uuid，不会。
         */
        fun fromStorageKey(key: String): SessionRef = when {
            key.startsWith(CODEX_PREFIX) -> SessionRef(EngineKind.Codex, key.removePrefix(CODEX_PREFIX))
            key.startsWith(MIN_PREFIX) -> SessionRef(EngineKind.MinAgent, key.removePrefix(MIN_PREFIX))
            else -> SessionRef(EngineKind.ClaudeCode, key)
        }
    }
}
