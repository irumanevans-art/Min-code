package dev.min.agent.context

import dev.min.agent.spi.AutoMemoryInfo
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import dev.min.agent.spi.WorkspaceFs
import dev.min.agent.spi.catchingNonCancellation
import java.nio.charset.CharacterCodingException

/**
 * 自动记忆（docs/agent/detail/记忆按需加载与自动记忆设计.md §2）：每个项目一个目录，模型用普通文件工具读写自己的笔记，
 * 索引 `MEMORY.md` 在会话创建时进系统提示。这里只管「目录在哪、索引读多少」；开关与目录覆盖的取舍在内核（loadMemory）。
 *
 * - **项目键**：cwd 所在 git 仓库的**主工作树根**。从 cwd 往上找第一个 `.git`：是目录，cwd 的仓库就在这（`.git` 目录本身
 *   只属于主工作树）；是文件（worktree / 子模块），读里面的 `gitdir:` 指到该 worktree 的 git 目录，再沿它的 `commondir`
 *   找回主仓库的 `.git`——主工作树根 = 那个 `.git` 的上一级。跨 worktree 的会话因此共用同一份记忆（CC 同）。不在 git
 *   仓库里就用 cwd 本身。键 = 路径里每个不是 `[A-Za-z0-9]` 的字符换成 `-`（`/root/proj` → `-root-proj`）。
 * - **索引截断**：前 200 行或前 25 KB，先到为准，按整行截（半行对模型没用）；只在第一行就超长时才在行中间硬截。
 *   行数与字节数两个上限都是给「该整理了」的信号用的，不是安全限制——所以读全文数行数（有 4 MiB 的读上限兜底）。
 */
object AutoMemory {
    const val INDEX_FILE = "MEMORY.md"

    /** CC 的索引预算：超过 200 行只加载前 200 行，提示里的用量行据此提醒模型合并整理 */
    const val MAX_INDEX_LINES = 200
    const val MAX_INDEX_BYTES = 25 * 1024

    /** 数总行数用的读上限：正常的索引只有几 KB；超过它按读到的数（防一个异常巨大的文件拖住会话创建） */
    private const val READ_CAP_BYTES = 1 shl 22

    /** 一次索引读取：[file] 为 null = 索引不存在（还是个新项目）或读不了；info 总是有（提示段要给出目录） */
    class Index(val file: LoadedMemoryFile?, val info: AutoMemoryInfo)

    /** 记忆目录的默认位置：`<home>/.claude/projects/<项目键>/memory/` */
    suspend fun directory(fs: WorkspaceFs, cwd: GuestPath, home: GuestPath): GuestPath =
        GuestPath(MemoryLoader.join(home.value, ".claude/projects/${projectKey(fs, cwd)}/memory"))

    /** 目录覆盖（settings 的 `autoMemoryDirectory`）：绝对路径或 `~/` 开头；别的写法不认，返回 null 按默认位置 */
    fun directoryOverride(home: GuestPath, override: String): GuestPath? = when {
        override.startsWith("/") -> GuestPath(MemoryLoader.normalize(override))
        override.startsWith("~/") -> GuestPath(MemoryLoader.join(home.value, override.removePrefix("~/")))
        else -> null
    }

    /** cwd 所在仓库的主工作树根 → 键。cwd 不在 git 仓库里时键就是 cwd 的键 */
    suspend fun projectKey(fs: WorkspaceFs, cwd: GuestPath): String =
        key(mainWorktreeRoot(fs, MemoryLoader.normalize(cwd.value)))

    /** [path] 里每个不是 `[A-Za-z0-9]` 的字符换成 `-`（CC 的项目目录名就是这么来的） */
    fun key(path: String): String = buildString {
        for (c in path) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
    }

    /** 读一次索引：截断后的正文（进快照）与用量（进提示）。读不了的（权限等）按没有索引处理，不让它挡住会话创建 */
    suspend fun loadIndex(fs: WorkspaceFs, dir: GuestPath): Index {
        val path = GuestPath(MemoryLoader.join(dir.value, INDEX_FILE))
        val stat = catchingNonCancellation { fs.stat(path) }.getOrNull()
        if (stat?.kind != FileKind.File) return Index(null, AutoMemoryInfo(dir))
        val bytes = catchingNonCancellation { fs.read(path, 0, READ_CAP_BYTES) }.getOrNull()
            ?: return Index(null, AutoMemoryInfo(dir))
        val text = decode(bytes).removePrefix("\uFEFF").replace("\r\n", "\n")
        val totalLines = countLines(text)
        val kept = cap(text)
        if (kept.isBlank()) return Index(null, AutoMemoryInfo(dir, totalLines, 0))
        return Index(LoadedMemoryFile(path, MemorySource.Auto, kept.trimEnd()), AutoMemoryInfo(dir, totalLines, countLines(kept)))
    }

    /** 前 200 行或前 25 KB，先到为准，按整行截（行以 `\n` 结尾）；结果是 [text] 的前缀 */
    private fun cap(text: String): String {
        var start = 0
        var bytes = 0
        var lines = 0
        while (lines < MAX_INDEX_LINES) {
            val end = text.indexOf('\n', start)
            val lineEnd = if (end < 0) text.length else end + 1
            val w = text.substring(start, lineEnd).toByteArray(Charsets.UTF_8).size
            if (bytes + w > MAX_INDEX_BYTES) {
                // 第一行就超长：按字节硬截，行中间在所难免（总得给出点什么）
                if (start == 0) return cutBytes(text, MAX_INDEX_BYTES)
                break
            }
            bytes += w
            start = lineEnd
            lines++
            if (end < 0) break
        }
        return text.substring(0, start)
    }

    /** 截到最多 [maxBytes] 字节，不劈开 UTF-8 序列，也不劈开代理对 */
    private fun cutBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        for ((i, c) in text.withIndex()) {
            val w = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> 4 // 与后面的低代理一起算
                Character.isLowSurrogate(c) -> 0
                else -> 3
            }
            if (w > 0 && bytes + w > maxBytes) return text.substring(0, i)
            bytes += w
        }
        return text
    }

    /** wc -l 的口径：换行符个数，末尾没换行的最后一行也算一行 */
    private fun countLines(text: String): Int = when {
        text.isEmpty() -> 0
        text.endsWith("\n") -> text.count { it == '\n' }
        else -> text.count { it == '\n' } + 1
    }

    /** 读到 READ_CAP 截断时可能劈开多字节字符：严格解码，失败就去掉尾巴一字节再试（最多三次） */
    private fun decode(bytes: ByteArray): String {
        var n = bytes.size
        while (n > 0) {
            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, n))
                    .toString()
            } catch (e: CharacterCodingException) {
                n--
            }
        }
        return ""
    }

    /** git 的发现顺序：从 cwd 往上找第一个 `.git`（git 同样在文件系统边界与挂载点才停，这里到根为止） */
    private suspend fun mainWorktreeRoot(fs: WorkspaceFs, cwd: String): String {
        for (dir in MemoryLoader.ancestors(cwd).asReversed()) {
            val dotGit = MemoryLoader.join(dir, ".git")
            val stat = catchingNonCancellation { fs.stat(GuestPath(dotGit)) }.getOrNull() ?: continue
            when (stat.kind) {
                FileKind.Directory -> return dir
                FileKind.File -> return mainRootOfWorktree(fs, dir, dotGit)
                else -> Unit
            }
        }
        return cwd
    }

    /**
     * `.git` 是文件（worktree / 子模块）：`gitdir:` 指到这个工作树的 git 目录，`commondir`（相对它）指回主仓库的
     * `.git`，主工作树根 = 那个 `.git` 的上一级。commondir 缺失说明它不是链接工作树（子模块的 `.git` 文件这么写），
     * 仓库根就是 `.git` 文件所在的目录。
     */
    private suspend fun mainRootOfWorktree(fs: WorkspaceFs, dir: String, dotGitFile: String): String {
        val text = readText(fs, dotGitFile) ?: return dir
        val gitdir = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("gitdir:") }
            ?.removePrefix("gitdir:")?.trim().orEmpty()
        if (gitdir.isEmpty()) return dir
        val common = readText(fs, MemoryLoader.join(resolve(gitdir, base = dir), "commondir"))?.trim().orEmpty()
        if (common.isEmpty()) return dir
        val mainGit = resolve(common, base = resolve(gitdir, base = dir))
        return if (mainGit.endsWith("/.git")) mainGit.removeSuffix("/.git") else MemoryLoader.parentOf(mainGit)
    }

    private fun resolve(path: String, base: String): String =
        MemoryLoader.normalize(if (path.startsWith("/")) path else MemoryLoader.join(base, path))

    private suspend fun readText(fs: WorkspaceFs, path: String): String? =
        catchingNonCancellation { String(fs.read(GuestPath(path), 0, READ_CAP_BYTES), Charsets.UTF_8) }.getOrNull()
}
