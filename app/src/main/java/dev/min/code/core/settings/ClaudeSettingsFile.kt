package dev.min.code.core.settings

import kotlinx.serialization.json.JsonElement

/**
 * rootfs 里 `~/.claude/settings.json` 的读写口 —— [ProviderSync] 要的只有这两样。
 *
 * 实现在 claudecode 包（`ClaudeCodeConfigStore`）。以前 ProviderSync 直接引那个类，
 * settings 和 claudecode 两个包就互相依赖了：哪天要拆模块，这两个只能绑在一起拆。
 */
interface ClaudeSettingsFile {
    /** 整份原文；文件不存在或读不了是 null */
    suspend fun readSettingsText(): String?

    /** 读出 → 交给 [mutate] 改 → 写回。文件解析不了 / 写不进去返回 false */
    suspend fun updateSettings(mutate: (MutableMap<String, JsonElement>) -> Unit): Boolean
}
