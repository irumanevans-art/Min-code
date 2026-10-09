package dev.min.agent.ext.config

import dev.min.agent.spi.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class NetworkDomainEntry(val domain: String, val denied: Boolean, val path: GuestPath, val source: ConfigSource, val permissionRule: String? = null)
data class NetworkSettingsView(val settings: NetworkSettings, val domains: List<NetworkDomainEntry>)

/** 编辑保留其他设置；内核「总是允许」与设置页用同一把锁，避免互相覆盖名单。 */
object NetworkSettingsEditor {
    private val lock = Mutex()
    suspend fun view(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath, trust: Boolean = true): NetworkSettingsView {
        val snapshot = SettingsLoader.load(fs, cwd, home)
        check(!snapshot.managedPolicyBroken) { "托管网络设置不可读" }
        val settings = snapshot.merged.networkSettings(trust)
        val entries = snapshot.layers.flatMap { layer ->
            val network = (layer.raw["sandbox"] as? JsonObject)?.get("network") as? JsonObject
            fun entries(key: String, denied: Boolean) = (network?.get(key) as? JsonArray).orEmpty().mapNotNull { raw ->
                (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { NetworkDomainEntry(it, denied, layer.path, layer.source) }
            }
            val rules = listOf(false to layer.permissions.allow, true to layer.permissions.deny).flatMap { (denied, rules) ->
                rules.mapNotNull { rule -> Regex("^WebFetch\\(domain:([^()]+)\\)$").matchEntire(rule)?.groupValues?.get(1)?.takeIf(NetworkRules::validPattern)?.let { NetworkDomainEntry(it, denied, layer.path, layer.source, rule) } }
            }
            (entries("allowedDomains", false) + entries("deniedDomains", true) + rules).filter { entry -> entry.domain in if (entry.denied) settings.deniedDomains else settings.allowedDomains }
        }
        return NetworkSettingsView(settings, entries.distinct())
    }
    suspend fun setEnabled(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath, value: Boolean, trust: Boolean = true) {
        check(!view(fs, cwd, home, trust).settings.enabledManaged) { "开关由托管设置控制" }
        // 修改当前覆盖开关的可信来源，避免用户层写入被项目层覆盖而看似无效。
        val target = SettingsLoader.load(fs, cwd, home).layers.lastOrNull { layer ->
            layer.source != ConfigSource.Managed && (trust || layer.source == ConfigSource.User) &&
                ((layer.raw["sandbox"] as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull != null
        }?.path ?: GuestPath("${home.value}/.claude/settings.json")
        edit(fs, target) { raw ->
            val sandbox = objectAt(raw, "sandbox")
            JsonObject(raw + ("sandbox" to JsonObject(sandbox + ("enabled" to JsonPrimitive(value)))))
        }
    }
    suspend fun allow(fs: WorkspaceFs, home: GuestPath, entry: String) {
        require(NetworkRules.validPattern(entry))
        edit(fs, GuestPath("${home.value}/.claude/settings.json")) { raw ->
            val sandbox = objectAt(raw, "sandbox")
            val network = objectAt(sandbox, "network")
            val domains = network["allowedDomains"]?.let { require(it is JsonArray); it } ?: JsonArray(emptyList())
            JsonObject(raw + ("sandbox" to JsonObject(sandbox + ("network" to JsonObject(network + ("allowedDomains" to JsonArray((domains + JsonPrimitive(entry)).distinct())))))))
        }
    }
    suspend fun remove(fs: WorkspaceFs, entry: NetworkDomainEntry) {
        require(entry.source != ConfigSource.Managed) { "托管名单只读" }
        edit(fs, entry.path) { raw ->
            if (entry.permissionRule != null) {
                val permissions = objectAt(raw, "permissions")
                val key = if (entry.denied) "deny" else "allow"
                val rules = permissions[key] as? JsonArray ?: error("权限名单无效")
                JsonObject(raw + ("permissions" to JsonObject(permissions + (key to JsonArray(rules.filterNot { (it as? JsonPrimitive)?.content == entry.permissionRule })))))
            } else {
                val sandbox = objectAt(raw, "sandbox"); val network = objectAt(sandbox, "network")
                val key = if (entry.denied) "deniedDomains" else "allowedDomains"
                val domains = network[key] as? JsonArray ?: error("域名名单无效")
                JsonObject(raw + ("sandbox" to JsonObject(sandbox + ("network" to JsonObject(network + (key to JsonArray(domains.filterNot { (it as? JsonPrimitive)?.content == entry.domain })))))))
            }
        }
    }
    private fun objectAt(raw: JsonObject, key: String): JsonObject = raw[key]?.let { require(it is JsonObject) { "$key 设置格式无效" }; it } ?: JsonObject(emptyMap())
    private suspend fun edit(fs: WorkspaceFs, path: GuestPath, update: (JsonObject) -> JsonObject) = lock.withLock {
        val raw = if (fs.stat(path) == null) JsonObject(emptyMap()) else Json.parseToJsonElement(fs.read(path).decodeToString()).jsonObject
        fs.writeAtomic(path, update(raw).toString().toByteArray())
    }
}
