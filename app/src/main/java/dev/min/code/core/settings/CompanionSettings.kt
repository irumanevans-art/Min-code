package dev.min.code.core.settings

/**
 * 情感特供的设置。[enabled] 管两件事：会话结束后台提取记忆、开新会话时把记忆的只读副本注入。
 * 提取用的模型由用户自己选（[profileId] + [modelId]，可以和会话用的不是同一家）；没选时只注入、不提取。
 */
data class CompanionSettings(
    val enabled: Boolean = false,
    val profileId: String? = null,
    val modelId: String? = null,
)
