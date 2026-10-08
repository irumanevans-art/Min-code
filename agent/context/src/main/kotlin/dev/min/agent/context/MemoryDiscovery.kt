package dev.min.agent.context

import dev.min.agent.context.MemoryLoader.PathRuleRef
import dev.min.agent.context.MemoryLoader.isUnder
import dev.min.agent.context.MemoryLoader.join
import dev.min.agent.context.MemoryLoader.normalize
import dev.min.agent.context.MemoryLoader.parentOf
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.WorkspaceFs

/**
 * 按需加载记忆（docs/agent/detail/记忆按需加载与自动记忆设计.md §1）：模型碰到文件之后，找出此刻该注入的记忆。
 * 纯逻辑，只经 [WorkspaceFs] 读文件；一个会话一个实例。
 *
 * 对每个碰到的文件 P（必须在 cwd 之内，cwd 之外的子目录记忆 CC 也不加载）：
 * - **子目录记忆**：从 cwd 的下一层到 P 所在目录（含），每层按启动时同样的顺序找 `CLAUDE.md`、`.claude/CLAUDE.md`、`AGENTS.md`、
 *   `.claude/rules/` 下没有 `paths:` 的规则、`CLAUDE.local.md`，`@` 导入照启动时的规则展开。
 * - **路径规则**：所有带 `paths:` 的规则——用户级、cwd 及其祖先的 `.claude/rules/`（启动时就存在的），加上这次扫到的子目录
 *   `.claude/rules/` 里的——其 glob 相对**规则所属项目根**（`.claude` 的上一级目录）匹配 P，命中就加载。
 *   用户级规则的项目根取 cwd：主目录通常不在工作区之内（Android 上 `/root` 与 `/workspace`），按字面取主目录就永远匹配不上。
 *
 * 去重由调用方给的 [attach] 的 `inContext` 负责（折叠维护的「已在上下文里的记忆路径」）：在里面的路径不再读、不再收。
 * 内存里只缓存「启动时就存在的路径规则有哪些」；内容每次现读（压缩后重新加载要读最新的），目录每次现扫，
 * 所以会话中途新增的子目录记忆下次碰到时也能找到。缓存丢了只是多读几次盘，结果都落了日志，请求不依赖它。
 *
 * 合计上限是 [MemoryLimits.totalChars] 的一半，整份放不下的文件只留一行「未加载」说明（见 [MemoryLoader.ATTACH_NOT_LOADED]）。
 */
class MemoryDiscovery(
    private val fs: WorkspaceFs,
    cwd: GuestPath,
    home: GuestPath,
    private val limits: MemoryLimits = MemoryLimits(),
    private val suppressCwdRules: Boolean = false,
) {
    private val cwd = normalize(cwd.value)
    private val home = normalize(home.value)
    private var startupRefs: List<PathRuleRef>? = null

    /**
     * 已经报过问题的路径（读不了、不是文本、导入越界……）。只在内存里：同一个坏路径一个会话只读一次、只说一次，
     * 免得每碰一个文件就重读重记；会话重开后重新来过，多报一次而已
     */
    private val knownProblems = mutableSetOf<String>()

    /** [touched] 是这批工具调用碰到的文件（调用方已排除目录与失败的调用）；返回要新注入的文件，顺序：子目录记忆（宽 → 窄），然后路径规则 */
    suspend fun attach(touched: List<GuestPath>, inContext: Set<GuestPath>): MemorySnapshot {
        val files = touched.map { normalize(it.value) }.distinct().filter { it.startsWith(prefixOfCwd()) }
        if (files.isEmpty()) return MemorySnapshot(emptyList(), emptyList())
        val loading = MemoryLoader.Loading(
            fs, home, limits.copy(totalChars = limits.totalChars / 2),
            alreadyIn = inContext.map { it.value },
            wholeFilesOnly = true,
            pathRuleRefs = mutableListOf(),
            directReads = files.toSet(),
            knownProblems = knownProblems,
        )
        val dirs = linkedSetOf<String>()
        for (file in files) dirs += dirsBelowCwd(parentOf(file))
        for (dir in dirs) loading.nestedLayer(dir)

        val candidates = (startupRuleRefs() + loading.pathRuleRefs.orEmpty()).distinctBy { it.path }
        val globNotes = mutableListOf<String>()
        for (ref in candidates) {
            reportInvalidGlobs(ref)?.let { globNotes += it }
            if (files.any { file -> matches(ref, file) }) loading.pathRule(ref.path)
        }
        knownProblems += loading.problems
        return MemorySnapshot(loading.files.toList(), loading.notes.toList() + globNotes, loading.covered.map(::GuestPath))
    }

    /** cwd 的下一层到 [dir]（含），宽 → 窄；[dir] 不在 cwd 之下返回空 */
    private fun dirsBelowCwd(dir: String): List<String> {
        if (dir == cwd || !dir.startsWith(prefixOfCwd())) return emptyList()
        val relative = dir.removePrefix(prefixOfCwd()).split('/').filter { it.isNotEmpty() }
        return relative.indices.map { i -> join(cwd, relative.subList(0, i + 1).joinToString("/")) }
    }

    /** cwd 为 `/` 时前缀就是 `/`，否则是 cwd 加 `/` */
    private fun prefixOfCwd() = if (cwd == "/") "/" else "$cwd/"

    /** 用户级与 cwd 及其祖先的 `.claude/rules/` 里带 `paths:` 的规则，按启动时的顺序（宽 → 窄）。只扫一次 */
    private suspend fun startupRuleRefs(): List<PathRuleRef> = startupRefs ?: run {
        val collector = MemoryLoader.Loading(
            fs, home, limits, pathRuleRefs = mutableListOf(), refsOnly = true,
        )
        collector.collectRuleRefs(join(join(home, ".claude"), "rules"))
        for (dir in MemoryLoader.ancestors(cwd)) if (!suppressCwdRules || dir != cwd) collector.collectRuleRefs(join(dir, ".claude/rules"))
        collector.pathRuleRefs.orEmpty().distinctBy { it.path }.also { startupRefs = it }
    }

    /** 已经为它记过「paths 里有无效写法」说明的规则：同一条规则一个会话只说一次 */
    private val reportedBadGlobs = mutableSetOf<String>()

    /** 规则的 paths 里写不对的条目（它们不匹配任何文件，其余条目照常）；第一次见到时返回一条说明，之后返回 null */
    private fun reportInvalidGlobs(ref: PathRuleRef): String? {
        val bad = ref.globs.mapNotNull { glob -> (PathGlob.compile(glob) as? PathGlob.Compiled.Invalid)?.let { glob to it.reason } }
        if (bad.isEmpty() || !reportedBadGlobs.add(ref.path)) return null
        return "规则 ${ref.path} 的 paths 里有无效的写法，这些条目不会匹配任何文件：" + bad.joinToString("；") { (glob, why) -> "「$glob」（$why）" }
    }

    private fun matches(ref: PathRuleRef, file: String): Boolean {
        val root = projectRootOf(ref.path)
        if (!isUnder(file, root)) return false
        val relative = file.removePrefix(if (root == "/") "/" else "$root/")
        return ref.globs.any { PathGlob.matches(it, relative) }
    }

    /** 规则所属的项目根：`.claude` 的上一级；用户级规则（根是主目录）按 cwd 算 */
    private fun projectRootOf(rulePath: String): String {
        val root = normalize(rulePath.substringBefore("/.claude/rules/").ifEmpty { "/" })
        return if (root == home) cwd else root
    }
}
