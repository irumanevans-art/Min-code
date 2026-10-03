package dev.min.agent.context

import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation

/**
 * 记忆文件的字符上限。超出部分截断，并在内容里写明截断（模型知道还有下文，可以自己 Read）。
 *
 * CC 对 CLAUDE.md 只在超过 4 MiB 时跳过，从不截断；Min 跑在手机上、按 token 付费，所以收得紧得多：
 * 单文件 2 万字符约 5k token（英文；中文更多），已是 CC 建议的「200 行以内」的好几倍；合计 5 万字符约 12.5k token。
 */
data class MemoryLimits(
    val fileChars: Int = 20_000,
    val totalChars: Int = 50_000,
)

/**
 * 一次加载的结果：[files] 按进上下文的顺序排好；[notes] 只给人看（跳过了什么、为什么），不进模型上下文。
 * [covered]（按需加载才有）：没有注入、但内容已经在上下文里的路径——与同批已收文件内容相同的，以及模型刚直接读过的记忆文件本身
 */
data class MemorySnapshot(val files: List<LoadedMemoryFile>, val notes: List<String>, val covered: List<GuestPath> = emptyList())

/**
 * 发现并读取记忆文件（docs/agent/04-子系统设计.md §4.6）。纯逻辑，只经 [WorkspaceFs] 端口读文件。
 *
 * 顺序宽 → 窄，全部拼接、互不覆盖（与 Claude Code 一致，越靠后越具体）：
 * 1. 用户级：`~/.claude/CLAUDE.md`，然后 `~/.claude/rules/` 下的规则（CC：用户规则先于项目规则）。
 * 2. 从文件系统根往下直到 cwd 的每一层：`CLAUDE.md`、`.claude/CLAUDE.md`、`AGENTS.md`、`.claude/rules/` 下的规则、
 *    最后是 `CLAUDE.local.md`——CC 原文「Within each directory, CLAUDE.local.md is appended after CLAUDE.md」，
 *    个人说明是那一层最后读到的；规则「with the same priority as .claude/CLAUDE.md」，所以跟在同层项目文件后面。
 *
 * 规则：`.md` 递归收，按名字排序；带 `paths:` 的按需加载（读到匹配文件时才注入，见 [MemoryDiscovery]），会话创建时跳过并记一条说明。
 * 规则的 frontmatter 不进上下文（CC 同样去掉）。
 *
 * `@` 导入（解析见 [MemoryMarkdown.imports]）：相对**包含它的文件**解析，`~/` 指用户主目录，最多 [MAX_IMPORT_HOPS] 跳；
 * 被导入的文件排在导入它的文件**之前**（CC 文档：「Claude reads the imported file first, then the rest」）。
 * 只导入工作区内或 `~/.claude` 下的文件；CC 在这里会弹一次批准框，Min 还没有接审批，先跳过并记一条说明。
 *
 * 去重：同一路径只收一次（`CLAUDE.md` 里写 `@AGENTS.md` 时，同层的 AGENTS.md 不会再读一遍；导入成环也靠它停下）；
 * 内容与已收文件完全相同的也不再收——`ln -s AGENTS.md CLAUDE.md` 是 CC 文档推荐的写法，端口里看不出链接，按内容认。
 */
object MemoryLoader {
    /** CC 原文「with a maximum depth of four hops」 */
    const val MAX_IMPORT_HOPS = 4

    suspend fun load(
        fs: WorkspaceFs,
        cwd: GuestPath,
        home: GuestPath,
        limits: MemoryLimits = MemoryLimits(),
    ): MemorySnapshot = Loading(fs, normalize(home.value), limits).run(normalize(cwd.value))

    internal const val FILE_TRUNCATED = "[Truncated: this file is longer than %d characters. Read the file for the rest.]"
    internal const val TOTAL_TRUNCATED =
        "[Truncated: memory files are limited to %d characters in total. Read the file for the rest.]"
    internal const val NOT_LOADED =
        "[Not loaded: memory files are limited to %d characters in total. Read the file if you need it.]"

    internal const val ATTACH_NOT_LOADED =
        "[Not loaded: this file is too long to attach here together with the other memory files. Read the file if you need it.]"

    /** 带 `paths:` 的规则：路径与它的 glob 列表（内容用到时再读） */
    internal data class PathRuleRef(val path: String, val globs: List<String>)

    internal class Loading(
        private val fs: WorkspaceFs,
        private val home: String,
        private val limits: MemoryLimits,
        /** 已经在模型上下文里的路径（按需加载时给）：当作处理过，不再读，导入指向它们也不再重复 */
        alreadyIn: Collection<String> = emptyList(),
        /** 按需加载：整份放不下的文件不截断，只留一行说明 */
        private val wholeFilesOnly: Boolean = false,
        /** 非 null = 遇到带 paths 的规则时登记下来（读到匹配的文件时再加载），而不是跳过 */
        val pathRuleRefs: MutableList<PathRuleRef>? = null,
        /** 只登记带 paths 的规则，别的什么都不收（找启动时就存在的路径规则用） */
        private val refsOnly: Boolean = false,
        /** 按需加载：模型这批直接读过的文件。它们的内容已在工具结果里，其中是记忆文件的不再作为记忆注入，只记进 [covered] */
        private val directReads: Set<String> = emptySet(),
        /** 按需加载：之前的批次里已经报过问题（读不了、不是文本、导入越界……）的路径。不再读、不再报：同一个坏路径一个会话说一次 */
        knownProblems: Set<String> = emptySet(),
    ) {
        private val homeClaude = join(home, ".claude")
        val files = mutableListOf<LoadedMemoryFile>()
        val notes = mutableListOf<String>()

        /** 没有注入、但内容已经在上下文里的路径（见 [MemorySnapshot.covered]） */
        val covered = mutableListOf<String>()

        /** 报过问题的路径（含 [knownProblems]）：调用方带去下一批，当作负缓存 */
        val problems = knownProblems.toMutableSet()

        /** 记一条与 [path] 有关的问题说明；这个路径已经报过就不重复 */
        private fun problem(path: String, note: String) {
            if (problems.add(path)) notes += note
        }

        /** 处理过（或正在处理）的路径：去重，也让导入成环时停下 */
        private val seen = alreadyIn.toMutableSet()

        /** 已收文件的正文 → 路径：同内容（多半是符号链接）不重复进上下文 */
        private val bodies = mutableMapOf<String, String>()
        private var used = 0

        suspend fun run(cwd: String): MemorySnapshot {
            add(join(homeClaude, "CLAUDE.md"), MemorySource.User)
            addRules(join(homeClaude, "rules"))
            for (dir in ancestors(cwd)) {
                add(join(dir, "CLAUDE.md"), MemorySource.Project)
                add(join(dir, ".claude/CLAUDE.md"), MemorySource.Project)
                add(join(dir, "AGENTS.md"), MemorySource.Project)
                addRules(join(dir, ".claude/rules"))
                add(join(dir, "CLAUDE.local.md"), MemorySource.Local)
            }
            return MemorySnapshot(files.toList(), notes.toList())
        }

        /** 按需加载：cwd 之下一个子目录这一层的记忆，顺序与启动时每一层相同；其中的规则（没有 paths 的）来源记为 nested */
        suspend fun nestedLayer(dir: String) {
            add(join(dir, "CLAUDE.md"), MemorySource.Nested)
            add(join(dir, ".claude/CLAUDE.md"), MemorySource.Nested)
            add(join(dir, "AGENTS.md"), MemorySource.Nested)
            addRules(join(dir, ".claude/rules"), MemorySource.Nested)
            add(join(dir, "CLAUDE.local.md"), MemorySource.Nested)
        }

        /** 按需加载：一条已经匹配上的路径规则 */
        suspend fun pathRule(path: String) = add(path, MemorySource.PathRule, rule = true)

        /** 登记 [dir]（一个 `rules` 目录）下带 paths 的规则，不收内容；要先用 [refsOnly] 建出这个实例 */
        suspend fun collectRuleRefs(dir: String) = addRules(dir, MemorySource.Rule)

        /**
         * [importer] 非空 = 这是一次导入：文件不存在时要告诉人（拼错了路径），顶层候选不存在是常态，不吭声。
         * [rule] 为真时按规则处理（去 frontmatter、认 paths）
         */
        private suspend fun add(
            path: String,
            source: MemorySource,
            hops: Int = 0,
            importer: String? = null,
            rule: Boolean = source == MemorySource.Rule,
        ) {
            if (path in problems || !seen.add(path)) return
            val stat = attempt(path) { fs.stat(GuestPath(path)) }
            if (stat?.kind != FileKind.File) {
                if (importer != null) problem(path, "导入的文件不存在或不是普通文件：$path（来自 $importer）")
                return
            }
            var text = read(path) ?: return
            if ('\u0000' in text) {
                problem(path, "跳过 $path：看起来不是文本文件")
                return
            }
            if (rule) {
                val frontmatter = MemoryMarkdown.splitFrontmatter(text)
                if (frontmatter.paths.isNotEmpty() && source != MemorySource.PathRule) {
                    if (pathRuleRefs == null) {
                        notes += "跳过规则 $path：带 paths: 的规则要在读到匹配文件时才加载，会话创建时不读"
                    } else {
                        // 登记下来；路径从已处理集合里拿掉，匹配上之后才能再按路径规则加载它
                        pathRuleRefs += PathRuleRef(path, frontmatter.paths)
                        seen.remove(path)
                    }
                    return
                }
                if (refsOnly) return
                text = frontmatter.body
            }
            if (text.isBlank()) return
            val fileTruncated = text.length > limits.fileChars
            if (fileTruncated) {
                notes += "$path 超过 ${limits.fileChars} 字符，已截断"
                text = cut(text, limits.fileChars)
            }
            // 预算已用完的文件连同它的导入都不读：模型只看到一行「未加载」，导入跟着读进来也是白读
            if (used < limits.totalChars) {
                for (raw in MemoryMarkdown.imports(text)) importFrom(path, raw, hops)
            }
            if (path in directReads) {
                // 模型刚读过这个文件本身：内容已经在工具结果里，再注入一遍是重复。它的内容也算「已收」，同内容的兄弟文件随之跳过
                bodies.putIfAbsent(text, path)
                covered += path
                return
            }
            val same = bodies[text]
            if (same != null) {
                notes += "跳过 $path：内容与 $same 相同"
                // 跳过不等于没收：内容已经在上下文里，记下这个路径，下一批不能把它当新文件再注入
                covered += path
                return
            }
            bodies[text] = path
            append(path, source, text, fileTruncated)
        }

        private suspend fun importFrom(importer: String, raw: String, hops: Int) {
            val target = normalize(
                when {
                    raw.startsWith("~/") -> join(home, raw.removePrefix("~/"))
                    raw.startsWith("/") -> raw
                    else -> join(parentOf(importer), raw)
                },
            )
            if (target in seen || target in problems) return // 已经收过，或成环，或报过问题
            when {
                hops + 1 > MAX_IMPORT_HOPS -> problem(target, "跳过导入 $target（来自 $importer）：导入最多 $MAX_IMPORT_HOPS 层")
                !importable(target) -> problem(target, "跳过导入 $target（来自 $importer）：只导入工作区或 ~/.claude 下的文件")
                else -> add(target, MemorySource.Import, hops + 1, importer)
            }
        }

        /** 工作区按端口的分级判定（宿主会先解析符号链接）；`~/.claude` 按字面路径判定 */
        private fun importable(path: String): Boolean =
            isUnder(path, homeClaude) ||
                catchingNonCancellation { fs.scopeOf(GuestPath(path)) }.getOrNull() == PathScope.Workspace

        /**
         * 递归收 `.md` 规则，按名字排序（目录里的顺序由端口决定，不排序前缀就可能变）。
         * 符号链接指向的文件照收（指向哪里与导入同一套范围规则）；指向目录的不跟——端口里认不出链接成环。
         */
        private suspend fun addRules(dir: String, source: MemorySource = MemorySource.Rule) {
            if (dir in problems) return
            if (attempt(dir) { fs.stat(GuestPath(dir)) }?.kind != FileKind.Directory) return
            val entries = attempt(dir) { fs.list(GuestPath(dir)) } ?: return
            for (entry in entries.sortedBy { it.name }) {
                val path = join(dir, entry.name)
                val linked = entry.kind == FileKind.Symlink
                val kind = if (linked) attempt(path) { fs.stat(GuestPath(path)) }?.kind else entry.kind
                when {
                    kind == FileKind.Directory && linked -> problem(path, "跳过 $path：规则目录里的符号链接目录暂不跟随")
                    kind == FileKind.Directory -> addRules(path, source)
                    kind != FileKind.File || !entry.name.endsWith(".md") -> Unit
                    linked && !importable(path) -> problem(path, "跳过规则 $path：链接到工作区或 ~/.claude 之外")
                    else -> add(path, source, rule = true)
                }
            }
        }

        /** 记入结果并扣合计预算。正文按预算截断；预算用完的文件只留一行说明，让模型知道它存在 */
        private fun append(path: String, source: MemorySource, body: String, fileTruncated: Boolean) {
            val remaining = limits.totalChars - used
            val content = when {
                // 按需加载：放不下的整份不收，也不占预算——模型要的话自己 Read
                wholeFilesOnly && body.length > remaining -> {
                    notes += "按需加载的记忆合计超过 ${limits.totalChars} 字符，$path 未加载"
                    files += LoadedMemoryFile(GuestPath(path), source, ATTACH_NOT_LOADED)
                    return
                }
                remaining <= 0 -> {
                    notes += "记忆文件合计超过 ${limits.totalChars} 字符，$path 未加载"
                    NOT_LOADED.format(limits.totalChars)
                }
                body.length > remaining -> {
                    notes += "记忆文件合计超过 ${limits.totalChars} 字符，$path 已截断"
                    cut(body, remaining) + "\n\n" + TOTAL_TRUNCATED.format(limits.totalChars)
                }
                fileTruncated -> body + "\n\n" + FILE_TRUNCATED.format(limits.fileChars)
                else -> body
            }
            used += minOf(body.length, remaining.coerceAtLeast(0))
            files += LoadedMemoryFile(GuestPath(path), source, content)
        }

        /** 读到比单文件上限多一点就够判断要不要截断：UTF-8 一个字符至多 4 字节 */
        private suspend fun read(path: String): String? {
            val limit = (limits.fileChars.toLong() * 4 + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = attempt(path) { fs.read(GuestPath(path), 0, limit) } ?: return null
            return String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n", "\n")
        }

        /** 读不了的（权限、链接成环、宿主越界）记一条说明跳过，不让一个坏文件拖垮会话创建 */
        private inline fun <T> attempt(path: String, block: () -> T): T? =
            catchingNonCancellation(block).getOrElse { e ->
                problem(path, "读取 $path 失败：${e.message ?: e::class.simpleName}")
                null
            }
    }

    /** 截到 [n] 个字符，不把代理对劈成两半 */
    private fun cut(text: String, n: Int): String {
        if (text.length <= n) return text
        val end = if (n > 0 && text[n - 1].isHighSurrogate()) n - 1 else n
        return text.substring(0, end)
    }

    // ---- guest 路径（POSIX，字面处理 . 与 ..，不跟随链接） ----

    internal fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts += part
            }
        }
        return "/" + parts.joinToString("/")
    }

    internal fun join(dir: String, name: String): String = normalize("$dir/$name")

    internal fun parentOf(path: String): String = normalize(path.substringBeforeLast('/', "/").ifEmpty { "/" })

    /** `/`、`/a`、`/a/b`……直到 [path] 本身，宽 → 窄 */
    internal fun ancestors(path: String): List<String> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return listOf("/") + parts.indices.map { i -> "/" + parts.subList(0, i + 1).joinToString("/") }
    }

    internal fun isUnder(path: String, base: String): Boolean = path == base || path.startsWith("$base/")
}
