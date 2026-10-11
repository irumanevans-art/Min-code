package dev.min.agent.kernel

import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.ShellCommand
import dev.min.agent.spi.ShellPreamble
import dev.min.agent.spi.catchingNonCancellation

/*
 * `CLAUDE_ENV_FILE`（官方 hooks 页「Persist environment variables」「Persisted variables in PowerShell commands」、env-vars 页、
 * tools-reference「What persists between commands」）：
 * - SessionStart、Setup、CwdChanged、FileChanged 四种 hook 的环境里有 `CLAUDE_ENV_FILE`（其他事件没有），hook 往里 `>>` 追加 `export` 语句；
 * - 之后每条 Bash 命令先把文件内容当 shell 代码执行，再跑命令本身（原文：「Each Bash command runs the file's contents as shell code before
 *   the command itself」）；Skill 的 `!` 预执行与 Monitor 同样经这条路（见下面「范围」）；
 * - CwdChanged / FileChanged 写的变量「persist … until the next CwdChanged event, when Claude Code clears them」：下一次 CwdChanged 触发时清掉；
 * - 用户启动前自己把 `CLAUDE_ENV_FILE` 设成一个脚本：Claude Code 也先执行它（env-vars 页：「Path to a shell script whose contents Claude Code
 *   runs before each Bash command」），与 hook 写的合在一起（PowerShell 一节：「what every hook in this session wrote and any script you set
 *   `CLAUDE_ENV_FILE` to before launch」）。
 *
 * 原文没写、这里自己定的（按「每个会话一份、在会话目录里、续接沿用」）：
 * - 文件放在**会话附件目录**的 `env/` 下（[ArtifactSink.locate]；桌面 `<home>/sessions/<id>/artifacts/env/`，Android `/root/.min/artifacts/<id>/env/`），
 *   所以并发的多个会话互不串，续接同一个会话沿用同一份；`/clear` 在 Min 里是新会话，因此从空白开始；压缩不换会话，文件原样保留；
 * - hook 拿到的 `CLAUDE_ENV_FILE` 一律指向**我们自己的**会话文件，不写用户预设的那个脚本：用户的文件是他的配置，不被 hook 改写，也不会被
 *   两个会话同时追加；用户的脚本照原文被先执行（只读）；
 * - 每种触发各一个文件：`setup.sh`、`sessionstart-<source>.sh`、`cwd.sh`（CwdChanged 与 FileChanged 共用，因为清理的时机相同）。SessionStart / Setup
 *   的 hook 每次要跑时先把**自己的**文件清空：续接、压缩后同一批 hook 重跑，`export PATH="$PATH:x"` 之类的追加不会越堆越多，
 *   又不会碰别的触发写的；
 * - 子 agent 会话没有自己的文件：它的 Bash 沿用根会话那一份（同一个会话，同一个环境），但不给它的 hook `CLAUDE_ENV_FILE`、也不清理。
 *
 * 为什么执行它不放宽权限：文件内容是用户配置的 hook 写的 shell 代码（项目层 hook 要工作区受信任才会跑，同一道闸），本来就握着 hook
 * 的全部权力；前导在内核判定**之后**才接到脚本前（[ShellCommand.preamble]），命令本身照常走权限分析，沙箱 / 网络批准 / 隔离照常罩住整个
 * 进程。要堵的口子是「模型自己往这个文件里写东西」：它等同 shell 启动脚本，所以会话的 `env/` 目录在权限底线里（[PolicyContext.shellEnvDir]），
 * 任何模式写它都要人确认——和写 `.bashrc` 同级。
 *
 * 范围：只有 Bash、Monitor、Skill 的 `!` 预执行三条经 [SessionRuntime.runNetworkShell] 的路径会带前导；hook 命令、git、worktree 脚本等宿主自己起的进程不带。
 */
internal class SessionEnvFiles(private val rt: SessionRuntime) {
    private val deps = rt.deps

    /** 文件归属的会话：子 agent 沿用根会话 */
    private val owner get() = rt.state.value.origin?.root ?: rt.sessionId

    /** 会话的 env 目录（guest 路径）；宿主说不出附件路径时为 null，整个功能关闭 */
    fun dir(): GuestPath? =
        deps.host.artifacts(owner).locate("$DIR/$PROBE")?.let { GuestPath(it.substringBeforeLast('/')) }

    private fun locate(file: String): String? = deps.host.artifacts(owner).locate("$DIR/$file")

    /**
     * [event]（对 [target]）要跑的 hook 该拿到的 `CLAUDE_ENV_FILE`；这个事件没有这个变量、子 agent、宿主说不出路径时返回 null。
     * SessionStart / Setup 在这里先清空自己的文件（调用时已确定有 command hook 要跑）。路径写成 hook 的 shell 看得见的样子
     */
    suspend fun forHook(event: HookEvent, target: String?): String? {
        if (event !in HOOK_EVENTS || rt.state.value.origin != null) return null
        val name = fileName(event, target)
        val path = locate(name) ?: return null
        if (event == HookEvent.SessionStart || event == HookEvent.Setup) {
            // 重跑要幂等；清空失败（宿主写不了）不该挡住 hook，只是这次的追加会叠在旧内容后面
            catchingNonCancellation { deps.host.artifacts(owner).put("$DIR/$name", ByteArray(0)) }
        } else {
            // CwdChanged / FileChanged 的文件不在这里清（清理在 [clearOnCwdChange]），但 hook 的 `>>` 不会替我们建目录：
            // 目录还不存在（这个会话没跑过 SessionStart 的写入）时先落一个空文件。说不清有没有就当有，免得截断别人写的
            val exists = catchingNonCancellation { deps.host.fs.stat(GuestPath(path)) != null }.getOrDefault(true)
            if (!exists) catchingNonCancellation { deps.host.artifacts(owner).put("$DIR/$name", ByteArray(0)) }
        }
        return deps.hookShellPath(GuestPath(path))
    }

    /** CwdChanged 触发时清掉 cwd.sh（原文：下一次 CwdChanged 时 Claude Code 清掉 CwdChanged / FileChanged 写的变量）。不存在或本来就空就不动 */
    suspend fun clearOnCwdChange() {
        if (rt.state.value.origin != null) return
        val path = locate(CWD_FILE) ?: return
        val size = catchingNonCancellation { deps.host.fs.stat(GuestPath(path))?.size }.getOrNull() ?: return
        if (size > 0) catchingNonCancellation { deps.host.artifacts(owner).put("$DIR/$CWD_FILE", ByteArray(0)) }
    }

    /**
     * 每条命令前要 source 的文件，成前导脚本；一个都没有就是 null（没配 hook 的会话命令原样，不多一行）。
     * 顺序：用户启动前设的脚本、setup、SessionStart、CwdChanged / FileChanged ——后写的盖前面的
     */
    suspend fun preamble(): String? {
        val files = buildList {
            deps.env(ENV_FILE_VAR)?.takeIf { it.isNotBlank() }?.let { add(shellPathOfUser(it)) }
            for (name in ORDER) {
                val path = locate(name) ?: continue
                // stat 说「没有」或「空」就跳过；说不出（抛异常）按存在算：多 source 一个不存在的文件也无妨，有 [ -f ] 兜着
                val stat = catchingNonCancellation { deps.host.fs.stat(GuestPath(path)) }
                if (stat.isSuccess && (stat.getOrNull() == null || stat.getOrNull()!!.size == 0L)) continue
                add(deps.hookShellPath(GuestPath(path)))
            }
        }
        return ShellPreamble.forFiles(files)
    }

    /** 给命令套上前导（权限判定已经做完；[ShellCommand.script] 不变） */
    suspend fun arm(command: ShellCommand): ShellCommand {
        val p = preamble() ?: return command
        return command.copy(preamble = p)
    }

    private companion object {
        const val DIR = "env"
        /** 只用来问目录在哪，不会被写 */
        const val PROBE = "dir"
        const val CWD_FILE = "cwd.sh"
        const val ENV_FILE_VAR = "CLAUDE_ENV_FILE"
        val HOOK_EVENTS = setOf(HookEvent.SessionStart, HookEvent.Setup, HookEvent.CwdChanged, HookEvent.FileChanged)
        /** SessionStart 的 source 取值（官方：startup / resume / clear / compact，另有 fork）；不认识的归一个文件，名字不由外部拼 */
        val SOURCES = listOf("startup", "resume", "clear", "compact", "fork")
        val ORDER = listOf("setup.sh") + SOURCES.map { "sessionstart-$it.sh" } + "sessionstart-other.sh" + CWD_FILE

        fun fileName(event: HookEvent, target: String?): String = when (event) {
            HookEvent.Setup -> "setup.sh"
            HookEvent.SessionStart -> "sessionstart-${target?.takeIf { it in SOURCES } ?: "other"}.sh"
            else -> CWD_FILE
        }

        /** 用户在环境里设的路径：Windows 的盘符写法换成 Git Bash 能读的正斜杠（`C:\x\env.sh` → `C:/x/env.sh`）；其他原样 */
        fun shellPathOfUser(raw: String): String =
            if (Regex("^[A-Za-z]:[\\\\/].*").matches(raw)) raw.replace('\\', '/') else raw
    }
}
