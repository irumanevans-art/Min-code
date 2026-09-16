package dev.min.code.core.settings

/**
 * 一个文件被点开时用什么打开。
 *
 * 放在 `core/settings` 而不是 `ui/files`，是因为它要进 [AppSettings] —— core 不该反过来依赖 ui。
 */
enum class WorkspaceOpenMode {
    /** 应用内的文本编辑器（`Screen.FileEditor`） */
    EDITOR,

    /** 应用内的图片预览 */
    IMAGE,

    /** 交给系统：`ACTION_VIEW` + 选择器 */
    EXTERNAL,
}

/**
 * 按扩展名记住的「打开方式」。存成 `kt=EDITOR;png=EXTERNAL` 一行，而不是各存一个 key ——
 * DataStore 的 key 是固定集合，按用户见过的扩展名动态开 key 会越攒越多且删不干净。
 *
 * 坏掉的项一律**丢弃不抛**：这行字是可能被旧版本、被手改、被别的分支写过的，
 * 为了一个记不住的默认值让整个设置流炸掉不值得。
 */
fun parseOpenWithDefaults(raw: String): Map<String, WorkspaceOpenMode> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(';').mapNotNull { pair ->
        val ext = pair.substringBefore('=', "").trim().lowercase()
        val mode = pair.substringAfter('=', "").trim()
        if (ext.isEmpty() || mode.isEmpty()) return@mapNotNull null
        val parsed = runCatching { WorkspaceOpenMode.valueOf(mode) }.getOrNull() ?: return@mapNotNull null
        ext to parsed
    }.toMap()
}

fun formatOpenWithDefaults(map: Map<String, WorkspaceOpenMode>): String =
    map.entries
        .filter { it.key.isNotBlank() }
        .sortedBy { it.key }
        .joinToString(";") { "${it.key.lowercase()}=${it.value.name}" }
