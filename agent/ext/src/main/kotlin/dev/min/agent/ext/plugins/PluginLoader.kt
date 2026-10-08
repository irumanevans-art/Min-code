package dev.min.agent.ext.plugins

import dev.min.agent.ext.config.ConfigIo
import dev.min.agent.ext.config.GuestPaths
import dev.min.agent.spi.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** 只读本地目录；市场安装、LSP、monitors 与 prompt/agent/http hook 尚无运行端口，不能在发现时执行。 */
class PluginLoader(private val fs: WorkspaceFs, private val canonical: (GuestPath) -> GuestPath = { it }) {
    suspend fun load(request: PluginRequest): PluginLoadOutcome {
        val io = ConfigIo(fs)
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        try {
            val root = GuestPaths.normalize(request.root)
            val files = linkedMapOf<String, String>()
            val realRoot = canonical(GuestPath(root)).value
            val directories = mutableSetOf("")
            var textBytes = 0L
            if (io.kind(root) != FileKind.Directory) return PluginLoadOutcome(errors = listOf("plugin directory not found: $root"))
            val manifestPath = "$root/.claude-plugin/plugin.json"
            val manifestReal = canonical(GuestPath(manifestPath)).value
            if (!manifestReal.startsWith(realRoot.trimEnd('/') + "/")) return PluginLoadOutcome(errors = listOf("$manifestPath escapes plugin directory"))
            val manifestText = io.readText(manifestPath)
            if (manifestText == null && io.kind(manifestPath) != null) return PluginLoadOutcome(errors = io.notes.toList())
            val manifest = manifestText?.let { Json.parseToJsonElement(it) as? JsonObject }
            val declarations = PluginParser(emptySet()).collectPaths(manifest) + PluginParser(emptySet()).collectPaths(request.entry)
            val declared = declarations.mapNotNull { (_, raw) ->
                if (!raw.startsWith("./")) null else GuestPaths.join(root, raw).takeIf { it.startsWith(root.trimEnd('/') + "/") }?.removePrefix(root.trimEnd('/') + "/")
            }
            val roots = setOf(".claude-plugin", "commands", "agents", "skills", "hooks", "output-styles", "workflows", "themes", "monitors", "bin") + declared.map { it.substringBefore('/') }
            val defaultFiles = setOf("SKILL.md", "CLAUDE.md", "settings.json", ".mcp.json", ".lsp.json")
            suspend fun walk(dir: String, depth: Int, ancestors: Set<String>) {
                if (depth > ConfigIo.MAX_DEPTH) { errors += "插件组件目录嵌套过深：$dir"; return }
                for (e in io.entries(dir)) {
                    if (e.name == ".git") continue
                    val path = GuestPaths.join(dir, e.name)
                    val rel = path.removePrefix(root.trimEnd('/') + "/")
                    if (depth == 0 && e.name !in roots && e.name !in defaultFiles) continue
                    if (files.size > 4096 || textBytes > 8L * 1024 * 1024) { errors += "插件组件超过装载预算：$path"; continue }
                    val real = canonical(GuestPath(path)).value
                    if (real != realRoot && !real.startsWith(realRoot.trimEnd('/') + "/")) {
                        errors += "$path escapes plugin directory"; continue
                    }
                    when (io.resolvedKind(dir, e)) {
                        FileKind.Directory -> {
                            directories += path.removePrefix(root.trimEnd('/') + "/")
                            if (real in ancestors) errors += "插件组件目录链接成环：$path"
                            else walk(path, depth + 1, ancestors + real)
                        }
                        FileKind.File -> {
                            // 不读 bin 与 bundle 的二进制；这里只需要声明路径，避免把任意大树当配置读入内存。
                            if (rel.endsWith(".md") || rel.endsWith(".json")) {
                                io.readText(path)?.let { files[rel] = it; textBytes += it.toByteArray().size }
                            } else files[rel] = ""
                        }
                        else -> Unit
                    }
                }
            }
            walk(root, 0, setOf(realRoot))
            val result = PluginParser(directories).parse(request, root, files)
            val allErrors = (errors + io.notes + result.errors).distinct()
            return result.copy(plugin = result.plugin?.copy(errors = allErrors), errors = allErrors)
        } catch (e: CancellationException) { throw e }
        catch (e: PluginInvalid) { return PluginLoadOutcome(warnings = warnings, errors = errors + e.message.orEmpty()) }
        catch (e: Exception) {
            // 解析异常可能引用配置值，诊断只保留类别，避免坏 JSON 把凭证写进会话日志。
            return PluginLoadOutcome(warnings = warnings, errors = errors + "插件配置无效（${e::class.simpleName}）")
        }
    }

}
