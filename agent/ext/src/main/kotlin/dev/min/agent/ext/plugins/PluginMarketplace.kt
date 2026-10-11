package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.*
import dev.min.agent.spi.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID
import java.security.MessageDigest

const val THIRD_PARTY_PLUGIN_WARNING = "第三方插件能跑命令、能访问网络。请只添加你信任的来源；安装前核对组件。"
val OFFICIAL_MARKETPLACE_SOURCE = buildJsonObject { put("source", "github"); put("repo", "anthropics/claude-plugins-official") }
const val OFFICIAL_MARKETPLACE = "claude-plugins-official"

data class Marketplace(val name: String, val source: JsonObject, val installLocation: String?, val confirmed: Boolean, val autoUpdate: Boolean)
data class MarketplaceCatalog(val name: String, val owner: String, val plugins: List<JsonObject>)
data class InstalledPlugin(val id: String, val scope: String, val installPath: String, val projectPath: String?, val version: String, val enabled: Boolean)
data class PluginCommandRequest(val id: String, val command: String, val target: String, val sha256: String)

fun redactedPluginSource(source: JsonObject): JsonObject = JsonObject(source.mapValues { (key, value) ->
    when {
        key.lowercase() in setOf("headers", "token", "password", "authorization") -> JsonPrimitive("[已设置]")
        value is JsonObject -> redactedPluginSource(value)
        key in setOf("url", "registry") && value is JsonPrimitive && value.isString -> try {
            val uri = URI(value.content)
            JsonPrimitive(URI(uri.scheme, null, uri.host, uri.port, uri.path,
                uri.rawQuery?.split('&')?.joinToString("&") { part -> if (Regex("(?i)token|key|secret|password|signature|credential").containsMatchIn(part.substringBefore('='))) part.substringBefore('=') + "=[已设置]" else part }, null).toString())
        } catch (_: Exception) { JsonPrimitive("[地址]") }
        else -> value
    }
})

/** 预览只能由管理器产生；安装使用已拉取的同一份文件，确认后不能换成另一版远端内容。 */
class PluginInstallPreview internal constructor(val id: String, val components: LoadedPlugin, internal val staging: GuestPath, internal val entry: JsonObject, internal val owner: String, internal val fingerprint: String, internal val container: GuestPath, internal val resolvedVersion: String? = null)

class PluginMarketplace(
    private val fs: WorkspaceFs,
    private val host: PluginSourceHost,
    private val home: GuestPath,
    private val cwd: GuestPath,
    private val trusted: () -> Boolean,
    private val canonical: (GuestPath) -> GuestPath = { it },
    private val managedPath: GuestPath = SettingsLoader.DEFAULT_MANAGED_PATH,
    private val limits: PluginFetchLimits = PluginFetchLimits(),
    private val installationPath: (GuestPath) -> String = { it.value },
) {
    private val root = home.value.trimEnd('/') + "/.claude/plugins"
    private val owner = UUID.randomUUID().toString()
    private val previews = mutableSetOf<PluginInstallPreview>()
    private fun path(relative: String) = GuestPath("$root/$relative")
    private suspend fun readObject(file: GuestPath): JsonObject {
        val stat = fs.stat(file) ?: return JsonObject(emptyMap())
        require(stat.size <= 8L * 1024 * 1024) { "插件记录超过大小上限" }
        return Json.parseToJsonElement(fs.read(file, limit = 8 * 1024 * 1024).toString(Charsets.UTF_8)) as? JsonObject ?: error("插件记录必须是对象")
    }
    private suspend fun writeObject(file: GuestPath, value: JsonObject) = fs.writeAtomic(file, value.toString().toByteArray())
    private suspend fun settings() = SettingsLoader.load(fs, cwd, home, managedPath)
    private suspend fun marketsRaw(): JsonObject {
        val saved = readObject(path("known_marketplaces.json"))
        return if (OFFICIAL_MARKETPLACE in saved) saved else JsonObject(saved + (OFFICIAL_MARKETPLACE to buildJsonObject {
            put("source", OFFICIAL_MARKETPLACE_SOURCE); put("confirmed", true); put("autoUpdate", true)
        }))
    }
    private fun marketplace(name: String, raw: JsonObject) = Marketplace(name, raw["source"] as? JsonObject ?: error("市场记录缺 source"), raw.text("installLocation"), raw.bool("confirmed") == true, raw.bool("autoUpdate") == true)
    suspend fun markets(): List<Marketplace> = host.locked {
        val layers = settings().layers.filter { it.source !in setOf(ConfigSource.Project, ConfigSource.Local) || trusted() }
        marketsRaw().mapNotNull { (name, item) -> (item as? JsonObject)?.takeUnless { it.bool("disabled") == true }?.let {
            val market = marketplace(name, it)
            // 设置声明优先于磁盘开关；未信任项目不能借 autoUpdate 发起后台拉取。
            val override = layers.mapNotNull { layer ->
                ((layer.raw["extraKnownMarketplaces"] ?: layer.raw["additionalMarketplaces"]) as? JsonObject)?.get(name)?.let { declaration ->
                    (declaration as? JsonObject)?.takeIf { declared -> declared["source"] == market.source }?.bool("autoUpdate")
                }
            }.lastOrNull()
            market.copy(autoUpdate = override ?: market.autoUpdate)
        } }
    }
    private suspend fun allowed(source: JsonObject) {
        val snapshot = settings()
        require(!snapshot.managedPolicyBroken) { "托管市场策略不可用" }
        val managed = snapshot.layers.lastOrNull { it.source == ConfigSource.Managed }?.raw.orEmpty()
        val strict = managed["strictKnownMarketplaces"] ?: managed["allowedMarketplaces"]
        val blocked = managed["blockedMarketplaces"]
        require(blocked == null || blocked is JsonArray) { "托管 blockedMarketplaces 格式无效" }
        require(strict == null || strict is JsonArray) { "托管 strictKnownMarketplaces 格式无效" }
        require((blocked as? JsonArray)?.none { (it as? JsonObject)?.let { rule -> matches(rule, source) } == true } != false) { "市场被托管黑名单禁止" }
        require(strict == null || (strict as JsonArray).any { (it as? JsonObject)?.let { rule -> matches(rule, source) } == true }) { "市场不在托管 strictKnownMarketplaces 名单内" }
    }
    private fun matches(rule: JsonObject, source: JsonObject): Boolean {
        val type = rule.text("source") ?: return false
        return when (type) {
            "hostPattern" -> {
                val hostname = if (source.text("source") == "github") "github.com" else source.text("url")?.let { url ->
                    if (url.startsWith("git@")) url.substringAfter('@').substringBefore(':') else pluginUri(url).host
                }
                hostname != null && Regex(rule.text("hostPattern") ?: return false).containsMatchIn(hostname)
            }
            "pathPattern" -> source.text("source") in setOf("file", "directory") && source.text("path")?.let { Regex(rule.text("pathPattern") ?: return false).containsMatchIn(it) } == true
            "url" -> source.text("source") == type && source.text("url") == rule.text("url")
            "github" -> {
                if (source.text("source") != type || source.text("ref") != rule.text("ref")) false
                else {
                    val repo = rule.text("repo") ?: return false
                    if (repo.endsWith("/*") && repo.count { it == '*' } == 1) source.text("repo")?.substringBefore('/') == repo.substringBefore('/') && (rule.text("path") == null || rule.text("path") == source.text("path"))
                    else repo == source.text("repo") && rule.text("path") == source.text("path")
                }
            }
            else -> type == source.text("source") && listOf("url", "path", "ref").all { rule[it] == source[it] }
        }
    }
    private suspend fun market(name: String): Marketplace {
        val record = marketsRaw()[name] as? JsonObject ?: error("市场尚未添加：$name")
        require(record.bool("disabled") != true) { "市场已移除" }
        val result = marketplace(name, record)
        allowed(result.source)
        require(result.source == OFFICIAL_MARKETPLACE_SOURCE && name == OFFICIAL_MARKETPLACE || result.confirmed) { "第三方源尚未确认：$THIRD_PARTY_PLUGIN_WARNING" }
        return result
    }
    private fun fresh(label: String) = path(".staging/$label-${UUID.randomUUID()}")
    private suspend fun fetchedCatalog(source: JsonObject, name: String? = null): Pair<GuestPath, MarketplaceCatalog> {
        allowed(source)
        if (source.text("headersHelper") != null) checkCommandPolicy(source)
        val staging = fresh("market")
        try {
            host.fetch(fetchSource(source, plugin = false).let { fetch -> if (name == null) fetch else fetch.copy(helperEnvironment = fetch.helperEnvironment + ("CLAUDE_CODE_MARKETPLACE_NAME" to name)) }, staging, limits)
            val raw = readObject(GuestPath(staging.value + "/.claude-plugin/marketplace.json"))
            val catalog = parseCatalog(raw)
            require(catalog.name != OFFICIAL_MARKETPLACE || source == OFFICIAL_MARKETPLACE_SOURCE) { "官方市场名不能被第三方冒用" }
            return staging to catalog
        } catch (e: Exception) {
            withContext(NonCancellable) { host.remove(staging) }
            throw e
        }
    }
    suspend fun add(source: JsonObject, confirmed: Boolean = false, expectedName: String? = null): Marketplace = host.locked {
        require(source == OFFICIAL_MARKETPLACE_SOURCE || confirmed) { THIRD_PARTY_PLUGIN_WARNING }
        val (staging, catalog) = fetchedCatalog(source)
        try {
            require(expectedName == null || expectedName == catalog.name) { "声明的市场名称与清单不一致" }
            val existing = marketsRaw()[catalog.name] as? JsonObject
            require(existing == null || existing.bool("disabled") == true || existing["source"] == source) { "市场同名来源冲突" }
            val location = path("marketplaces/${catalog.name}/${UUID.randomUUID()}")
            host.copy(staging, location, limits)
            host.remove(staging)
            val record = buildJsonObject { put("source", source); put("installLocation", location.value); put("confirmed", true); put("autoUpdate", existing?.takeIf { it["source"] == source }?.bool("autoUpdate") ?: (source == OFFICIAL_MARKETPLACE_SOURCE)); put("lastUpdated", System.currentTimeMillis()) }
            writeObject(path("known_marketplaces.json"), JsonObject(marketsRaw() + (catalog.name to record)))
            declareMarket(catalog.name, record)
            retireMarketLocation(existing?.text("installLocation"), catalog.name)
            marketplace(catalog.name, record)
        } catch (e: Exception) { withContext(NonCancellable) { host.remove(staging) }; throw e }
    }
    private suspend fun declareMarket(name: String, record: JsonObject?) {
        val file = GuestPath(home.value + "/.claude/settings.json")
        val raw = readObject(file)
        val extra = (raw["extraKnownMarketplaces"] as? JsonObject).orEmpty().toMutableMap()
        if (record == null) extra.remove(name) else extra[name] = buildJsonObject { put("source", record.getValue("source")); record["autoUpdate"]?.let { put("autoUpdate", it) } }
        writeObject(file, JsonObject(raw + ("extraKnownMarketplaces" to JsonObject(extra))))
    }
    suspend fun refresh(name: String): MarketplaceCatalog = host.locked { refreshUnlocked(name) }
    private suspend fun refreshUnlocked(name: String): MarketplaceCatalog {
        val market = market(name)
        val (staging, catalog) = fetchedCatalog(market.source, name)
        if (catalog.name != name) { host.remove(staging); error("市场更新改变了名字") }
        val previous = marketsRaw().getValue(name).jsonObject
        val location = path("marketplaces/$name/${UUID.randomUUID()}")
        host.copy(staging, location, limits); host.remove(staging)
        writeObject(path("known_marketplaces.json"), JsonObject(marketsRaw() + (name to JsonObject(previous + mapOf("installLocation" to JsonPrimitive(location.value), "lastUpdated" to JsonPrimitive(System.currentTimeMillis()))))))
        retireMarketLocation(previous.text("installLocation"), name)
        return catalog
    }
    private suspend fun retireMarketLocation(location: String?, name: String) {
        val prefix = "$root/marketplaces/$name/"
        if (location != null && location.startsWith(prefix) && Regex("[a-f0-9-]{36}").matches(location.removePrefix(prefix)) && canonical(GuestPath(location)).value.startsWith(canonical(GuestPath(prefix)).value.trimEnd('/') + "/")) host.remove(GuestPath(location))
    }
    suspend fun browse(name: String): MarketplaceCatalog = host.locked {
        val market = market(name)
        if (market.installLocation == null) refreshUnlocked(name)
        else parseCatalog(readObject(GuestPath(market.installLocation + "/.claude-plugin/marketplace.json")))
    }
    private suspend fun checkCommandPolicy(marketSource: JsonObject? = null) {
        val snapshot = settings()
        require(!snapshot.managedPolicyBroken) { "托管市场策略不可用" }
        val managed = JsonObject(snapshot.layers.lastOrNull { it.source == ConfigSource.Managed }?.raw.orEmpty())
        if (marketSource != null && (managed["extraKnownMarketplaces"] as? JsonObject)?.values?.any { (it as? JsonObject)?.get("source") == marketSource } == true) return
        require(managed.bool("disableCommandPluginSources") != true && (managed.bool("allowManagedHooksOnly") != true || managed.bool("disableCommandPluginSources") == false)) { "托管设置禁止插件 command / headersHelper" }
    }
    private fun commandRequests(id: String, entry: JsonObject): List<PluginCommandRequest> {
        val source = entry["source"] as? JsonObject
        val commands = buildList {
            if (source?.text("source") == "command") add(source.text("command")!! to "plugin directory")
            entry.text("headersHelper")?.let { add(it to (source?.text("url") ?: error("headersHelper 需要 archive URL"))) }
        }
        return commands.map { (command, target) ->
            require(command.isNotBlank() && command.length <= 500 && command.all { it.code in 32..126 } && "    " !in command) { "无效插件命令" }
            PluginCommandRequest(id, command, target, MessageDigest.getInstance("SHA-256").digest("$id\u0000$command\u0000$target".toByteArray()).joinToString("") { "%02x".format(it) })
        }
    }
    suspend fun commandsToAccept(id: String): List<PluginCommandRequest> = host.locked {
        val ref = parseId(id)
        var market = market(ref.marketplace!!)
        if (market.installLocation == null) { refreshUnlocked(market.name); market = market(market.name) }
        val entry = parseCatalog(readObject(GuestPath(market.installLocation + "/.claude-plugin/marketplace.json"))).plugins.singleOrNull { it.text("name") == ref.name } ?: error("市场没有插件 $id")
        commandRequests(id, entry).also { if (it.isNotEmpty()) checkCommandPolicy() }
    }
    suspend fun preview(id: String, acceptedCommands: Set<String> = emptySet()): PluginInstallPreview = host.locked { previewUnlocked(id, acceptedCommands) }
    private suspend fun previewUnlocked(id: String, acceptedCommands: Set<String> = emptySet(), ranges: List<String> = emptyList()): PluginInstallPreview {
        val ref = parseId(id)
        var market = market(requireNotNull(ref.marketplace))
        if (market.installLocation == null) { refreshUnlocked(market.name); market = market(market.name) }
        val catalog = parseCatalog(readObject(GuestPath(market.installLocation + "/.claude-plugin/marketplace.json")))
        val entry = catalog.plugins.singleOrNull { it.text("name") == ref.name } ?: error("市场没有插件 $id")
        val source = entry.getValue("source")
        val commands = commandRequests(id, entry)
        if (commands.isNotEmpty()) {
            checkCommandPolicy()
            require(commands.all { it.sha256 in acceptedCommands }) { "请先确认命令与目标；command 或 headersHelper 已变化时必须重新确认" }
        }
        val stage = fresh("plugin")
        var resolvedVersion: String? = null
        suspend fun tagSource(fetch: PluginFetchSource, relative: Boolean): PluginFetchSource? {
            if (ranges.isEmpty() || fetch.type !in setOf("git", "github", "directory")) return null
            val tags = host.tags(fetch, ref.name).filter { tag -> ranges.all { pluginVersionSatisfies(tag.version, it) } }
            val tag = tags.maxWithOrNull { a, b -> comparePluginVersions(a.version, b.version) }
            require(tag != null || relative) { "Dependency \"$id\" has no git tag satisfying ${ranges.joinToString(" & ")}" }
            if (tag == null) return null
            resolvedVersion = tag.version
            val git = if (fetch.type == "directory") fetch.copy(type = "git", location = installationPath(GuestPath(fetch.location))) else fetch
            return git.copy(ref = tag.ref, sha = tag.sha)
        }
        try {
            if (source is JsonPrimitive && source.isString) {
                require(market.source.text("source") != "url") { "URL 清单不提供相对路径插件" }
                val relative = relativeSource(source.content, catalog = readObject(GuestPath(market.installLocation + "/.claude-plugin/marketplace.json")))
                val tagged = tagSource(fetchSource(market.source, false).copy(path = relative.removePrefix("./").takeUnless { it == "." }), true)
                if (tagged == null) host.copy(GuestPath(market.installLocation + relative.removePrefix(".")), stage, limits)
                else host.fetch(tagged, stage, limits)
            } else {
                val raw = source as? JsonObject ?: error("无效插件 source")
                var fetch = fetchSource(raw, plugin = true)
                if (fetch.type == "archive") {
                    val inherited = if (market.source.text("source") == "url" && sameOrigin(market.source.text("url")!!, fetch.location)) fetchSource(market.source, false) else null
                    entry.text("headersHelper")?.let { require(entry.bool("strict") == false) { "headersHelper 的条目必须 strict: false" } }
                    fetch = fetch.copy(headers = headerValues(entry), headersHelper = entry.text("headersHelper"), inheritedAuthentication = inherited?.copy(
                        helperEnvironment = inherited.helperEnvironment + ("CLAUDE_CODE_MARKETPLACE_NAME" to market.name)),
                        helperEnvironment = buildMap { put("CLAUDE_CODE_PLUGIN_NAME", ref.name); put("CLAUDE_CODE_PLUGIN_ARCHIVE_URL", fetch.location) }, scrubCredentials = true)
                }
                host.fetch(tagSource(fetch, false) ?: fetch, stage, limits)
            }
            val pluginRoot = archiveRoot(stage)
            val outcome = PluginLoader(fs, canonical).load(PluginRequest(pluginRoot.value, ref, entry))
            val plugin = outcome.plugin ?: error("插件清单无效：${outcome.errors.joinToString()}")
            require(outcome.errors.isEmpty()) { "插件组件无效：${outcome.errors.joinToString()}" }
            return PluginInstallPreview(id, plugin, pluginRoot, entry, owner, fingerprint(pluginRoot, entry), stage, resolvedVersion).also { previews += it }
        } catch (e: Exception) { withContext(NonCancellable) { host.remove(stage) }; throw e }
    }
    private suspend fun archiveRoot(stage: GuestPath): GuestPath {
        if (fs.stat(GuestPath(stage.value + "/.claude-plugin/plugin.json")) != null || fs.list(stage).any { it.name in setOf("commands", "skills", "hooks", "agents", ".mcp.json", ".lsp.json") }) return stage
        val children = fs.list(stage)
        return if (children.size == 1 && children.single().kind == FileKind.Directory) GuestPath(stage.value + "/" + children.single().name) else stage
    }
    private suspend fun fingerprint(root: GuestPath, entry: JsonObject): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(entry.toString().toByteArray())
        var bytes = 0L; var files = 0
        suspend fun walk(dir: GuestPath, depth: Int) {
            require(depth <= 64) { "插件树过深" }
            for (item in fs.list(dir).sortedBy { it.name }) {
                if (item.name == ".git") continue
                val file = GuestPath(dir.value + "/" + item.name)
                require(canonical(file).value.startsWith(canonical(root).value.trimEnd('/') + "/")) { "预览文件链接逃逸" }
                when (item.kind) {
                    FileKind.Directory -> walk(file, depth + 1)
                    FileKind.File -> {
                        val stat = requireNotNull(fs.stat(file)); bytes += stat.size; files++
                        require(bytes <= limits.expandedBytes && files <= limits.files) { "预览树超限" }
                        digest.update(file.value.removePrefix(root.value).toByteArray())
                        digest.update(0.toByte()); digest.update(fs.read(file, limit = limits.expandedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    }
                    else -> error("预览树包含链接或特殊文件")
                }
            }
        }
        walk(root, 0)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    suspend fun discard(preview: PluginInstallPreview) = host.locked {
        require(preview.owner == owner)
        if (previews.remove(preview)) host.remove(preview.container)
    }
    private suspend fun installationScope(scope: String) {
        require(scope in setOf("user", "project", "local")) { "作用域只能是 user / project / local" }
        require(scope == "user" || trusted()) { "工作区尚未信任，项目 / local 插件配置不生效" }
    }
    suspend fun install(preview: PluginInstallPreview, scope: String = "user", confirmed: Boolean = false): InstalledPlugin = host.locked {
        require(confirmed) { "请先核对安装组件并确认" }
        require(preview.owner == owner && preview in previews) { "安装预览已过期" }
        require(fingerprint(preview.staging, preview.entry) == preview.fingerprint) { "预览文件已变化，请重新核对组件" }
        installationScope(scope)
        val ref = parseId(preview.id)
        val rootMarket = market(requireNotNull(ref.marketplace))
        val rootCatalog = readObject(GuestPath(rootMarket.installLocation + "/.claude-plugin/marketplace.json"))
        val cross = (rootCatalog["allowCrossMarketplaceDependenciesOn"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
        val managed = settings().layers.lastOrNull { it.source == ConfigSource.Managed }?.enabledPlugins.orEmpty()
        val indexPath = path("installed_plugins.json")
        val oldIndex = readObject(indexPath)
        fun installedRequest(record: InstalledPlugin): PluginRequest {
            val ref = parseId(record.id)
            val metadata = ((oldIndex["plugins"] as? JsonObject)?.get(record.id) as? JsonArray)?.filterIsInstance<JsonObject>()?.lastOrNull { sameScope(it, scope) }
            return PluginRequest(path("cache/${ref.marketplace}/${ref.name}/${record.version}").value, ref, resolvedVersion = metadata?.text("resolvedVersion"))
        }
        val enabled = installedUnlocked().filter { it.enabled && it.scope == scope }.associateBy { it.id }
        val requirements = linkedMapOf<String, MutableList<String>>()
        // 已安装的约束也参与求交，更新一个插件不能偷偷破坏另一插件的依赖。
        for (record in installedUnlocked().filter { it.scope == scope && it.id != preview.id }) {
            val r = parseId(record.id)
            val loaded = PluginLoader(fs, canonical).load(installedRequest(record)).plugin
            loaded?.manifest?.dependencies.orEmpty().forEach { dep -> dep.version?.let { range ->
                val depId = if ('@' in dep.name) dep.name else dep.name + "@" + (dep.marketplace ?: r.marketplace)
                requirements.getOrPut(depId) { mutableListOf() }.add(range)
            } }
        }
        val ordered = linkedMapOf<String, PluginInstallPreview>()
        val visiting = mutableListOf<String>()
        val fetched = mutableListOf<PluginInstallPreview>()
        suspend fun visit(item: PluginInstallPreview) {
            require(item.id !in visiting) { "Dependency cycle detected: ${(visiting + item.id).joinToString(" -> ")}" }
            // 直接更新依赖本身时，也必须满足已有调用者的约束，不能只检查新图中的边。
            require(requirements[item.id].orEmpty().all { pluginVersionSatisfies(item.resolvedVersion ?: item.components.manifest?.version, it) }) { "Dependency \"${item.id}\" has conflicting version requirements" }
            if (item.id in ordered) return
            require(managed[item.id] != false) { "Dependency \"${item.id}\" is disabled by managed settings" }
            market(parseId(item.id).marketplace!!)
            visiting += item.id
            for (dependency in item.components.manifest?.dependencies.orEmpty()) {
                val depId = if ('@' in dependency.name) dependency.name else dependency.name + "@" + (dependency.marketplace ?: parseId(item.id).marketplace)
                val depRef = parseId(depId)
                require(depId !in visiting) { "Dependency cycle detected: ${(visiting + depId).joinToString(" -> ")}" }
                dependency.version?.let { range -> requirements.getOrPut(depId) { mutableListOf() }.also { if (range !in it) it += range } }
                val ranges = requirements[depId].orEmpty()
                require(managed[depId] != false) { "Dependency \"$depId\" is disabled by managed settings" }
                val scopeFlags = readObject(settingsFile(scope))["enabledPlugins"] as? JsonObject
                require(scopeFlags?.get(depId)?.jsonPrimitive?.booleanOrNull != false) { "Dependency \"$depId\" is disabled — enable it or remove the dependency" }
                if (depId in enabled) {
                    market(depRef.marketplace!!)
                    val record = enabled.getValue(depId)
                    val result = PluginLoader(fs, canonical).load(installedRequest(record))
                    if (ranges.all { pluginVersionSatisfies(result.plugin?.manifest?.version, it) }) continue
                }
                require(depRef.marketplace == parseId(item.id).marketplace || depRef.marketplace in cross) {
                    "Dependency \"$depId\" (required by ${item.id}) is in marketplace \"${depRef.marketplace}\", which is not in the allowlist. Set allowCrossMarketplaceDependenciesOn in the root marketplace."
                }
                require(managed[depId] != false) { "Dependency \"$depId\" is disabled by managed settings" }
                val prior = ordered[depId]
                val next = prior?.takeIf { ranges.all { range -> pluginVersionSatisfies(it.resolvedVersion ?: it.components.manifest?.version, range) } } ?: try {
                    ordered.remove(depId)
                    previewUnlocked(depId, ranges = ranges).also { fetched += it }
                }
                    catch(e: kotlinx.coroutines.CancellationException) { throw e }
                    catch(e: Exception) { error("Dependency \"$depId\" is not installed — ${e.message}") }
                require(ranges.all { pluginVersionSatisfies(next.resolvedVersion ?: next.components.manifest?.version, it) }) { "Dependency \"$depId\" has conflicting version requirements" }
                visit(next)
            }
            visiting.removeAt(visiting.lastIndex)
            ordered[item.id] = item
        }
        val settingsPath = settingsFile(scope)
        val oldSettings = readObject(settingsPath)
        var publishing = false
        try {
            // 先解析整个图再发布索引；缺失、环和授权失败不会留下半套启用状态。
            visit(preview)
            val records = (oldIndex["plugins"] as? JsonObject).orEmpty().toMutableMap()
            val flags = (oldSettings["enabledPlugins"] as? JsonObject).orEmpty().toMutableMap()
            var installed: InstalledPlugin? = null
            for ((id, item) in ordered) {
                require(fingerprint(item.staging, item.entry) == item.fingerprint) { "预览文件已变化，请重新核对组件" }
                val itemRef = parseId(id)
                val version = ((item.resolvedVersion ?: item.components.manifest?.version)?.takeIf { validPluginName(it) } ?: "unversioned") + "-" + item.fingerprint.take(12)
                val target = path("cache/${itemRef.marketplace}/${itemRef.name}/$version")
                if (fs.stat(target) == null) host.copy(item.staging, target, limits)
                require(fingerprint(target, item.entry) == item.fingerprint) { "缓存文件与已确认预览不一致" }
                val record = buildJsonObject {
                    put("scope", scope); put("installPath", installationPath(target)); put("version", version)
                    if (scope != "user") put("projectPath", installationPath(canonical(cwd)))
                    put("installedAt", System.currentTimeMillis()); put("lastUpdated", System.currentTimeMillis())
                    item.resolvedVersion?.let { put("resolvedVersion", it) }; put("marketplaceEntry", item.entry); put("componentInventory", item.componentSummary())
                    if ((item.entry["source"] as? JsonObject)?.text("source") == "command") put("commandSha256", commandRequests(id, item.entry).first().sha256)
                }
                val prior = (records[id] as? JsonArray).orEmpty().filterNot { it is JsonObject && sameScope(it, scope) }
                records[id] = JsonArray(prior + record)
                flags[id] = if (id == preview.id) flags[id] ?: JsonPrimitive(item.components.manifest?.defaultEnabled != false) else JsonPrimitive(true)
                if (id == preview.id) installed = InstalledPlugin(id, scope, installationPath(target), record.text("projectPath"), version, flags[id]!!.jsonPrimitive.boolean)
            }
            publishing = true
            writeObject(settingsPath, JsonObject(oldSettings + ("enabledPlugins" to JsonObject(flags))))
            writeObject(indexPath, JsonObject(oldIndex + mapOf("version" to JsonPrimitive(2), "plugins" to JsonObject(records))))
            requireNotNull(installed)
        } catch(e: Exception) {
            if (publishing) withContext(NonCancellable) { writeObject(settingsPath, oldSettings); writeObject(indexPath, oldIndex) }
            throw e
        } finally {
            withContext(NonCancellable) { (fetched + preview).forEach { previews.remove(it); host.remove(it.container) } }
        }

    }
    private fun sameScope(record: JsonObject, scope: String) = record.text("scope") == scope && (scope == "user" || record.text("projectPath") == installationPath(canonical(cwd)))
    private fun settingsFile(scope: String) = when (scope) { "user" -> GuestPath(home.value + "/.claude/settings.json"); "project" -> GuestPath(cwd.value + "/.claude/settings.json"); else -> GuestPath(cwd.value + "/.claude/settings.local.json") }
    private suspend fun setEnabledUnlocked(id: String, enabled: Boolean, scope: String) {
        parseId(id); installationScope(scope)
        val snapshot = settings(); require(!snapshot.managedPolicyBroken) { "托管策略不可用" }
        snapshot.layers.lastOrNull { it.source == ConfigSource.Managed }?.enabledPlugins?.get(id)?.let { require(it == enabled) { "插件状态由托管设置锁定" } }
        val file = settingsFile(scope); val raw = readObject(file); val flags = (raw["enabledPlugins"] as? JsonObject).orEmpty()
        writeObject(file, JsonObject(raw + ("enabledPlugins" to JsonObject(flags + (id to JsonPrimitive(enabled))))))
    }
    suspend fun setEnabled(id: String, enabled: Boolean, scope: String = "user") = host.locked { market(requireNotNull(parseId(id).marketplace)); setEnabledUnlocked(id, enabled, scope) }
    private suspend fun installedRecord(id: String, record: JsonObject): InstalledPlugin {
        val enabled = settings().layers.filter { trusted() || it.source !in setOf(ConfigSource.Project, ConfigSource.Local) }.mapNotNull { it.enabledPlugins[id] }.lastOrNull() ?: false
        return InstalledPlugin(id, record.text("scope") ?: "user", record.text("installPath") ?: error("安装记录缺路径"), record.text("projectPath"), record.text("version") ?: "unknown", enabled)
    }
    suspend fun installed(): List<InstalledPlugin> = host.locked { installedUnlocked() }
    private suspend fun installedUnlocked(): List<InstalledPlugin> {
        val result = mutableListOf<InstalledPlugin>()
        (readObject(path("installed_plugins.json"))["plugins"] as? JsonObject).orEmpty().forEach { (id, records) ->
            (records as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("scope") == "user" || it.text("projectPath") == installationPath(canonical(cwd)) }.forEach { result += installedRecord(id, it) }
        }
        return result
    }
    suspend fun uninstall(id: String, scope: String = "user") = host.locked {
        parseId(id); installationScope(scope)
        val raw = readObject(path("installed_plugins.json")); val records = (raw["plugins"] as? JsonObject).orEmpty().toMutableMap()
        val prior = (records[id] as? JsonArray).orEmpty()
        require(prior.any { it is JsonObject && sameScope(it, scope) }) { "该作用域未安装插件" }
        setEnabledUnlocked(id, false, scope)
        val remaining = prior.filterNot { it is JsonObject && sameScope(it, scope) }
        if (remaining.isEmpty()) records.remove(id) else records[id] = JsonArray(remaining)
        writeObject(path("installed_plugins.json"), JsonObject(raw + ("plugins" to JsonObject(records))))
        // 活跃会话仍可能执行旧版本 hook / MCP；只移除索引，文件留作旧会话的冻结依赖。
    }
    suspend fun removeMarket(name: String) = host.locked {
        require((readObject(path("installed_plugins.json"))["plugins"] as? JsonObject).orEmpty().none { (id, installs) -> id.substringAfter('@') == name && (installs as? JsonArray)?.isNotEmpty() == true }) { "请先卸载该市场在所有作用域的插件" }
        val record = marketsRaw()[name] as? JsonObject ?: error("市场不存在")
        allowed(record.getValue("source").jsonObject)
        writeObject(path("known_marketplaces.json"), JsonObject(marketsRaw() + (name to JsonObject(record + ("disabled" to JsonPrimitive(true))))))
        declareMarket(name, null)
        retireMarketLocation(record.text("installLocation"), name)
    }
    suspend fun setAutoUpdate(name: String, enabled: Boolean) = host.locked {
        market(name)
        val raw = marketsRaw(); val record = JsonObject(raw.getValue(name).jsonObject + ("autoUpdate" to JsonPrimitive(enabled)))
        writeObject(path("known_marketplaces.json"), JsonObject(raw + (name to record))); declareMarket(name, record)
    }
    /** 自动更新只改磁盘副本；组件清单扩大时留下提示，要求再次预览确认，不偷偷增加执行入口。 */
    suspend fun autoUpdate(): List<String> {
        val notices = mutableListOf<String>()
        for (market in markets().filter { it.autoUpdate }) {
            try {
                val installs = installed().filter { it.id.substringAfter('@') == market.name }
                if (installs.isEmpty()) continue
                refresh(market.name)
                for (record in installs) {
                    val preview = preview(record.id)
                    val old = host.locked { (readObject(path("installed_plugins.json"))["plugins"]?.jsonObject?.get(record.id) as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { sameScope(it, record.scope) } }
                    if (old?.text("componentInventory") != preview.componentSummary()) {
                        notices += "${record.id} 的组件或说明变化，请预览后更新"; discard(preview)
                    } else {
                        install(preview, record.scope, confirmed = true)
                        notices += "${record.id} 已更新，下个会话加载新版本"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { notices += "${market.name} 自动更新失败，请手动重试" }
        }
        return notices
    }
    /** 设置只自动登记已确认源；未信任项目在任何拉取之前过滤，不把 enabledPlugins 当作安装授权。 */
    suspend fun reconcileSettings(): List<String> {
        val pending = mutableListOf<String>()
        val snapshot = settings()
        require(!snapshot.managedPolicyBroken) { "托管策略不可用" }
        for (layer in snapshot.layers) {
            if (layer.source in setOf(ConfigSource.Project, ConfigSource.Local) && !trusted()) continue
            val extra = layer.raw["extraKnownMarketplaces"] ?: layer.raw["additionalMarketplaces"] ?: continue
            require(extra is JsonObject) { "extraKnownMarketplaces 必须是对象" }
            for ((name, item) in extra) {
                val source = (item as? JsonObject)?.get("source") as? JsonObject ?: error("市场设置缺 source")
                allowed(source)
                val known = markets().firstOrNull { it.name == name }
                if (known?.source == source && known.confirmed) continue
                if (name == OFFICIAL_MARKETPLACE && source == OFFICIAL_MARKETPLACE_SOURCE) continue
                pending += name
            }
        }
        return pending.distinct()
    }
    companion object {
        fun parseId(id: String): PluginRef {
            require(id.count { it == '@' } == 1 && validPluginName(id.substringBefore('@')) && validPluginName(id.substringAfter('@'))) { "插件需用 name@marketplace，名字只含字母数字、点、下划线、连字符，且以字母数字开头" }
            return PluginRef(id.substringBefore('@'), id.substringAfter('@'))
        }
        fun parseCatalog(raw: JsonObject): MarketplaceCatalog {
            val name = raw.text("name") ?: error("marketplace.json 缺 name")
            require(validPluginName(name) && name !in setOf("inline", "builtin", "skills-dir", "synced")) { "市场名字非法或保留" }
            val owner = (raw["owner"] as? JsonObject)?.text("name")?.takeIf { it.isNotBlank() } ?: error("marketplace.json 缺 owner.name")
            val entries = raw["plugins"] as? JsonArray ?: error("marketplace.json 缺 plugins 数组")
            val names = mutableSetOf<String>()
            val plugins = entries.map { item ->
                val entry = item as? JsonObject ?: error("插件条目必须是对象")
                val pluginName = entry.text("name") ?: error("插件缺 name")
                require(validPluginName(pluginName)) { "插件名字非法" }; require(names.add(pluginName)) { "市场同名插件冲突" }
                val source = entry["source"] ?: error("插件缺 source")
                if (source is JsonPrimitive && source.isString) relativeSource(source.content, raw)
                else fetchSource(source as? JsonObject ?: error("插件 source 必须是字符串或对象"), true)
                entry
            }
            return MarketplaceCatalog(name, owner, plugins)
        }
        private fun relativeSource(raw: String, catalog: JsonObject): String {
            val value = if (raw == "." || raw.startsWith("./")) raw else {
                require('/' !in raw && '\\' !in raw) { "插件相对路径须以 ./ 开头" }
                val base = (catalog["metadata"] as? JsonObject)?.text("pluginRoot") ?: error("裸插件路径需 metadata.pluginRoot")
                "$base/$raw"
            }
            require(value == "." || value.startsWith("./") && value.removePrefix("./").split('/').none { it == ".." || it.isEmpty() } && !value.contains('\\') && !value.contains(':')) { "插件相对路径穿越" }
            return value
        }
        fun sourceInput(raw: String): JsonObject {
            if (raw.trimStart().startsWith('{')) return Json.parseToJsonElement(raw).jsonObject
            if (raw in setOf("https://github.com/anthropics/claude-plugins-official", "https://github.com/anthropics/claude-plugins-official.git")) return OFFICIAL_MARKETPLACE_SOURCE
            val address = raw.substringBefore('#')
            val ref = raw.substringAfter('#', "").takeIf { it.isNotEmpty() }
            return buildJsonObject {
                when {
                    Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(raw) -> { put("source", "github"); put("repo", raw) }
                    address.startsWith("git@") || address.endsWith(".git") || address.startsWith("file://") || Regex("https://(?:github.com|gitlab.com|bitbucket.org)/[^?#]+(?<!\\.json)$").matches(address) -> { put("source", "git"); put("url", address); ref?.let { put("ref", it) } }
                    raw.startsWith("https://") || raw.startsWith("http://") -> { put("source", "url"); put("url", raw) }
                    else -> { put("source", if (raw.endsWith(".json")) "file" else "directory"); put("path", raw) }
                }
            }
        }
        fun fetchSource(raw: JsonObject, plugin: Boolean): PluginFetchSource {
            val type = raw.text("source") ?: error("source 对象缺类型")
            val location = when (type) { "github" -> raw.text("repo"); "file", "directory" -> raw.text("path"); "npm" -> raw.text("package"); "command" -> raw.text("command"); else -> raw.text("url") }?.takeIf { it.isNotBlank() } ?: error("source 缺位置字段")
            if (!plugin && type == "npm") error("NPM marketplace sources not yet implemented")
            require(type in (if (plugin) setOf("github", "url", "git-subdir", "archive", "npm", "command") else setOf("github", "git", "url", "file", "directory"))) { "source 类型不适用于此处" }
            if (type == "github") require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(location)) { "无效 github repo" }
            val subdir = raw.text("path").takeIf { type in setOf("git", "github", "git-subdir") }
            subdir?.let { require(!it.startsWith('/') && !it.contains('\\') && it.split('/').none { p -> p == ".." }) { "源子目录穿越" } }
            if (type == "git-subdir") require(subdir != null) { "git-subdir 缺 path" }
            raw.text("sha256")?.let { require(Regex("[0-9a-fA-F]{64}").matches(it)) { "无效 SHA256" } }
            return PluginFetchSource(if (plugin && type in setOf("url", "git-subdir")) "git" else type,
                if (type == "git-subdir" && Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(location)) "https://github.com/$location.git" else location,
                raw.text("ref"), raw.text("sha"), subdir, raw.text("sha256"), raw.text("version"), raw.text("registry"),
                (raw["timeout"] as? JsonPrimitive)?.intOrNull ?: 60, raw.text("mode") ?: "copy", headerValues(raw), raw.text("headersHelper"),
                helperEnvironment = buildMap { if (type == "url" && !plugin) put("CLAUDE_CODE_MARKETPLACE_URL", location) }, scrubCredentials = false)
        }
        private fun pluginUri(value: String): URI = try { URI(value) } catch (_: Exception) { error("插件源 URL 格式无效") }
        private fun sameOrigin(a: String, b: String): Boolean {
            fun origin(raw: String) = pluginUri(raw).let { listOf(it.scheme?.lowercase(), it.host?.lowercase(), if (it.port >= 0) it.port else if (it.scheme.equals("https", true)) 443 else 80) }
            return origin(a) == origin(b)
        }
        private fun headerValues(raw: JsonObject): Map<String, String> {
            val headers = raw["headers"] ?: return emptyMap()
            require(headers is JsonObject && headers.values.all { it is JsonPrimitive && it.isString }) { "headers 必须是字符串值对象" }
            return headers.mapValues { it.value.jsonPrimitive.content }
        }
    }
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

fun PluginInstallPreview.componentSummary(): String = buildString {
    val p = components
    appendLine("${id}\n${p.manifest?.description.orEmpty()}")
    appendLine("Commands: ${p.commands.joinToString { it.name }.ifEmpty { "—" }}")
    appendLine("Skills: ${p.skills.joinToString { it.name }.ifEmpty { "—" }}")
    appendLine("Agents: ${p.agents.joinToString { it.name }.ifEmpty { "—" }}")
    appendLine("Hooks: ${p.hooks.flatMap { (event, handlers) -> handlers.map { "$event / ${(it.raw["type"] as? JsonPrimitive)?.content ?: "command"}" } }.joinToString().ifEmpty { "—" }}")
    appendLine("MCP: ${p.mcpServers.joinToString { it.serverName }.ifEmpty { "—" }}")
    appendLine("Monitors: ${p.monitors.joinToString { it.name }.ifEmpty { "—" }}")
    appendLine("LSP: ${p.lspServers.joinToString { it.key }.ifEmpty { "—" }}")
    appendLine("Dependencies: ${p.manifest?.dependencies.orEmpty().joinToString { it.name }.ifEmpty { "—" }}")
    p.warnings.forEach { appendLine(it) }
}

fun pluginAutoUpdatesEnabled(environment: Map<String, String>): Boolean = environment["FORCE_AUTOUPDATE_PLUGINS"] == "1" ||
    listOf("DISABLE_UPDATES", "DISABLE_AUTOUPDATER", "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC").none { environment[it] == "1" }
