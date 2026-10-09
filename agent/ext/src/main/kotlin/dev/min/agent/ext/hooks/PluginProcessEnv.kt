package dev.min.agent.ext.hooks

import dev.min.agent.ext.config.ConfigSource

/** 每个 handler 有自己的参数槽；不同插件的同名 OPTION 不会互相覆盖，真值不落脚本。 */
fun pluginProcessEnv(handlers: List<HookHandler>): Map<String, String> = buildMap {
    handlers.forEachIndexed { i, handler ->
        if (handler.source == ConfigSource.Plugin && handler.args != null) {
            val argv = handler.command?.let(::listOf).orEmpty() + handler.args
            argv.forEachIndexed { ai, value -> put("MIN_PLUGIN_${i}_ARG_$ai", value) }
        }
        handler.pluginEnv.values.forEachIndexed { ei, value -> put("MIN_PLUGIN_${i}_ENV_$ei", value) }
    }
}
