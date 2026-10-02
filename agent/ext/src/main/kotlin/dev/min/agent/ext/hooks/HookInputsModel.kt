package dev.min.agent.ext.hooks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 模型切换类事件的 stdin 专属字段（R1a.2）：PreModelSwitch、PostModelSwitch（字段相同，source 取值不同）。
 */

/** PreModelSwitch 输入：模型切换前触发。自身 fallback/恢复切换不触发（走 PostModelSwitch source=auto/resume） */
@Serializable
data class PreModelSwitchInput(
    /** 原模型 */
    @SerialName("from_model") val fromModel: String? = null,
    /** 目标模型（matcher 对其 canonical 名，忽略 [1m]；无法定 canonical 时全部 hook 都跑） */
    @SerialName("to_model") val toModel: String? = null,
    /** 用户请求的原始写法：别名/全 id/null=默认（可缺席） */
    @SerialName("requested_model") val requestedModel: String? = null,
    /** command / picker / sdk */
    val source: String? = null,
    /** 当前上下文 token 数 */
    @SerialName("context_tokens") val contextTokens: Long? = null,
    /** 提示缓存当前是否热着 */
    @SerialName("prompt_cache_warm") val promptCacheWarm: Boolean? = null,
    /** 缓存 TTL：5m / 1h */
    @SerialName("cache_ttl") val cacheTtl: String? = null,
    /** 估算的缓存写入费用 */
    @SerialName("estimated_cache_write_usd") val estimatedCacheWriteUsd: Double? = null,
    /** 价格口径：configured / catalog / default */
    val pricing: String? = null,
)

/** PostModelSwitch 输入：模型切换后触发。字段同 PreModelSwitch，source 另有 auto/resume（requested_model 为 null/保存的设置）；fallback 链单轮替换不触发 */
@Serializable
data class PostModelSwitchInput(
    /** 原模型 */
    @SerialName("from_model") val fromModel: String? = null,
    /** 目标模型（matcher 对其 canonical 名，忽略 [1m]） */
    @SerialName("to_model") val toModel: String? = null,
    /** 用户请求的原始写法：别名/全 id/null=默认（可缺席） */
    @SerialName("requested_model") val requestedModel: String? = null,
    /** command / picker / sdk / auto / resume */
    val source: String? = null,
    /** 当前上下文 token 数 */
    @SerialName("context_tokens") val contextTokens: Long? = null,
    /** 提示缓存当前是否热着 */
    @SerialName("prompt_cache_warm") val promptCacheWarm: Boolean? = null,
    /** 缓存 TTL：5m / 1h */
    @SerialName("cache_ttl") val cacheTtl: String? = null,
    /** 估算的缓存写入费用 */
    @SerialName("estimated_cache_write_usd") val estimatedCacheWriteUsd: Double? = null,
    /** 价格口径：configured / catalog / default */
    val pricing: String? = null,
)
