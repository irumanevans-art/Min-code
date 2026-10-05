package dev.min.agent.cli

import dev.min.agent.ext.styles.OutputStyleDef
import dev.min.agent.ext.styles.OutputStyleFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * `/output-style [名字]`（CC output-styles.md）：无参列出可用样式（磁盘 + 内置，标出本会话生效的）；
 * 有参把名字写进项目 `.claude/settings.local.json` 的 `outputStyle`（与 CC 互通），并提示新会话生效。
 * 名字大小写敏感——设置值的语义如此（文档明说），命令也按精确匹配。
 */
internal suspend fun outputStyleCommand(app: DesktopApp, runtime: dev.min.agent.kernel.SessionRuntime, arg: String) {
    val disk = app.outputStyles()
    val active = runtime.state.value.outputStyle?.name ?: "Default"
    if (arg.isEmpty()) {
        System.err.println(styleList(disk, active))
        return
    }
    val def = OutputStyleFiles.resolve(disk, arg)
    if (def == null) {
        System.err.println("没有名为 \"$arg\" 的样式（名字大小写敏感）。\n${styleList(disk, active)}")
        return
    }
    val target = app.settingsLocalFile()
    try {
        writeOutputStyleSetting(target, def.name)
    } catch (e: Exception) {
        System.err.println("写不进 $target：${e.message ?: e::class.simpleName}")
        return
    }
    System.err.println("outputStyle = ${def.name} 已写进 .claude/settings.local.json，新会话生效（本会话仍是 $active）。")
}

internal fun styleList(disk: Map<String, OutputStyleDef>, active: String): String = buildString {
    append("可用样式（* 是本会话生效的）：")
    (disk.values.map { it.name } + OutputStyleFiles.BUILTINS.keys).distinct().sorted().forEach { name ->
        append("\n  ").append(if (name == active) "* " else "  ").append(name)
        (disk[name] ?: OutputStyleFiles.BUILTINS[name])?.description?.let { append(" —— ").append(it) }
    }
}

/**
 * 把 `outputStyle` 写进 [file]（`.claude/settings.local.json`）：读—改—写，保留其它键与顺序（JsonObject 本就是
 * 按插入序的 LinkedHashMap）；文件或目录不在就建。顶层不是 JSON 对象时抛异常、不覆盖——用户的文件比我们的选择重要
 */
internal fun writeOutputStyleSetting(file: Path, name: String) {
    val obj = if (Files.exists(file)) {
        dev.min.agent.protocol.AgentJson.parseToJsonElement(Files.readString(file)) as? JsonObject
            ?: error("顶层不是 JSON 对象，不覆盖")
    } else {
        JsonObject(emptyMap())
    }
    val updated = JsonObject(LinkedHashMap(obj).apply { put("outputStyle", JsonPrimitive(name)) })
    Files.createDirectories(file.parent)
    Files.writeString(file, updated.toString())
}
