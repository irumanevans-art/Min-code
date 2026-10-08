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
            suspend fun walk(dir: String, depth: Int, ancestors: Set<String>) {
                if (depth > ConfigIo.MAX_DEPTH) { errors += "插件目录嵌套过深"; return }
                for (e in io.entries(dir)) {
                    if (e.name == ".git") continue
                    if (files.size > 4096 || textBytes > 8L * 1024 * 1024) { errors += "插件配置超过装载预算"; return }
                    val path = GuestPaths.join(dir, e.name)
                    val real = canonical(GuestPath(path)).value
                    if (real != realRoot && !real.startsWith(realRoot.trimEnd('/') + "/")) {
                        errors += "escapes plugin directory"; continue
                    }
                    when (io.resolvedKind(dir, e)) {
                        FileKind.Directory -> {
                            directories += path.removePrefix(root.trimEnd('/') + "/")
                            if (real in ancestors) errors += "插件目录链接成环"
                            else walk(path, depth + 1, ancestors + real)
                        }
                        FileKind.File -> {
                            // 不读 bin 与 bundle 的二进制；这里只需要声明路径，避免把任意大树当配置读入内存。
                            val rel = path.removePrefix(root.trimEnd('/') + "/")
                            if (rel.endsWith(".md") || rel.endsWith(".json")) {
                                io.readText(path)?.let { files[rel] = it; textBytes += it.toByteArray().size }
                            } else files[rel] = ""
                        }
                        else -> Unit
                    }
                }
            }
            if (io.kind(root) != FileKind.Directory) return PluginLoadOutcome(errors = listOf("plugin directory not found"))
            walk(root, 0, setOf(realRoot))
            if (errors.isNotEmpty() || io.notes.isNotEmpty()) return PluginLoadOutcome(errors = errors + io.notes.map { "插件文件读取失败" })
            return PluginParser(directories).parse(request, root, files)
        } catch (e: CancellationException) { throw e }
        catch (e: PluginInvalid) { return PluginLoadOutcome(warnings = warnings, errors = errors + e.message.orEmpty()) }
        catch (e: Exception) {
            // 解析异常可能引用配置值，诊断只保留类别，避免坏 JSON 把凭证写进会话日志。
            return PluginLoadOutcome(warnings = warnings, errors = errors + "插件配置无效（${e::class.simpleName}）")
        }
    }

}
