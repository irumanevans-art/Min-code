package dev.min.agent.ext.security

/**
 * security-guidance 取改动清单用的 git 命令与输出解析（纯逻辑，不起进程；进程由内核经宿主的 exec 端口跑）。
 *
 * CC 的做法（文档 security-guidance.md）：回合开头记下工作区基线，回合末对它做 git diff，所以 Bash 里 sed / 脚本改的、子 agent 改的都在清单里；
 * 不在 git 仓库里两个模型侧审查静默跳过。Min 取同一个口径，但不碰用户的索引、不建提交也不动 stash：
 * 用一份临时索引（复制真索引的 stat 信息、`git add -A` 之后 `git write-tree`）把整个工作区（含未跟踪、不含被忽略的）固化成一个 tree 对象，
 * 回合开头一个、回合末一个，两个 tree 之间的 `git diff` 就是这一回合在工作区里发生的全部变化。
 * 写进对象库的只是孤立的 blob / tree 对象（`git gc` 会回收），真索引、HEAD、引用、工作区文件一概不动。
 */
object SecurityGit {
    /** tree 哈希：SHA-1 是 40 个十六进制字符、SHA-256 仓库是 64 个 */
    private val HASH = Regex("^[0-9a-f]{40}$|^[0-9a-f]{64}$")

    fun isHash(text: String): Boolean = HASH.matches(text)

    /**
     * 把当前工作区固化成一个 tree，哈希打在标准输出最后一行。不在仓库里（或没有工作树）退出码非零。
     * 临时索引放在 git 目录里、用完即删；`cp` 失败（新仓库还没有索引）时 `git add -A` 自己从空索引建起。
     */
    const val SNAPSHOT_SCRIPT: String = """d=${'$'}(git rev-parse --absolute-git-dir 2>/dev/null) || exit 3
i="${'$'}d/min-sg-index.${'$'}${'$'}"
cp "${'$'}(git rev-parse --git-path index 2>/dev/null)" "${'$'}i" 2>/dev/null
GIT_INDEX_FILE="${'$'}i" git add -A >/dev/null 2>&1 && GIT_INDEX_FILE="${'$'}i" git write-tree 2>/dev/null
r=${'$'}?
rm -f "${'$'}i"
exit ${'$'}r"""

    /** [SNAPSHOT_SCRIPT] 的输出里取 tree 哈希；不像哈希（空输出、报错文字）返回 null */
    fun parseTree(output: String): String? = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.takeIf(::isHash)

    private const val NAMES = "---MIN-SG-NAMES---"
    private const val PATCH = "---MIN-SG-PATCH---"

    /** 两个 tree 之间改了哪些文件、各自的补丁。补丁总量封顶 300 KB（超出的文件只剩名字） */
    fun diffScript(base: String, end: String): String {
        require(isHash(base) && isHash(end)) { "不是 tree 哈希" }
        return """echo "TOP=${'$'}(git rev-parse --show-toplevel 2>/dev/null)"
echo "$NAMES"
git -c core.quotepath=false diff --no-color --no-ext-diff --name-only $base $end 2>/dev/null
echo "$PATCH"
git -c core.quotepath=false diff --no-color --no-ext-diff $base $end 2>/dev/null | head -c 300000"""
    }

    /** 一个被改的文件：[rel] 是相对仓库根的路径，[patch] 是它那一段统一 diff（可能为空：补丁被总量封顶截掉了） */
    class Change(val rel: String, val patch: String)

    class Diff(val top: String?, val changes: List<Change>)

    /** [diffScript] 的输出拆成文件清单与逐文件补丁；格式不对返回 null */
    fun parseDiff(output: String): Diff? {
        val lines = output.replace("\r\n", "\n").split('\n')
        val top = lines.firstOrNull { it.startsWith("TOP=") }?.removePrefix("TOP=")?.trim()?.takeIf { it.isNotEmpty() }
        val names = lines.indexOf(NAMES)
        val patch = lines.indexOf(PATCH)
        if (names < 0 || patch < names) return null
        val files = lines.subList(names + 1, patch).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = HashMap<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in lines.subList(patch + 1, lines.size)) {
            if (line.startsWith("diff --git ")) {
                // `diff --git a/旧 b/新`：取最后一个 ` b/` 之后的是新路径（重命名时与 --name-only 给的一致）
                val path = line.substringAfterLast(" b/", "")
                current = StringBuilder().also { chunks[path] = it }
            }
            current?.append(line)?.append('\n')
        }
        return Diff(top, files.map { Change(it, chunks[it]?.toString().orEmpty()) })
    }

    /** 远端引用现在各指向哪个提交：`git push` 前后各取一次，差出来的就是这次推出去的 */
    const val REMOTE_REFS_SCRIPT: String = "git for-each-ref --format='%(objectname) %(refname)' refs/remotes"

    fun parseRefs(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ', limit = 2)
        if (parts.size == 2 && isHash(parts[0])) parts[1] to parts[0] else null
    }.toMap()

    /** 这次推送新到的提交们：指向变了或新出现的远端引用的新位置。远端引用本身是别名（`origin/HEAD`）也无妨：它指向的提交相同、会被去重 */
    fun pushedTips(before: Map<String, String>, after: Map<String, String>): List<String> =
        after.filter { (ref, hash) -> before[ref] != hash }.values.distinct()

    /** 一次推送最多审多少个提交 */
    const val MAX_PUSH_COMMITS = 50

    /**
     * 推出去的全部提交的补丁（旧的在前）。[tips] 是新到的位置，[known] 是推送前所有远端引用的位置——只算「推送前远端还没有」的提交。
     * 输出第一行是提交总数，之后是 `git log -p`；补丁总量封顶 300 KB、最多 [MAX_PUSH_COMMITS] 个提交。
     */
    fun pushLogScript(tips: List<String>, known: Collection<String>): String {
        require(tips.isNotEmpty() && (tips + known).all(::isHash)) { "不是提交哈希" }
        val revs = tips.joinToString(" ")
        val negatives = known.distinct().joinToString(" ").let { if (it.isEmpty()) "" else " --not $it" }
        return """echo "COUNT=${'$'}(git rev-list --count $revs$negatives 2>/dev/null)"
git -c core.quotepath=false log -p --no-color --no-ext-diff --reverse --max-count=$MAX_PUSH_COMMITS --format='commit %H%n%s%n' $revs$negatives 2>/dev/null | head -c 300000"""
    }

    class PushLog(val commits: Int, val text: String)

    fun parsePushLog(output: String): PushLog? {
        val normalized = output.replace("\r\n", "\n")
        val first = normalized.substringBefore('\n')
        val count = first.removePrefix("COUNT=").trim().toIntOrNull().takeIf { first.startsWith("COUNT=") } ?: return null
        val text = normalized.substringAfter('\n', "")
        return if (count <= 0 || text.isBlank()) null else PushLog(count, text)
    }
}
