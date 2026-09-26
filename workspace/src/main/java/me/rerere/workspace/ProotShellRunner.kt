package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

/**
 * proot 命令行的**唯一**拼装处。
 *
 * 三条路径 —— Claude 会话、长驻本地服务、终端页签 —— 都必须从这里出命令行。
 * 终端页签不走 [launch]（它要把 argv 交给 pty 去 fork），但仍然走 [buildCommand] /
 * [hostEnvironment]：曾经它自己抄过一份，抄出来的那份少了几样东西，谁都没发现。
 *
 * 路径之间**合理**的差异全部用 [WorkspaceShellContext] 的字段表达
 * （[WorkspaceShellContext.killOnExit]、[WorkspaceShellContext.entry]、
 * [WorkspaceShellContext.env]…），不要在调用方另拼一份。
 */
class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    /** proot 可执行文件。自己 fork 进程的调用方从这里取, 别再抄一遍 so 名字。 */
    val prootExecutable: File get() = File(nativeLibraryDir, PROOT_EXEC)

    /** proot 的 loader。只通过 [hostEnvironment] 传给子进程。 */
    val prootLoader: File get() = File(nativeLibraryDir, PROOT_LOADER)

    /**
     * 宿主侧要带给 proot 进程的环境变量。
     * 少一条 PROOT_LOADER 就是"启动了但立刻死掉"，所以这份表也必须只有一个来源。
     */
    fun hostEnvironment(context: WorkspaceShellContext): Map<String, String> = mapOf(
        "PROOT_LOADER" to prootLoader.absolutePath,
        "PROOT_TMP_DIR" to context.tempDir.absolutePath,
        "TMPDIR" to context.tempDir.absolutePath,
    )

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        checkAvailability(context)?.let { reason ->
            return WorkspaceCommandResult(exitCode = 127, stdout = "", stderr = reason)
        }
        val process = launch(context) ?: return WorkspaceCommandResult(
            exitCode = 127,
            stdout = "",
            stderr = "Failed to start proot process",
        )
        // proot 扛得住 SIGTERM（ProcessTreeKill「教训二」），超时/取消的收尾必须走 reap：
        // 自底向上清掉 guest 树，再给宿主真 SIGKILL。宿主 shell 那条路保留默认的
        // destroyForcibly —— readResult 是共享的，reap 对它是误伤
        return process.readResult(context.timeoutMillis, context.stdin) { ProcessTreeKill.reap(it) }
    }

    /**
     * 环境自检。返回 null 表示可用，否则返回具体到"缺哪一样"的原因 ——
     * 合并成一句笼统的错误会让用户没法判断该去装 Rootfs 还是该重装 App。
     */
    fun checkAvailability(context: WorkspaceShellContext): String? {
        if (!context.linuxDir.hasUsableRootfs()) return "Rootfs is not installed"
        if (!prootExecutable.isFile) {
            return "proot executable not found: ${prootExecutable.absolutePath}"
        }
        if (!prootLoader.isFile) {
            return "proot loader not found: ${prootLoader.absolutePath}"
        }
        return null
    }

    /**
     * 启动一个长存活的 Rootfs 进程并直接返回 [Process], 调用方自行读写 stdin/stdout。
     * 用于 Claude Code 这类需要流式双向通信的会话; 一次性命令请用 [execute]。
     * 返回 null 表示环境不可用, 具体原因用 [checkAvailability] 取。
     */
    fun launch(context: WorkspaceShellContext): Process? {
        if (checkAvailability(context) != null) return null

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(buildCommand(context))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply { environment().putAll(hostEnvironment(context)) }
            .start()
    }

    /**
     * 拼出完整的 proot 命令行, 下标 0 是 proot 可执行文件本身。
     *
     * 公开是为了收口: 终端页签把 argv 交给 pty 自己 fork, 没法走 [launch], 但拼装必须同源。
     */
    fun buildCommand(context: WorkspaceShellContext): List<String> {
        val command = mutableListOf(
            prootExecutable.absolutePath,
            "--root-id",
            "--link2symlink",
        )
        // 长驻服务关 kill-on-exit；会话 / 一次性命令保持默认开
        if (context.killOnExit) {
            command += "--kill-on-exit"
        }
        command += listOf(
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        // /dev/shm 替身 + 宿主读不到的 /proc 条目的假文件，理由见 ProotCompat。
        // 必须排在上面的 /dev、/proc 之后：proot 对重叠绑定按最长路径匹配，顺序本身不重要，
        // 但放在这里读起来清楚——"先绑真的，再打补丁"。
        ProotCompat.extraBinds(context.tempDir).forEach { (host, guest) ->
            command += "-b"
            command += "${host.absolutePath}:$guest"
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=$DEFAULT_GUEST_PATH",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // proot 恒以 --root-id 跑、HOME 恒为 /root, 所以这两条在任何路径下都成立;
            // 少了它们的话 guest 里的 `$USER` 是空的, 一批脚本 (npm/git 的 hook、oh-my-*) 会走错分支
            "USER=root",
            "SHELL=/bin/bash",
            // rootfs 里谁要开网页都交给 Min（见 GuestBrowserBridge）。放在基础环境而不是各调用方：
            // 会话、终端、托管服务、`!` 命令、Codex 全从这里出命令行，漏一处就是一种命令开不了浏览器。
            // 调用方的 env 排在后面，要换成自己的 BROWSER（订阅登录那条路）照样能覆盖
            "${GuestBrowserBridge.ENV_KEY}=${GuestBrowserBridge.SCRIPT_GUEST}",
        )
        if (context.entry == ProotShellEntry.LoginCommand) {
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)。
            // 终端页签是真交互 (用户在看), 给它关掉颜色、把分页器换成 cat 反而是错的,
            // 所以这三条跟着 entry 走。
            command += listOf("CI=true", "NO_COLOR=1", "PAGER=cat")
        }
        // 调用方注入的额外环境变量 (如 ANTHROPIC_*), key 做白名单校验, 值作为独立 argv 不经 shell。
        // 必须排在上面那批之后: `env -i` 里同名变量后者覆盖前者, 调用方因此可以按需
        // 覆盖 CI / NO_COLOR / PAGER 这些默认值。
        context.env.forEach { (key, value) ->
            require(ENV_KEY_REGEX.matches(key)) { "Invalid env key: $key" }
            command += "$key=$value"
        }
        command += when (context.entry) {
            ProotShellEntry.LoginCommand -> listOf(
                "/bin/bash",
                "-l",
                "-c",
                // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
                //
                // PATH 要在这里再 export 一次: `bash -l` 是登录 shell, 会 source /etc/profile,
                // 那里的 PATH 赋值会把 `env -i PATH=...` 传进来的值整个覆盖掉。调用方注入的
                // PATH（如 /opt/node/bin）必须在 profile 跑完之后才生效。
                "[ -n \"\$3\" ] && export PATH=\"\$3\"; cd -- \"\$1\" && eval \"\$2\"",
                "rikkahub",
                context.prootCwd(),
                context.command,
                context.env[ENV_PATH].orEmpty(),
            )
            // 交互 shell: 起在哪由上面的 `-w` 决定, 没有要 eval 的命令
            ProotShellEntry.InteractiveShell -> listOf("/bin/bash")
        }
        return command
    }

    /** [cwd] 是相对 [WORKSPACE_DIR] 的路径, 这里换算成 guest 侧绝对路径给 `-w` */
    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        /** Rootfs 里的默认 PATH；调用方要加目录 (如 /opt/node/bin) 用 env 注入覆盖 */
        private const val DEFAULT_GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val ENV_PATH = "PATH"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
