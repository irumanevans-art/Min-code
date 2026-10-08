package dev.min.agent.ext.plugins

import kotlinx.serialization.json.JsonObject

data class PluginBatch(
    val plugins: List<LoadedPlugin>,
    val settings: JsonObject,
    val errors: List<String>,
    val overrides: List<String>,
)

/** 候选只来自本地目录；安装、下载与来源授权由宿主另行处理，加载不能偷偷触网。 */
suspend fun PluginLoader.loadAll(requests: List<PluginRequest>): PluginBatch {
    val errors = mutableListOf<String>()
    val winners = linkedMapOf<String, LoadedPlugin>()
    for (request in requests) {
        val result = load(request)
        errors += result.errors
        result.plugin?.let { winners.remove(it.ref.id); winners[it.ref.id] = it }
    }
    val selected = linkedMapOf<String, LoadedPlugin>()
    val visiting = mutableListOf<String>()
    val failed = mutableSetOf<String>()
    fun enable(id: String): Boolean {
        if (id in selected) return true
        if (id in failed) return false
        if (id in visiting) {
            val cycle = visiting.dropWhile { it != id }
            failed += cycle
            errors += "插件循环依赖：${cycle.joinToString(" → ")}"
            return false
        }
        val plugin = winners[id] ?: return false.also { errors += "插件依赖不存在：$id" }
        visiting += id
        var ok = true
        for (dependency in plugin.manifest?.dependencies.orEmpty()) {
            val depName = dependency.name.substringBefore('@')
            val market = dependency.marketplace ?: dependency.name.substringAfter('@', "").ifEmpty { plugin.ref.marketplace }
            val depId = PluginRef(depName, market).id
            if (!enable(depId)) ok = false
        }
        visiting.removeAt(visiting.lastIndex)
        if (!ok || id in failed) { failed += id; return false }
        // defaultEnabled 是用户未选择时的默认值；依赖必须先启用，不能被默认关闭挡住。
        selected[id] = plugin.copy(enabled = true)
        return true
    }
    winners.values.filter { it.enabled }.forEach { enable(it.ref.id) }
    val settings = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    val overrides = mutableListOf<String>()
    selected.values.forEach { plugin -> plugin.settings.orEmpty().forEach { (key, value) ->
        if (key in settings) overrides += "${plugin.ref.id} overrides setting $key"
        settings[key] = value
    } }
    return PluginBatch(selected.values.toList(), JsonObject(settings), errors, overrides)
}
