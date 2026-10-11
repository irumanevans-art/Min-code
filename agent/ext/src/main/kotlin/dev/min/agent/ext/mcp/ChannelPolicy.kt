package dev.min.agent.ext.mcp

import dev.min.agent.ext.config.ConfigSource
import dev.min.agent.ext.config.SettingsLayer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 用户点名要开的一个 channel（CC `--channels` / `--dangerously-load-development-channels` 的一项）：
 * `plugin:<name>@<marketplace>`（该插件清单里声明为 channel 的 MCP 服务器）或 `server:<name>`（配置里的 MCP 服务器，如 .mcp.json 的条目）。
 */
sealed interface ChannelRef {
    val label: String

    data class Plugin(val name: String, val marketplace: String) : ChannelRef {
        override val label: String get() = "plugin:$name@$marketplace"
    }

    data class Server(val name: String) : ChannelRef {
        override val label: String get() = "server:$name"
    }

    companion object {
        /** 解析一项；不合 CC 语法返回 null（`plugin:` 必须带 `@`，名字不能为空、不能含空白） */
        fun parse(text: String): ChannelRef? {
            val body = text.trim()
            return when {
                body.startsWith("plugin:") -> {
                    val rest = body.removePrefix("plugin:")
                    val name = rest.substringBefore('@', "")
                    val market = rest.substringAfter('@', "")
                    if (name.isEmpty() || market.isEmpty() || '@' in market || rest.any { it.isWhitespace() }) null else Plugin(name, market)
                }
                body.startsWith("server:") -> body.removePrefix("server:").takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }?.let(::Server)
                else -> null
            }
        }
    }
}

/**
 * 一次 channel 请求。[development] = 走 `--dangerously-load-development-channels`：绕过「已批准」检查，
 * 但**宿主必须先让用户确认过**才能传 true（CLI 弹确认、App 弹对话框）；内核不替宿主确认。
 */
data class ChannelRequest(val ref: ChannelRef, val development: Boolean = false)

/**
 * 托管设置对 channel 的管控（CC channels「Enterprise controls」）。只读 managed 层——用户 / 项目层写了不算数。
 *
 * - `channelsEnabled`：总开关。**有 managed 层、但没写 `channelsEnabled: true`** 时 channel 一律不开（CC：Console 组织部署了
 *   托管设置就要显式打开）；没有任何 managed 层（个人使用）时放行。关着时连开发参数也挡。
 * - `allowedChannelPlugins`：`[{marketplace, plugin}]`。Min 没有 Anthropic 维护的批准清单，所以没写它时的口径是
 *   「用户在 `--channels` 里点了名的插件就算批准」；写了就以它为准（空数组 = 全挡，开发参数仍能绕过）。
 */
class ChannelPolicy(
    val enabled: Boolean,
    /** [enabled] 为 false 时给人看的原因 */
    val disabledReason: String?,
    private val allowedPlugins: List<ChannelRef.Plugin>?,
) {
    /** [request] 能不能开。开发参数只绕过 [allowedPlugins]，绕不过 [enabled] */
    fun admits(request: ChannelRequest): Boolean = when {
        !enabled -> false
        request.development -> true
        else -> when (val ref = request.ref) {
            is ChannelRef.Server -> false
            is ChannelRef.Plugin -> allowedPlugins == null || ref in allowedPlugins
        }
    }

    /** 被拒的原因（给 Note / 面板）；放行时 null */
    fun refusal(request: ChannelRequest): String? = when {
        admits(request) -> null
        !enabled -> disabledReason
        request.ref is ChannelRef.Server -> "server: 形式不在批准范围内：它只能走开发参数 --dangerously-load-development-channels（需要确认）"
        else -> "${request.ref.label} 不在组织批准的 channel 插件名单里（allowedChannelPlugins）"
    }

    companion object {
        /** 没有任何托管层、没有名单的默认口径（个人使用） */
        val Open = ChannelPolicy(true, null, null)

        fun fromLayers(layers: List<SettingsLayer>): ChannelPolicy {
            val managed = layers.filter { it.source == ConfigSource.Managed }
            if (managed.isEmpty()) return Open
            val flag = managed.lastOrNull { it.raw.containsKey("channelsEnabled") }?.raw?.get("channelsEnabled")
                ?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull }
            val allowed = managed.lastOrNull { it.raw.containsKey("allowedChannelPlugins") }?.raw?.get("allowedChannelPlugins")
                ?.let { value -> (value as? JsonArray)?.mapNotNull(::allowedPlugin).orEmpty() }
            return when (flag) {
                true -> ChannelPolicy(true, null, allowed)
                false -> ChannelPolicy(false, "组织的托管设置关闭了 channel（channelsEnabled: false）", allowed)
                null -> ChannelPolicy(false, "组织部署了托管设置但没有开启 channel（需要 channelsEnabled: true）", allowed)
            }
        }

        private fun allowedPlugin(entry: kotlinx.serialization.json.JsonElement): ChannelRef.Plugin? {
            val obj = entry as? JsonObject ?: return null
            val market = (obj["marketplace"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val plugin = (obj["plugin"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return ChannelRef.Plugin(plugin, market)
        }
    }
}
