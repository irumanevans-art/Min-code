package dev.min.agent.cli

import dev.min.agent.ext.plugins.*
import dev.min.agent.host.FilePluginSources
import dev.min.agent.spi.GuestPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

internal fun desktopMarketplace(cwd: Path, trust: Boolean, userHome: Path = Path.of(System.getProperty("user.home"))): PluginMarketplace {
    val paths = DesktopPaths(cwd, userHome.resolve(".min-agent/root"))
    val logs = Files.createDirectories(userHome.resolve(".min-agent/exec-logs"))
    val home = paths.guestOfHost(userHome)
    return PluginMarketplace(paths.fs, FilePluginSources(paths.fs, LocalExec(paths::hostOf, logs, paths.guestOfHost(logs)), GuestPath(home.value + "/.claude/plugins")), home,
        paths.guestOfHost(cwd), { trust }, canonical = paths.fs::canonical, installationPath = { paths.hostOf(it).toString() })
}

internal suspend fun pluginCommand(manager: PluginMarketplace, arguments: List<String>, stdin: BufferedReader, output: PrintStream,
    sourcePath: (String) -> String = { it }): Int {
    val args = arguments.toMutableList()
    val yes = args.remove("--yes") or args.remove("-y")
    val json = args.remove("--json")
    var scope = "user"
    val scopeAt = args.indexOfFirst { it == "--scope" || it == "-s" }
    if (scopeAt >= 0) { if (scopeAt + 1 >= args.size) throw UsageException("--scope 缺值"); scope = args.removeAt(scopeAt + 1); args.removeAt(scopeAt) }
    fun confirm(message: String): Boolean {
        output.println(message)
        if (yes) return true
        output.print("确认？[y/N] "); output.flush()
        return stdin.readLine()?.trim()?.lowercase() in setOf("y", "yes")
    }
    try {
        when (val command = args.firstOrNull() ?: "browse") {
            "marketplace" -> when (args.getOrNull(1)) {
                "add" -> {
                    val raw = args.getOrNull(2) ?: throw UsageException("plugin marketplace add <source> [--yes]")
                    var source = PluginMarketplace.sourceInput(raw)
                    if ((source["source"] as? JsonPrimitive)?.content in setOf("file", "directory")) source = JsonObject(source + ("path" to JsonPrimitive(sourcePath(source.getValue("path").jsonPrimitive.content))))
                    val accepted = source == OFFICIAL_MARKETPLACE_SOURCE || confirm(THIRD_PARTY_PLUGIN_WARNING + "\n$source")
                    if (!accepted) return 1
                    output.println("已添加市场：${manager.add(source, confirmed = accepted).name}")
                }
                "list" -> manager.markets().forEach { output.println("${it.name}  ${it.source}  autoUpdate=${it.autoUpdate}") }
                "remove", "rm" -> { manager.removeMarket(args.getOrNull(2) ?: throw UsageException("marketplace remove <name>")); output.println("市场已移除") }
                "update" -> { for (name in args.getOrNull(2)?.let(::listOf) ?: manager.markets().map { it.name }) { manager.refresh(name); output.println("市场已更新：$name") } }
                "auto-update" -> { manager.setAutoUpdate(args.getOrNull(2) ?: throw UsageException("auto-update <name> on|off"), args.getOrNull(3) == "on"); output.println("自动更新设置已保存") }
                else -> throw UsageException("plugin marketplace add|list|update|remove|auto-update")
            }
            "browse" -> {
                val names = args.getOrNull(1)?.let(::listOf) ?: manager.markets().map { it.name }
                for (name in names) manager.browse(name).plugins.forEach { output.println("${it.getValue("name").jsonPrimitive.content}@$name  ${(it["description"] as? JsonPrimitive)?.content.orEmpty()}") }
            }
            "install", "i", "update", "details" -> {
                val id = args.getOrNull(1) ?: throw UsageException("plugin $command <name@marketplace>")
                if (command == "update") {
                    require(manager.installed().any { it.id == id && it.scope == scope }) { "该作用域未安装插件" }
                    manager.refresh(PluginMarketplace.parseId(id).marketplace!!)
                }
                val preview = manager.preview(id)
                try {
                    if (command == "details") output.println(preview.componentSummary())
                    else if (confirm(preview.componentSummary() + "\n插件能跑命令、能访问网络。作用域：$scope")) output.println("已安装：${manager.install(preview, scope, confirmed = true).id}；下个会话加载")
                    else return 1
                } finally { withContext(NonCancellable) { manager.discard(preview) } }
            }
            "list" -> {
                val installed = manager.installed()
                if (json) output.println(JsonArray(installed.map { buildJsonObject { put("id", it.id); put("scope", it.scope); put("enabled", it.enabled); put("version", it.version); put("installPath", it.installPath) } }))
                else installed.forEach { output.println("${it.id}  scope=${it.scope}  enabled=${it.enabled}  version=${it.version}") }
            }
            "uninstall", "remove", "rm" -> { manager.uninstall(args.getOrNull(1) ?: throw UsageException("plugin uninstall <id>"), scope); output.println("插件已卸载") }
            "enable", "disable" -> { manager.setEnabled(args.getOrNull(1) ?: throw UsageException("plugin $command <id>"), command == "enable", scope); output.println("插件状态已保存；下个会话加载") }
            "--help", "help" -> output.println("plugin marketplace add|list|update|remove <source/name>\nplugin browse [marketplace]\nplugin install|update|details|uninstall|enable|disable <name@marketplace> [--scope user|project|local] [--yes]\nplugin list [--json]")
            else -> throw UsageException("未知插件命令：$command")
        }
        return 0
    } catch (e: CancellationException) { throw e }
    catch (e: UsageException) { throw e }
    catch (e: Exception) { output.println(e.message ?: "插件操作失败"); return 1 }
}

internal suspend fun standalonePluginCommand(argv: Array<String>): Int {
    val args = argv.drop(1).toMutableList()
    val trust = args.remove("--trust")
    var cwd = Path.of("").toAbsolutePath()
    val i = args.indexOf("--cwd")
    if (i >= 0) { if (i + 1 >= args.size) throw UsageException("--cwd 缺值"); cwd = Path.of(args.removeAt(i + 1)).toAbsolutePath(); args.removeAt(i) }
    if (args.firstOrNull() == "eval") return pluginEvalCommand(args.drop(1), cwd)
    val paths = DesktopPaths(cwd, Path.of(System.getProperty("user.home"), ".min-agent/root"))
    return pluginCommand(desktopMarketplace(cwd, trust), args, System.`in`.bufferedReader(), System.out, { paths.guestOfHost(Path.of(it).toAbsolutePath()).value })
}
