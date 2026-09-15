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

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        checkAvailability(context)?.let { reason ->
            return WorkspaceCommandResult(exitCode = 127, stdout = "", stderr = reason)
        }
        val process = launch(context) ?: return WorkspaceCommandResult(
            exitCode = 127,
            stdout = "",
            stderr = "Failed to start proot process",
        )
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /**
     * 环境自检。返回 null 表示可用，否则返回具体到"缺哪一样"的原因 ——
     * 合并成一句笼统的错误会让用户没法判断该去装 Rootfs 还是该重装 App。
     */
    fun checkAvailability(context: WorkspaceShellContext): String? {
        if (!context.linuxDir.hasUsableRootfs()) return "Rootfs is not installed"
        if (!File(nativeLibraryDir, PROOT_EXEC).isFile) {
            return "proot executable not found: ${File(nativeLibraryDir, PROOT_EXEC).absolutePath}"
        }
        if (!File(nativeLibraryDir, PROOT_LOADER).isFile) {
            return "proot loader not found: ${File(nativeLibraryDir, PROOT_LOADER).absolutePath}"
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

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        return ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
    }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
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
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
        )
        // 调用方注入的额外环境变量 (如 ANTHROPIC_*), key 做白名单校验, 值作为独立 argv 不经 shell。
        // 必须排在上面那批之后: `env -i` 里同名变量后者覆盖前者, 调用方因此可以按需
        // 覆盖 CI / NO_COLOR / PAGER 这些默认值。
        context.env.forEach { (key, value) ->
            require(ENV_KEY_REGEX.matches(key)) { "Invalid env key: $key" }
            command += "$key=$value"
        }
        command += listOf(
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
        return command
    }

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
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val ENV_PATH = "PATH"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        private val ENV_KEY_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
