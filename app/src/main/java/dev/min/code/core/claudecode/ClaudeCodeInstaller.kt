package dev.min.code.core.claudecode

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.min.code.core.rootfs.DeviceArch
import dev.min.code.core.rootfs.WorkspaceRepository
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Claude Code CLI 安装器：把 Node.js 运行时与官方 @anthropic-ai/claude-code
 * 装进指定工作区的 Rootfs（Ubuntu base, proot 运行），并负责之后的**维护**
 * （升级 CLI、升级 Ubuntu 软件包、重装损坏的 Node）。
 *
 * 布局（Rootfs 内）：
 * - /opt/node/                          Node.js LTS 静态包
 * - /opt/node/bin/claude                npm 建的入口软链（global prefix=/opt/node）
 * - /etc/profile.d/node.sh              把 /opt/node/bin 写进登录 shell 的 PATH
 *
 * ## 2.x 的包是怎么组成的（决定了下面为什么要分两步装）
 *
 * `@anthropic-ai/claude-code` 本体只是一个几 KB 的 wrapper：`bin/claude.exe` 是一个不到 4KB 的
 * 占位 stub，真正的 CLI 是 optionalDependencies 里按平台拆开的原生二进制包
 * （`@anthropic-ai/claude-code-linux-arm64`，解压后 200 MB+）。postinstall（install.cjs）
 * 把那个二进制硬链接到 `bin/claude.exe` 上，stub 就被换掉了。
 *
 * 问题在于那 100 MB 的 tarball 走 npm 下：npm 对 optional 依赖的失败是**静默跳过**的
 * （fetch 超时、连接被重置、磁盘不够都算），exit code 照样是 0，而且还会留下一个只有
 * package.json 没有二进制的半截目录。结果就是 wrapper 在、版本号在、`claude --version`
 * 都能打印，一启动会话就 `spawnSync .../claude-code-linux-arm64/claude ENOENT`。
 * 国内网络下这几乎必现。
 *
 * 所以这里让 npm 只装 wrapper（`--os=freebsd` 让它跳过所有平台包），原生二进制由 App 自己下：
 * 断点续传、官方源/镜像回退、拿官方 registry 的 sha512 校验，复制成 `bin/claude.exe`。
 * 状态判断也不再只看 bin 软链在不在，而是看 `bin/claude.exe` 是不是真的二进制。
 *
 * 安装只需一次；host 侧直接探测 rootfs 目录判断状态，无需起 proot。
 */
class ClaudeCodeInstaller(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * wrapper + native 的配对状态（[PREFS_NAME]，按工作区分键）。
     * native 装到一半失败时 wrapper 已是新版、旧二进制还完整，文件层面看不出错配，
     * 只能靠这个标记让 [updateCli] 自愈。
     */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    sealed interface InstallState {
        data class Downloading(val progress: Float, val detail: String) : InstallState
        data class Running(val detail: String) : InstallState
        /** @param cliVersion `claude --version` 的输出，装不出来时为 null */
        data class Done(val cliVersion: String?) : InstallState
        data class Failed(val message: String) : InstallState
    }

    data class CliStatus(
        val rootfsReady: Boolean,
        val nodeInstalled: Boolean,
        /** npm 包（wrapper）在：bin 软链 / 包目录任一存在 */
        val packageInstalled: Boolean,
        /** 原生二进制真的在，不是 postinstall 之前那个 4KB 占位 stub */
        val nativeBinaryReady: Boolean,
        /** 已安装的 CLI 版本，直接读包里的 package.json，不用起 proot */
        val cliVersion: String? = null,
    ) {
        /** 能启动会话 */
        val claudeInstalled: Boolean get() = packageInstalled && nativeBinaryReady

        /** wrapper 装上了但二进制没到位：上次装/更新时那个 100 MB 的平台包没下完 */
        val cliIncomplete: Boolean get() = packageInstalled && !nativeBinaryReady

        val ready: Boolean get() = rootfsReady && nodeInstalled && claudeInstalled
    }

    suspend fun status(workspaceId: String): CliStatus = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getById(workspaceId)
        val root = workspace?.root
        val linuxDir = root?.let { linuxDir(it) }
        CliStatus(
            rootfsReady = workspace != null && linuxDir != null &&
                File(linuxDir, "bin/sh").isFile &&
                workspace.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name,
            nodeInstalled = linuxDir?.let { File(it, NODE_BIN).isFile } == true,
            packageInstalled = linuxDir?.let { isPackageInstalled(it) } == true,
            nativeBinaryReady = linuxDir?.let { isNativeBinaryReady(it) } == true,
            cliVersion = linuxDir?.let { readInstalledCliVersion(it) },
        )
    }

    /** 从 Rootfs 里那份 package.json 直接读版本号 */
    private fun readInstalledCliVersion(linuxDir: File): String? = runCatching {
        val pkg = File(linuxDir, CLAUDE_PACKAGE_MARKER)
        if (!pkg.isFile) return@runCatching null
        Json.parseToJsonElement(pkg.readText()).jsonObject["version"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * 安装 Node.js + claude-code。幂等：已安装的步骤会跳过。
     *
     * @param forceNode 即使 Node 已在也重下重装。**几十 MB 的下载**，只有修复损坏的运行时才用；
     *   想单独升级 CLI 请走 [updateCli]。
     * @param forceCli 即使 CLI 已在也重跑 npm install（等价于升级到 @latest）。
     * @param useNpmMirror 走淘宝 npm 源装 claude-code。**默认关**：那等于从第三方镜像
     *   取 CLI 本体，是一次实打实的供应链信任转移，必须由用户显式打开。
     *   （Node 那一步不受影响：无论从哪下，都拿官方 SHA-256 校验，镜像只加速不降低可信度。）
     */
    suspend fun install(
        workspaceId: String,
        forceNode: Boolean = false,
        forceCli: Boolean = false,
        useNpmMirror: Boolean = false,
        onState: suspend (InstallState) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val linuxDir = linuxDir(workspace.root)
        require(File(linuxDir, "bin/sh").isFile) { "Rootfs 尚未安装，请先在工作区详情页安装 Linux 环境" }

        try {
            // 1. Node.js
            if (forceNode || !File(linuxDir, NODE_BIN).isFile) {
                installNode(workspaceId, workspace.root, onState)
            } else {
                Log.i(TAG, "Node already installed, skip")
            }

            // 2. claude-code wrapper（npm）+ 3. 平台原生二进制（App 自己下）
            if (forceCli || !isPackageInstalled(linuxDir)) {
                installCliPackage(workspaceId, useNpmMirror, onState)
            }
            if (forceCli || !isNativeBinaryReady(linuxDir)) {
                installNativeBinary(workspaceId, workspace.root, linuxDir, useNpmMirror, onState)
            }
            verifyCliRunnable(linuxDir)

            // 4. 让登录 shell 也能找到 node/npm —— Claude Code 的 Bash 工具走 `bash -l`,
            //    /etc/profile 会重置 PATH，光靠进程环境变量传不进去。
            writeProfileScript(linuxDir)

            // 版本号要落到 UI，不能只写日志：@latest 装的版本会随时间漂移，
            // 出问题时不知道装的是哪一版就没法定位
            onState(InstallState.Done(probeCliVersion(workspaceId, linuxDir)))
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            onState(InstallState.Failed(e.message ?: e.toString()))
            throw e
        }
    }

    /**
     * 只升级 CLI，不碰 Node。
     *
     * 和 `install(forceCli = true)` 的区别在于**明确拒绝**在没有 Node 的情况下工作，
     * 而不是顺手把几十 MB 的运行时重下一遍 —— 用户点的是「更新 Claude Code」，
     * 不该因此产生一次意料之外的大流量下载。
     */
    suspend fun updateCli(
        workspaceId: String,
        useNpmMirror: Boolean = false,
        onState: suspend (InstallState) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val linuxDir = linuxDir(workspace.root)
        require(File(linuxDir, "bin/sh").isFile) { "Rootfs 尚未安装，请先在工作区详情页安装 Linux 环境" }
        require(File(linuxDir, NODE_BIN).isFile) {
            "Rootfs 内找不到 Node.js，无法升级 CLI。请先在「修复」里重装 Node 运行时"
        }

        try {
            // 原生二进制是拷到 bin/claude.exe 再删掉平台包目录，不是硬链接。
            // npm @latest 换了 wrapper 之后，旧拷贝可能还在（或只剩 stub / 遗留 cli.js），
            // 不能只看「文件够大」——声明版本变了就必须重下，否则会话按新版本号跑旧 native。
            val pkg = nativePackageName()
            val versionBefore = readDeclaredNativeVersion(linuxDir, pkg)
            installCliPackage(workspaceId, useNpmMirror, onState)
            val versionAfter = readDeclaredNativeVersion(linuxDir, pkg)
            // 脏标记 / 配对版本不一致：上次更新 native 装到一半失败，wrapper 已是新版而
            // 旧二进制还完整，「声明版本没变 + 文件够大」永远发现不了这种错配，必须强制重装。
            val pairDirty = isNativePairDirty(workspaceId)
            val pairMismatch = versionAfter != null &&
                pairedNativeVersion(workspaceId)?.let { it != versionAfter } == true
            if (shouldRefreshNativeBinary(
                    nativeExecutablePresent(linuxDir),
                    versionBefore,
                    versionAfter,
                ) || pairDirty || pairMismatch
            ) {
                installNativeBinary(workspaceId, workspace.root, linuxDir, useNpmMirror, onState)
            }
            verifyCliRunnable(linuxDir)
            // npm 可能重建 bin 软链，profile 脚本本身幂等，顺手补一次不亏
            writeProfileScript(linuxDir)
            onState(InstallState.Done(probeCliVersion(workspaceId, linuxDir)))
        } catch (e: Exception) {
            // 任一步失败都可能留下「wrapper 新 / native 旧但完整」的半截状态，置脏让下次强制重装 native
            markNativePairDirty(workspaceId)
            Log.e(TAG, "updateCli failed", e)
            onState(InstallState.Failed(e.message ?: e.toString()))
            throw e
        }
    }

    private fun isNativePairDirty(workspaceId: String): Boolean =
        prefs.getBoolean("$KEY_NATIVE_PAIR_DIRTY.$workspaceId", false)

    /** 上次 wrapper + native 配对成功时的 native 包版本；null = 从未记录（老安装） */
    private fun pairedNativeVersion(workspaceId: String): String? =
        prefs.getString("$KEY_NATIVE_PAIRED_VERSION.$workspaceId", null)

    private fun markNativePairDirty(workspaceId: String) {
        prefs.edit().putBoolean("$KEY_NATIVE_PAIR_DIRTY.$workspaceId", true).apply()
    }

    private fun markNativePaired(workspaceId: String, version: String) {
        prefs.edit()
            .putBoolean("$KEY_NATIVE_PAIR_DIRTY.$workspaceId", false)
            .putString("$KEY_NATIVE_PAIRED_VERSION.$workspaceId", version)
            .apply()
    }

    /**
     * npm 装 claude-code wrapper 这一步。[install] 与 [updateCli] 共用，两边的命令必须一模一样。
     *
     * `--os=freebsd`：让 npm **跳过**那些按平台拆分的 optional 二进制包（它们都声明了
     * `os: [linux|darwin|win32]`，平台对不上的 optional 依赖 npm 会静默跳过）。
     * 为什么不用 `--omit=optional`：实测 npm 10/11 对 `install -g <pkg>` 根本不理这个 flag，
     * 平台包照下不误 —— 而它一旦下失败就是静默的、留半截目录的那种失败。
     * wrapper 本身没有 os 字段，不受影响；它的 postinstall 找不到平台包只会打印一句提示，exit 0。
     * 二进制由 [installNativeBinary] 用可续传、可校验的方式单独下。
     */
    private suspend fun installCliPackage(
        workspaceId: String,
        useNpmMirror: Boolean,
        onState: suspend (InstallState) -> Unit,
    ) {
        val registry = if (useNpmMirror) NPM_MIRROR_REGISTRY else null
        onState(
            InstallState.Running(
                "正在安装 @anthropic-ai/claude-code（npm${if (useNpmMirror) "·淘宝源" else ""}）…"
            )
        )
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = buildString {
                append("$NPM_BIN_GUEST install -g --prefix /opt/node --no-fund --no-audit ")
                append("--os=freebsd --loglevel warn ")
                if (registry != null) append("--registry $registry ")
                append("$CLI_PACKAGE@latest")
            },
            timeoutMillis = NPM_TIMEOUT_MS,
            env = nodeEnv(),
        )
        if (result.exitCode != 0) {
            error("npm install 失败 (exit ${result.exitCode}): ${result.stderr.ifBlank { result.stdout }.take(800)}")
        }
    }

    /**
     * 下载并放置平台原生二进制（wrapper 的 optionalDependencies 里那个平台包）。
     *
     * 步骤：
     * 1. 读 wrapper 的 package.json，拿到平台包名与**精确版本**（和 wrapper 严格配对）；
     * 2. 向官方 registry 要这个版本的元数据，取 `dist.integrity`（sha512）；
     * 3. 断点续传下载 tarball（官方源 → 镜像；用户勾了镜像则镜像在前），按 sha512 校验；
     * 4. 在 Rootfs 里解压，把二进制 **复制**成 `bin/claude.exe`（wrapper 的 bin 软链指向它），
     *    然后删掉解压目录。不用包自带 install.cjs 的硬链接：proot 下没有真硬链接，
     *    `--link2symlink` 会把它做成指向 `.l2s.*` 的软链，一旦平台包目录被 npm 清掉就悬空。
     *    复制多占 200 MB，换来的是这个文件从此自给自足。
     *
     * 校验和优先取自**官方** registry，镜像只提供字节：镜像换了内容对不上就删档重来。
     * 只有官方元数据也拿不到时才退回镜像的元数据（那时信任来源就是镜像，和勾选镜像装
     * wrapper 是同一级别，日志里会记一笔）。
     */
    private suspend fun installNativeBinary(
        workspaceId: String,
        root: String,
        linuxDir: File,
        useNpmMirror: Boolean,
        onState: suspend (InstallState) -> Unit,
    ) {
        val pkg = nativePackageName()
        val version = readDeclaredNativeVersion(linuxDir, pkg)
        if (version == null) {
            // 没声明这个平台包：要么包布局变了（未来版本），要么是老的 cli.js 布局。
            // 交给 verifyCliRunnable 决定能不能跑，这里不硬来。
            Log.w(TAG, "wrapper package.json does not declare $pkg; skipping native binary step")
            return
        }

        onState(InstallState.Running("获取 $pkg@$version 的校验信息…"))
        try {
            val integrity = fetchNativeIntegrity(pkg, version)

            val filesDir = File(workspaceDir(root), "files").apply { mkdirs() }
            requireFreeSpace(filesDir, NATIVE_REQUIRED_BYTES, "Claude Code 原生二进制")
            val tarball = File(filesDir, NATIVE_ARCHIVE_NAME)
            fetchVerified(
                sources = nativeTarballUrls(pkg, version, mirrorFirst = useNpmMirror),
                dest = tarball,
                expected = integrity,
                what = "Claude Code 原生二进制（约 100 MB）",
                onState = onState,
            )

            onState(InstallState.Running("安装原生二进制…"))
            val result = workspaceRepository.executeCommand(
                id = workspaceId,
                command = """
                set -e
                PKG="/$CLAUDE_PKG_DIR"
                DEST="${'$'}PKG/node_modules/$pkg"
                rm -rf "${'$'}DEST"
                mkdir -p "${'$'}DEST"
                tar -xzf /workspace/$NATIVE_ARCHIVE_NAME -C "${'$'}DEST" --strip-components=1
                chmod 755 "${'$'}DEST/claude"
                rm -f /workspace/$NATIVE_ARCHIVE_NAME
                cd "${'$'}PKG"
                mkdir -p bin
                cp "${'$'}DEST/claude" bin/claude.exe.tmp
                chmod 755 bin/claude.exe.tmp
                mv -f bin/claude.exe.tmp bin/claude.exe
                rm -rf "${'$'}DEST"
            """.trimIndent(),
                timeoutMillis = NATIVE_TIMEOUT_MS,
                env = nodeEnv(),
            )
            if (result.exitCode != 0) {
                error("放置原生二进制失败 (exit ${result.exitCode}): ${result.stderr.ifBlank { result.stdout }.take(800)}")
            }
            if (!isNativeBinaryReady(linuxDir)) {
                error("原生二进制安装后仍未就绪：$CLI_NATIVE_BIN 不存在或不是完整文件")
            }
            // 只有走到这里 wrapper + native 才算真的配上对，落版本标记给 updateCli 判错配用
            markNativePaired(workspaceId, version)
        } catch (e: Exception) {
            // 中途失败：wrapper 可能已是新版而旧二进制还完整，文件层面看不出错配，置脏逼下次重装
            markNativePairDirty(workspaceId)
            throw e
        }
    }

    /** 装完必须真的能跑：包在但二进制缺失的状态**不算装好**，否则一启动会话就是 ENOENT */
    private fun verifyCliRunnable(linuxDir: File) {
        if (claudeEntry(linuxDir) == null) error(cliProblem(linuxDir))
    }

    /**
     * 为什么现在跑不了 —— 给 UI 和会话启动失败时用，比一句"找不到 CLI"有用得多。
     */
    fun cliProblem(linuxDir: File): String = when {
        !isPackageInstalled(linuxDir) -> "Rootfs 内找不到 claude CLI，请先在 Claude Code 页完成安装"
        !isNativeBinaryReady(linuxDir) ->
            "Claude Code 的原生二进制缺失：上次安装/更新时约 100 MB 的平台包没有下完，" +
                "只留下了一个占位文件。回到 Claude Code 页点「修复安装」补齐"
        else -> "claude CLI 状态异常，请重新安装"
    }

    /** wrapper 的 package.json 里 optionalDependencies 声明的平台包版本 */
    private fun readDeclaredNativeVersion(linuxDir: File, pkg: String): String? = runCatching {
        val file = File(linuxDir, CLAUDE_PACKAGE_MARKER)
        if (!file.isFile) return@runCatching null
        parseDeclaredNativeVersion(file.readText(), pkg)
    }.getOrNull()

    private suspend fun fetchNativeIntegrity(pkg: String, version: String): Digest {
        var lastError: Exception? = null
        for ((index, registry) in NPM_REGISTRIES.withIndex()) {
            try {
                val digest = parseIntegrity(httpGetText("$registry/$pkg/$version"))
                    ?: error("元数据里没有可用的 sha512 integrity")
                if (index > 0) Log.w(TAG, "integrity for $pkg@$version taken from mirror, not official registry")
                return digest
            } catch (e: Exception) {
                Log.w(TAG, "fetch integrity failed: $registry", e)
                lastError = e
            }
        }
        throw IllegalStateException("无法获取 $pkg@$version 的校验信息：${lastError?.message}", lastError)
    }

    private fun requireFreeSpace(dir: File, needed: Long, what: String) {
        val free = runCatching { android.os.StatFs(dir.absolutePath).availableBytes }.getOrNull() ?: return
        if (free < needed) {
            error("空间不足，无法安装${what}：需要约 ${needed / MB} MB，当前剩余 ${free / MB} MB")
        }
    }

    /** 装完/升完跑一次 `claude --version`，拿到的是带后缀的原始输出（`2.1.258 (Claude Code)`） */
    private suspend fun probeCliVersion(workspaceId: String, linuxDir: File): String? {
        val version = workspaceRepository.executeCommand(
            id = workspaceId,
            command = (claudeEntry(linuxDir) ?: listOf(CLAUDE_BIN_GUEST)).joinToString(" ") + " --version",
            timeoutMillis = VERSION_TIMEOUT_MS,
            // 和会话启动（ClaudeCodeManager）保持一致：每次内置调用 claude 都带这两个变量
            env = nodeEnv() + mapOf(
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
                "CLAUDE_CODE_ATTRIBUTION_HEADER" to "0",
            ),
        )
        Log.i(TAG, "claude --version: ${version.stdout.trim()} ${version.stderr.trim()}")
        return version.stdout.trim().takeIf { it.isNotBlank() }
    }

    // -----------------------------------------------------------------------
    // 维护：环境探测 / 版本检查 / apt 升级
    // -----------------------------------------------------------------------

    /** 「环境与更新」面板要显示的一切 */
    data class EnvironmentInfo(
        val osName: String? = null,
        val nodeVersion: String? = null,
        val npmVersion: String? = null,
        /** package.json 里的纯 semver（`2.1.258`）——**能直接和 npm 上的最新版比**。
         *  别拿 `claude --version` 的输出（`2.1.258 (Claude Code)`）去比，那个带后缀。 */
        val cliVersion: String? = null,
        /** wrapper 在但原生二进制缺失，见 [CliStatus.cliIncomplete] */
        val cliIncomplete: Boolean = false,
    )

    /**
     * 读环境信息。os-release 与 CLI 版本都是 host 侧直接读文件（零成本），
     * 只有 node/npm 版本要起一次 proot —— 所以两条命令合并成一次调用，不是两次。
     */
    suspend fun environment(workspaceId: String): EnvironmentInfo = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getById(workspaceId) ?: return@withContext EnvironmentInfo()
        val linuxDir = linuxDir(workspace.root)
        val (node, npm) = readRuntimeVersions(workspaceId, linuxDir)
        EnvironmentInfo(
            osName = readOsRelease(linuxDir),
            nodeVersion = node,
            npmVersion = npm,
            cliVersion = readInstalledCliVersion(linuxDir),
            cliIncomplete = isPackageInstalled(linuxDir) && !isNativeBinaryReady(linuxDir),
        )
    }

    private suspend fun readRuntimeVersions(workspaceId: String, linuxDir: File): Pair<String?, String?> {
        if (!File(linuxDir, NODE_BIN).isFile) return null to null
        return runCatching {
            val result = workspaceRepository.executeCommand(
                id = workspaceId,
                command = "$NODE_BIN_GUEST --version; $NPM_BIN_GUEST --version",
                timeoutMillis = VERSION_TIMEOUT_MS,
                env = nodeEnv(),
            )
            val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            lines.getOrNull(0) to lines.getOrNull(1)
        }.getOrElse {
            Log.w(TAG, "readRuntimeVersions failed", it)
            null to null
        }
    }

    /** Rootfs 是哪个发行版。纯 host 侧文件读，和 [readInstalledCliVersion] 一样不起 proot。 */
    fun readOsRelease(linuxDir: File): String? = runCatching {
        val file = File(linuxDir, OS_RELEASE_REL_PATH)
        if (!file.isFile) return@runCatching null
        parseOsPrettyName(file.readText())
    }.getOrNull()

    /**
     * 问 npm registry 要 claude-code 的最新版号。
     *
     * **官方源在前、镜像兜底**，和 [nodeTarballUrls] 同一套。这里和 `useNpmMirror` 那个
     * 供应链开关无关：取的只是一行元数据，不会有任何东西落地执行，所以镜像在这里
     * 纯粹是「官方连不上时还能查到」的兜底。
     */
    suspend fun fetchLatestCliVersion(): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in CLI_METADATA_URLS) {
            try {
                val version = parseVersionField(httpGetText(url))
                if (version != null) return@withContext version
                lastError = IllegalStateException("元数据里没有 version 字段：$url")
            } catch (e: Exception) {
                Log.w(TAG, "fetch latest cli version failed: $url", e)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法获取最新版本")
    }

    /**
     * 升级 Rootfs 里的 Ubuntu 软件包（Claude Code 自己 apt install 的 git / python3 / ripgrep 等）。
     *
     * 和 CLI 更新是两件事：CLI 走 npm，这些走 apt，谁也不影响谁。
     * `--force-confold` 保留已有配置文件 —— proot 里没有交互终端可以回答 dpkg 的冲突提问，
     * 不指定的话整个升级会卡在那里直到超时。
     *
     * @return 给 UI 回显的结果摘要（apt 自己那句 "N upgraded, N newly installed…"）
     */
    suspend fun upgradeApt(
        workspaceId: String,
        onState: suspend (InstallState) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val workspace = workspaceRepository.getById(workspaceId) ?: error("Workspace not found: $workspaceId")
        val linuxDir = linuxDir(workspace.root)
        require(File(linuxDir, "bin/sh").isFile) { "Rootfs 尚未安装，请先在工作区详情页安装 Linux 环境" }

        try {
            onState(InstallState.Running("正在刷新软件源（apt-get update）…"))
            val update = workspaceRepository.executeCommand(
                id = workspaceId,
                command = "apt-get update",
                timeoutMillis = APT_TIMEOUT_MS,
                env = aptEnv(),
            )
            if (update.exitCode != 0) {
                error("apt-get update 失败 (exit ${update.exitCode}): ${update.stderr.ifBlank { update.stdout }.take(800)}")
            }

            onState(InstallState.Running("正在升级已安装的软件包（可能要几分钟）…"))
            val upgrade = workspaceRepository.executeCommand(
                id = workspaceId,
                command = "apt-get -y -o Dpkg::Options::=--force-confold upgrade",
                timeoutMillis = APT_TIMEOUT_MS,
                env = aptEnv(),
            )
            if (upgrade.exitCode != 0) {
                error("apt-get upgrade 失败 (exit ${upgrade.exitCode}): ${upgrade.stderr.ifBlank { upgrade.stdout }.take(800)}")
            }

            val summary = aptSummary(upgrade.stdout)
            Log.i(TAG, "apt upgrade done: $summary")
            onState(InstallState.Done(null))
            summary
        } catch (e: Exception) {
            Log.e(TAG, "upgradeApt failed", e)
            onState(InstallState.Failed(e.message ?: e.toString()))
            throw e
        }
    }

    private fun httpGetText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = METADATA_READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.connect()
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code for $url" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 返回 Rootfs 内启动 claude 的命令 argv；**跑不起来就返回 null**。
     *
     * 2.x 的 npm 包：`bin/claude` 软链 → `bin/claude.exe`，后者装好后是原生二进制，
     * 装到一半则是一个 4KB 的占位 stub —— stub 一跑就是 `spawnSync … ENOENT`。
     * 所以这里先看二进制是不是真的在（[isNativeBinaryReady]），再决定入口。
     */
    fun claudeEntry(linuxDir: File): List<String>? = when {
        !isNativeBinaryReady(linuxDir) -> null
        File(linuxDir, CLAUDE_BIN).isFile -> listOf(CLAUDE_BIN_GUEST)
        File(linuxDir, CLAUDE_WRAPPER).isFile -> listOf(NODE_BIN_GUEST, CLAUDE_WRAPPER_GUEST)
        else -> null
    }

    /** npm 包（wrapper）在。**不代表能跑**，见 [isNativeBinaryReady] */
    private fun isPackageInstalled(linuxDir: File): Boolean =
        File(linuxDir, CLAUDE_PACKAGE_MARKER).isFile ||
            File(linuxDir, CLAUDE_BIN).isFile ||
            File(linuxDir, CLAUDE_WRAPPER).isFile

    /**
     * 原生二进制真的在。判据是 `bin/claude.exe` 的**大小**：postinstall 之前它是一个
     * 不到 4KB 的 stub，之后是 200 MB+ 的二进制；阈值取 1 MB 两边都有余量。
     * 老布局（`cli.js` 在）也算可用。
     */
    private fun isNativeBinaryReady(linuxDir: File): Boolean {
        val native = File(linuxDir, CLI_NATIVE_BIN)
        if (native.isFile && native.length() >= NATIVE_BIN_MIN_BYTES) return true
        return File(linuxDir, CLI_LEGACY_ENTRY).isFile
    }

    /**
     * 预先在 Rootfs 的 `/root/.claude.json` 里落下 `bypassPermissionsModeAccepted: true`。
     *
     * 不这么做的话，`--permission-mode bypassPermissions` 会被**静默降级**成 default：
     * CLI 里的判断是 `if (mode === "bypassPermissions") return !pt() && !config.bypassPermissionsModeAccepted`，
     * 对应提示 "Permission mode downgraded to default — bypass requires accepting the
     * disclaimer interactively first"。手机上没有交互式终端可以点这个免责声明，
     * 所以由 App 代为写入（用户勾选那个"风险自负"复选框就是这里的知情同意）。
     *
     * 已存在但解析不了的配置一律不动，避免把用户/CLI 写的其他字段冲掉。
     */
    fun ensureBypassPermissionsAccepted(linuxDir: File) {
        runCatching {
            val file = File(linuxDir, CLAUDE_CONFIG_REL_PATH)
            val existing = if (file.isFile) {
                runCatching { Json.parseToJsonElement(file.readText()).jsonObject }
                    .getOrElse {
                        Log.w(TAG, "unparsable .claude.json, leaving it alone")
                        return
                    }
            } else {
                null
            }
            if (existing?.get(KEY_BYPASS_ACCEPTED)?.jsonPrimitive?.booleanOrNull == true) return

            file.parentFile?.mkdirs()
            val merged = buildJsonObject {
                existing?.forEach { (key, value) -> if (key != KEY_BYPASS_ACCEPTED) put(key, value) }
                put(KEY_BYPASS_ACCEPTED, true)
            }
            file.writeText(merged.toString())
            Log.i(TAG, "seeded $KEY_BYPASS_ACCEPTED in ${file.absolutePath}")
        }.onFailure { Log.w(TAG, "ensureBypassPermissionsAccepted failed", it) }
    }

    /**
     * 用户级 `/root/.claude/CLAUDE.md` 写入 / 更新短身份卡。
     * 不碰项目 `/workspace/CLAUDE.md`。
     */
    fun ensureRuntimeDocs(linuxDir: File, snapshot: dev.min.code.core.network.NetworkSnapshot? = null) {
        runCatching {
            val file = File(linuxDir, "root/.claude/CLAUDE.md")
            GuestRuntimeDocs.ensureSeeded(file, snapshot)
        }.onFailure { Log.w(TAG, "ensureRuntimeDocs failed", it) }
    }

    private fun writeProfileScript(linuxDir: File) {
        runCatching {
            val dir = File(linuxDir, "etc/profile.d").apply { mkdirs() }
            File(dir, "node.sh").writeText("export PATH=$NODE_BIN_DIR_GUEST:\$PATH\n")
        }.onFailure { Log.w(TAG, "writeProfileScript failed", it) }
    }

    private suspend fun installNode(
        workspaceId: String,
        root: String,
        onState: suspend (InstallState) -> Unit,
    ) {
        val arch = nodeArch()
        val filesDir = File(workspaceDir(root), "files").apply { mkdirs() }
        val tarball = File(filesDir, NODE_ARCHIVE_NAME)
        val expectedSha = NODE_SHA256[arch]
            ?: error("缺少 $arch 的官方 SHA-256 常量，拒绝在未校验的情况下安装")

        requireFreeSpace(filesDir, NODE_REQUIRED_BYTES, "Node.js")
        // 校验和写死在 App 里（取自官方 SHASUMS256.txt）而不是联网取：联网取的话，
        // 同一条被劫持的链路可以把包和校验和一起换掉，校验就成了摆设。
        fetchVerified(
            sources = nodeTarballUrls(arch),
            dest = tarball,
            expected = Digest("SHA-256", expectedSha),
            what = "Node.js",
            onState = onState,
        )

        onState(InstallState.Running("解压 Node.js 到 /opt/node …"))

        // Rootfs 内的 /workspace 即 filesDir
        val result = workspaceRepository.executeCommand(
            id = workspaceId,
            command = """
                set -e
                mkdir -p /opt
                rm -rf /opt/node /opt/node-v*
                tar -xzf /workspace/$NODE_ARCHIVE_NAME -C /opt
                mv /opt/node-v* /opt/node
                rm -f /workspace/$NODE_ARCHIVE_NAME
                /opt/node/bin/node --version
            """.trimIndent(),
            timeoutMillis = EXTRACT_TIMEOUT_MS,
        )
        if (result.exitCode != 0) {
            error("解压 Node.js 失败: ${result.stderr.ifBlank { result.stdout }.take(500)}")
        }
        Log.i(TAG, "node installed: ${result.stdout.trim()}")
    }

    /** 期望的摘要：算法名（MessageDigest 的叫法）+ 小写十六进制 */
    internal data class Digest(val algorithm: String, val hex: String)

    /**
     * 按给定顺序从多个源取同一个文件，每个源都断点续传 + 重试，最后一律按 [expected] 校验。
     *
     * 为什么这么设计：
     * - 国内直连官方源经常是几分钟起步甚至连不上，首次安装因此大量失败；
     * - 但从第三方镜像下二进制是信任转移。校验和另有来源（Node 写死在 App 里，
     *   原生二进制取自官方 registry），镜像就只剩"加速"这一个作用 —— 内容对不上就当没下过，
     *   删档重来。这样既拿到速度，又没有放松可信度。
     */
    private suspend fun fetchVerified(
        sources: List<String>,
        dest: File,
        expected: Digest,
        what: String,
        onState: suspend (InstallState) -> Unit,
    ) {
        // 已经下好且校验通过的包直接复用（上一次装到一半失败时很常见）
        if (dest.isFile && digest(dest, expected.algorithm).equals(expected.hex, ignoreCase = true)) {
            Log.i(TAG, "reusing verified $what at ${dest.name}")
            return
        }

        var lastError: Exception? = null
        sources.forEachIndexed { index, url ->
            val label = if (index == 0) "首选源" else "备用源"
            repeat(DOWNLOAD_ATTEMPTS) { attempt ->
                try {
                    onState(
                        InstallState.Downloading(
                            progress = 0f,
                            detail = "下载${what}（$label${if (attempt > 0) "，第 ${attempt + 1} 次" else ""}）",
                        )
                    )
                    download(url, dest) { progress ->
                        onState(InstallState.Downloading(progress, "下载${what}（$label）"))
                    }
                    onState(InstallState.Running("校验${what}完整性…"))
                    val actual = digest(dest, expected.algorithm)
                    if (actual.equals(expected.hex, ignoreCase = true)) {
                        Log.i(TAG, "$what verified from $url")
                        return
                    }
                    // 校验不过说明拿到的不是官方那个包（镜像同步坏了、被中间人改了、
                    // 或者续传把两段不同的响应拼在了一起）。删档，别拿它继续续传。
                    Log.w(TAG, "${expected.algorithm} mismatch from $url: $actual != ${expected.hex}")
                    dest.delete()
                    lastError = IllegalStateException("$label 下载的${what}校验不通过")
                } catch (e: Exception) {
                    Log.w(TAG, "download attempt failed: $url", e)
                    lastError = e
                    // 网络类失败保留已下部分，下一次尝试从断点接着下
                }
            }
        }
        throw lastError ?: IllegalStateException("$what 下载失败")
    }

    /**
     * 断点续传下载。
     *
     * 服务器认 `Range` 就接着写（206），不认就从头覆盖（200）—— 两种情况都要正确处理，
     * 否则会把新响应追加到旧内容后面，得到一个长度对、内容全错的文件。
     * 这也是上面那个"校验不过就删档"必须存在的原因之一。
     */
    private suspend fun download(url: String, dest: File, onProgress: suspend (Float) -> Unit) {
        val existing = if (dest.isFile) dest.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        try {
            connection.connect()
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code for $url" }
            val resuming = code == HTTP_PARTIAL && existing > 0
            val remaining = connection.contentLengthLong
            val total = if (resuming && remaining > 0) existing + remaining else remaining
            connection.inputStream.use { input ->
                FileOutputStream(dest, resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = if (resuming) existing else 0L
                    var lastReported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            // 每 1% 才回调一次；否则 64KB 一次的进度更新会把 StateFlow 刷爆
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun workspaceDir(root: String): File =
        File(File(context.filesDir, "workspaces"), root)

    private fun linuxDir(root: String): File = File(workspaceDir(root), "linux")

    companion object {
        private const val TAG = "ClaudeCodeInstaller"
        private const val NODE_VERSION = "v22.17.0"
        private const val NODE_ARCHIVE_NAME = ".min-node.tar.gz"
        private const val NATIVE_ARCHIVE_NAME = ".min-claude-native.tgz"
        private const val NPM_TIMEOUT_MS = 600_000L
        private const val EXTRACT_TIMEOUT_MS = 180_000L
        private const val NATIVE_TIMEOUT_MS = 300_000L
        private const val VERSION_TIMEOUT_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val HTTP_PARTIAL = 206
        private const val MB = 1024L * 1024L

        /**
         * `bin/claude.exe` 至少要这么大才算是原生二进制。真正的二进制 200 MB+，
         * postinstall 之前的占位 stub 不到 4 KB，1 MB 两边都留足了余量。
         */
        internal const val NATIVE_BIN_MIN_BYTES = 1L * MB

        /** tarball ~100 MB + 解压 ~215 MB（硬链接不再占空间），再留一点给 npm 缓存 */
        private const val NATIVE_REQUIRED_BYTES = 400L * MB

        /** tarball ~50 MB + 解压 ~120 MB */
        private const val NODE_REQUIRED_BYTES = 250L * MB

        /** 整包 apt 升级比 npm 慢得多，超时给宽一点，否则升到一半被掐会留下半配置状态 */
        private const val APT_TIMEOUT_MS = 900_000L

        /** 查版本号只是一行 JSON，不该等和下载一样久 */
        private const val METADATA_READ_TIMEOUT_MS = 20_000

        /** npm 包名。安装命令和版本检查必须指向同一个包，所以提成常量 */
        private const val CLI_PACKAGE = "@anthropic-ai/claude-code"

        /** 每个源试几次。失败大多是连接被重置，续传下一次就能接着走，不必试太多轮。 */
        private const val DOWNLOAD_ATTEMPTS = 2

        /**
         * 官方 SHASUMS256.txt 里 [NODE_VERSION] 对应的两个包的 SHA-256。
         *
         * **写死而不是联网取**：联网取校验和的话，同一条被劫持的链路可以把包和校验和一起
         * 替换掉，校验就成了摆设。写死之后，无论从官方还是镜像下，比对的都是这个常量。
         *
         * 升级 [NODE_VERSION] 时必须同步更新这里 —— 对不上会直接拒绝安装（这是有意的：
         * 宁可装不上，也不装一个来路不明的 Node 运行时）。
         */
        private val NODE_SHA256 = mapOf(
            "linux-arm64" to "3e99df8b01b27dc8b334a2a30d1cd500442b3b0877d217b308fd61a9ccfc33d4",
            "linux-x64" to "0fa01328a0f3d10800623f7107fbcd654a60ec178fab1ef5b9779e94e0419e1a",
        )

        /** 淘宝 npm 源。仅在用户显式勾选时用于装 claude-code。 */
        private const val NPM_MIRROR_REGISTRY = "https://registry.npmmirror.com"
        private const val NPM_OFFICIAL_REGISTRY = "https://registry.npmjs.org"

        /** 取元数据（版本号、校验和）的顺序：**官方在前**，镜像只做兜底 */
        private val NPM_REGISTRIES = listOf(NPM_OFFICIAL_REGISTRY, NPM_MIRROR_REGISTRY)

        /**
         * 查最新版号的地址，**官方在前**。取的是纯元数据（不落地任何可执行文件），
         * 所以这里的镜像回退和 `useNpmMirror` 那个供应链开关是两码事。
         */
        private val CLI_METADATA_URLS = NPM_REGISTRIES.map { "$it/$CLI_PACKAGE/latest" }

        // Rootfs 内的相对路径（host 侧探测用）
        private const val NODE_BIN = "opt/node/bin/node"
        private const val CLAUDE_BIN = "opt/node/bin/claude"
        private const val CLAUDE_PKG_DIR = "opt/node/lib/node_modules/$CLI_PACKAGE"
        private const val CLAUDE_WRAPPER = "$CLAUDE_PKG_DIR/cli-wrapper.cjs"
        private const val CLAUDE_PACKAGE_MARKER = "$CLAUDE_PKG_DIR/package.json"

        /**
         * 原生二进制落地的位置。名字就叫 `claude.exe`，Linux 上也是 —— 包作者的解释：
         * `.exe` + 无 shebang 的 stub 能让 npm 在 Windows 上生成直接 exec 的 shim，Unix 不看后缀。
         * `bin/claude` 软链指向它。
         */
        private const val CLI_NATIVE_BIN = "$CLAUDE_PKG_DIR/bin/claude.exe"

        /** 2.1 早期的布局：入口是 JS bundle，没有原生二进制 */
        private const val CLI_LEGACY_ENTRY = "$CLAUDE_PKG_DIR/cli.js"

        private const val CLAUDE_CONFIG_REL_PATH = "root/.claude.json"
        private const val OS_RELEASE_REL_PATH = "etc/os-release"
        private const val KEY_BYPASS_ACCEPTED = "bypassPermissionsModeAccepted"

        // wrapper/native 配对状态（SharedPreferences，键后缀是 workspaceId）
        private const val PREFS_NAME = "claudecode.installer"
        private const val KEY_NATIVE_PAIR_DIRTY = "nativePairDirty"
        private const val KEY_NATIVE_PAIRED_VERSION = "nativePairedVersion"

        // Rootfs 内的绝对路径（传给 CLI 用）
        private const val NODE_BIN_DIR_GUEST = "/opt/node/bin"
        private const val NODE_BIN_GUEST = "/opt/node/bin/node"
        private const val NPM_BIN_GUEST = "/opt/node/bin/npm"
        private const val CLAUDE_BIN_GUEST = "/opt/node/bin/claude"
        private const val CLAUDE_WRAPPER_GUEST = "/$CLAUDE_WRAPPER"

        fun nodeEnv(): Map<String, String> = mapOf(
            "PATH" to "$NODE_BIN_DIR_GUEST:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )

        /**
         * apt 不需要 node，但**必须**有 `DEBIAN_FRONTEND=noninteractive`：
         * proot 里没有交互终端，debconf 一弹问题就没人能回答，整个升级挂到超时为止。
         */
        private fun aptEnv(): Map<String, String> = mapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DEBIAN_FRONTEND" to "noninteractive",
        )

        /** 主 ABI 决定，见 [DeviceArch]：x86_64 模拟器会把 arm64 也列进 SUPPORTED_ABIS，不能按"有没有"判 */
        private fun nodeArch(): String = DeviceArch.nodeArch

        /**
         * 本机对应的平台二进制包。Rootfs 是 Ubuntu（glibc），所以不带 `-musl` 后缀；
         * wrapper 自己的判断（cli-wrapper.cjs 的 detectMusl）在这个环境里也是同一个结论。
         */
        private fun nativePackageName(): String = "$CLI_PACKAGE-${nodeArch()}"

        /**
         * `bin/claude.exe` 是不是一份真正的原生二进制。不管遗留的 `cli.js`——
         * 那只说明「还能用 JS 入口凑合跑」，不能说明平台包已经跟上 wrapper。
         */
        internal fun nativeExecutablePresent(linuxDir: File): Boolean {
            val native = File(linuxDir, CLI_NATIVE_BIN)
            return native.isFile && native.length() >= NATIVE_BIN_MIN_BYTES
        }

        /**
         * 更新 CLI 之后要不要重下原生二进制。
         *
         * - 没有可执行的原生文件，且 wrapper 声明了平台包 → 下
         * - 声明版本变了（拷贝不会跟着 npm 变）→ 下
         * - 其余（已是同一版的完整拷贝，或仍是没有平台包的老布局）→ 不下
         */
        internal fun shouldRefreshNativeBinary(
            executablePresent: Boolean,
            declaredVersionBefore: String?,
            declaredVersionAfter: String?,
        ): Boolean {
            if (!executablePresent) return declaredVersionAfter != null
            return declaredVersionBefore != declaredVersionAfter
        }

        /**
         * 候选下载地址，**官方在前**。镜像只在官方拿不到时兜底，且下到的内容一律用
         * [NODE_SHA256] 校验，所以镜像的作用仅限于"更快到手"，不改变信任来源。
         */
        internal fun nodeTarballUrls(arch: String): List<String> {
            val file = "node-$NODE_VERSION-$arch.tar.gz"
            return listOf(
                "https://nodejs.org/dist/$NODE_VERSION/$file",
                "https://cdn.npmmirror.com/binaries/node/$NODE_VERSION/$file",
            )
        }

        /**
         * 平台二进制包 tarball 的候选地址，按 npm 的固定格式拼：
         * `<registry>/<scope>/<name>/-/<name>-<version>.tgz`。
         * 用户勾了镜像就镜像在前（那是"我要快"的明确表态），否则官方在前。
         * 无论哪个源，内容都按官方 registry 给的 sha512 校验。
         */
        internal fun nativeTarballUrls(pkg: String, version: String, mirrorFirst: Boolean): List<String> {
            val bare = pkg.substringAfterLast('/')
            val path = "$pkg/-/$bare-$version.tgz"
            val registries = if (mirrorFirst) NPM_REGISTRIES.asReversed() else NPM_REGISTRIES
            return registries.map { "$it/$path" }
        }

        /** wrapper 的 package.json → optionalDependencies 里 [pkg] 声明的版本 */
        internal fun parseDeclaredNativeVersion(packageJson: String, pkg: String): String? = runCatching {
            Json.parseToJsonElement(packageJson).jsonObject["optionalDependencies"]
                ?.jsonObject?.get(pkg)?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        /**
         * npm registry 版本元数据里的 `dist.integrity`（SRI 格式 `sha512-<base64>`）→ [Digest]。
         * 只认 sha512；老包只有 `dist.shasum`（sha1）的话退回它 —— 有校验总比没有强。
         */
        internal fun parseIntegrity(json: String): Digest? = runCatching {
            val dist = Json.parseToJsonElement(json).jsonObject["dist"]?.jsonObject ?: return@runCatching null
            dist["integrity"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("sha512-") }
                ?.let { sri ->
                    // java.util.Base64 而不是 android.util.Base64：minSdk 26 已有，且 JVM 单测里能跑
                    Digest("SHA-512", java.util.Base64.getDecoder().decode(sri.removePrefix("sha512-")).toHex())
                }
                ?: dist["shasum"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.length == 40 }
                    ?.let { Digest("SHA-1", it.lowercase()) }
        }.getOrNull()

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        /** `PRETTY_NAME="Ubuntu 24.04.3 LTS"` → `Ubuntu 24.04.3 LTS` */
        internal fun parseOsPrettyName(content: String): String? = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

        /** npm registry 的 `/<pkg>/latest` 响应里那个 `version` 字段 */
        internal fun parseVersionField(json: String): String? = runCatching {
            Json.parseToJsonElement(json).jsonObject["version"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        /** apt 自己那句 "N upgraded, N newly installed…"；找不到就退回输出末尾几行 */
        internal fun aptSummary(stdout: String): String {
            val lines = stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
            return lines.lastOrNull { it.contains("upgraded") && it.contains("newly installed") }
                ?: lines.takeLast(3).joinToString("\n").ifBlank { "已完成" }
        }

        /**
         * 有没有更新。**必须按数字段比，不能比字符串**：`"2.1.9" > "2.1.10"` 在字典序下成立，
         * 会把新版判成旧版，用户永远看到"已是最新"。
         */
        internal fun isNewerVersion(installed: String?, latest: String?): Boolean {
            if (latest.isNullOrBlank()) return false
            if (installed.isNullOrBlank()) return true
            return compareVersions(latest, installed) > 0
        }

        internal fun compareVersions(a: String, b: String): Int {
            val pa = a.versionParts()
            val pb = b.versionParts()
            repeat(maxOf(pa.size, pb.size)) { i ->
                val diff = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
                if (diff != 0) return diff
            }
            return 0
        }

        /** `2.1.258 (Claude Code)` / `2.1.0-beta.3` 都归一成 `[2, 1, 258]` / `[2, 1, 0]` */
        private fun String.versionParts(): List<Int> = trim()
            .substringBefore(' ')
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
