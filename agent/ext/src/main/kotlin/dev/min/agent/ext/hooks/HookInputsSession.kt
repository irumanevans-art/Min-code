package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 会话生命周期类事件的 stdin 专属字段（R1a.2）：SessionStart、Setup、InstructionsLoaded、SessionEnd。
 * 通用字段（session_id、cwd、permission_mode……）在 [HookInputCommon]，这里只收各事件专属的。
 */

/** SessionStart 输入：会话开始或恢复时触发。仅支持 command 与 mcp_tool 类型；交互启动时后台运行 */
@Serializable
data class SessionStartInput(
    /** startup / resume / clear / compact / fork（matcher 同值；v2.1.214 前 fork 报 resume） */
    val source: String? = null,
    /** 模型名，可缺席 */
    val model: String? = null,
    /** 会话标题 */
    @SerialName("session_title") val sessionTitle: String? = null,
    /** resume/fork 且转录有响应时携带：距上次响应的秒数（v2.1.251+） */
    @SerialName("seconds_since_last_response") val secondsSinceLastResponse: Double? = null,
    /** resume/fork 且转录有响应时携带：上下文 token 数（v2.1.251+） */
    @SerialName("context_tokens") val contextTokens: Long? = null,
    /** resume/fork 且转录有响应时携带：提示缓存多半已过期（v2.1.251+） */
    @SerialName("prompt_cache_likely_expired") val promptCacheLikelyExpired: Boolean? = null,
    /** resume/fork 且转录有响应时携带：估算的缓存写入费用（v2.1.251+） */
    @SerialName("estimated_cache_write_usd") val estimatedCacheWriteUsd: Double? = null,
)

/** Setup 输入：`--init-only` 或 `-p` 模式的 `--init`/`--maintenance` 触发 */
@Serializable
data class SetupInput(
    /** init / maintenance（matcher 同值） */
    val trigger: String? = null,
)

/** InstructionsLoaded 输入：CLAUDE.md 或 .claude/rules/ 下的 *.md 载入上下文时触发。Claude 经 Project instructions 直读 AGENTS.md 时不触发 */
@Serializable
data class InstructionsLoadedInput(
    /** 载入的文件路径 */
    @SerialName("file_path") val filePath: String? = null,
    /** User / Project / Local / Managed */
    @SerialName("memory_type") val memoryType: String? = null,
    /** session_start / nested_traversal / path_glob_match / include / compact（matcher 对它） */
    @SerialName("load_reason") val loadReason: String? = null,
    /** 命中它的 glob 列表（文档未写单元素形状，按复数收成字符串列表） */
    val globs: List<String>? = null,
    /** 触发本次载入的文件路径 */
    @SerialName("trigger_file_path") val triggerFilePath: String? = null,
    /** 父文件路径（include 场景） */
    @SerialName("parent_file_path") val parentFilePath: String? = null,
)

/** SessionEnd 输入：会话终止时触发 */
@Serializable
data class SessionEndInput(
    /** clear / resume / logout / prompt_input_exit / other（bypass_permissions_disabled 已于 v2.1.234 移除） */
    val reason: String? = null,
)
