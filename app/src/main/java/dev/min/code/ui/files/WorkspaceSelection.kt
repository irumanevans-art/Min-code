package dev.min.code.ui.files

/**
 * 多选态的纯函数部分。放在这里而不是 VM 里，是因为仓库的测试只有裸 JUnit
 * （没有 Robolectric、没有 coroutines-test），能测的只有这种不碰 Android 的东西。
 *
 * 选中项一律用「相对区根的路径」当键 —— 和列表的 `key = "${area}:${path}"` 是同一套，
 * 所以刷新、动画、选择三者天然对得上。
 */
object WorkspaceSelection {

    fun toggle(selected: Set<String>, path: String): Set<String> =
        if (path in selected) selected - path else selected + path

    fun selectAll(selected: Set<String>, visible: List<String>): Set<String> = selected + visible

    fun invert(selected: Set<String>, visible: List<String>): Set<String> =
        visible.filterNot { it in selected }.toSet()

    /**
     * 刷新之后把已经不在列表里的项摘掉。
     *
     * 用 prune 而不是直接清空：删一个文件会触发一次 refresh，那不该把用户攒了半天的选择抹掉。
     */
    fun prune(selected: Set<String>, visible: List<String>): Set<String> =
        selected intersect visible.toSet()

    fun allSelected(selected: Set<String>, visible: List<String>): Boolean =
        visible.isNotEmpty() && visible.all { it in selected }
}

/**
 * 打包时条目名相对哪一层。
 *
 * 搜索结果里的命中**不是兄弟**：两个不同目录下的 `config.json` 如果都按当前目录取名，
 * 打进同一个包就会撞名。所以搜索态一律以区根为基，路径长一点也认。
 */
fun archiveBase(inSearch: Boolean, currentPath: String): String = if (inSearch) "" else currentPath

/**
 * 文件名里不能留的字符。用字符集合而不是正则：路径分隔符和引号在正则字面量里要绕好几层转义，
 * 这种一眼要能看懂的白名单不值得为它绕。
 */
private val UNSAFE_NAME_CHARS: Set<Char> =
    setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

private const val ARCHIVE_STEM_MAX = 80

/**
 * 给导出的 zip 起个名。
 *
 * - 只选了一个文件夹 → 就叫它自己（`docs.zip`），这是最容易认的
 * - 目录内多选 → `当前目录-日期.zip`
 * - 区根多选 → `workspace-日期.zip` / `rootfs-日期.zip`
 */
fun archiveName(
    selectedNames: List<String>,
    selectedIsSingleFolder: Boolean,
    currentFolderName: String?,
    areaLabel: String,
    date: String,
): String {
    val stem = if (selectedIsSingleFolder && selectedNames.size == 1) {
        selectedNames.first()
    } else {
        val scope = currentFolderName?.takeIf { it.isNotBlank() } ?: areaLabel
        "$scope-$date"
    }
    return sanitizeFileName(stem) + ".zip"
}

/** 清掉文件名里不能用的字符，压掉空白，再按 [ARCHIVE_STEM_MAX] 截断 */
fun sanitizeFileName(raw: String): String {
    val cleaned = raw
        .map { if (it in UNSAFE_NAME_CHARS || it.isISOControl()) '_' else it }
        .joinToString("")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('.')
    val stem = cleaned.ifBlank { "export" }
    return if (stem.length <= ARCHIVE_STEM_MAX) stem else stem.take(ARCHIVE_STEM_MAX).trimEnd()
}
