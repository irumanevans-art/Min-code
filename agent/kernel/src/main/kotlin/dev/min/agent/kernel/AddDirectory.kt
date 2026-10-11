package dev.min.agent.kernel

import dev.min.agent.ext.hooks.DirectoryAddedInput
import dev.min.agent.ext.hooks.HookEvent
import dev.min.agent.ext.hooks.HookJson
import dev.min.agent.policy.normalizePolicyPath
import dev.min.agent.spi.DirectoryAdded
import dev.min.agent.spi.Durability
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.HookContextAdded
import dev.min.agent.spi.HookContextTarget
import dev.min.agent.spi.PathScope
import dev.min.agent.spi.catchingNonCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/** `/add-dir` 的来源（也是 DirectoryAdded hook 的 matcher 与输入 `source`） */
const val DIRECTORY_SOURCE_SLASH_COMMAND = "slash_command"
const val DIRECTORY_SOURCE_REGISTER_REPO_ROOT = "register_repo_root"

/** 会话中途加目录的结果 */
sealed interface AddDirectoryResult {
    /**
     * 加上了。[hooks] 是 DirectoryAdded hooks 的后台任务（原文：加目录立即完成、不等 hook）；没有要跑的 hook 时是 null。
     * 调用方一般不用管它，测试与要在退出前等一等的宿主可以 join
     */
    data class Added(val directory: GuestPath, val hooks: Job?) : AddDirectoryResult

    /** 已经是工作目录、或在某个工作目录里面：什么都没做，不写事件、不触发 hook（原文） */
    data class AlreadyIncluded(val directory: GuestPath, val by: GuestPath) : AddDirectoryResult

    /** 没加：[reason] 给人看 */
    data class Refused(val reason: String) : AddDirectoryResult
}

/**
 * 用户输入里写的路径文字是不是网络路径（UNC `\server\share`，或 `//server/share`）。原文（permissions 页、cli-reference）：
 * 「You can't add most network paths ... because looking one up can contact the host it names」——连 stat 都不能做，所以在碰文件系统**之前**挡。
 * 宿主把用户的原话转成 guest 路径之前就要过这一关（guest 路径装不下 UNC，转完就看不出来了）
 */
fun isNetworkPathText(raw: String): Boolean = raw.trimStart().let { it.startsWith("\\\\") || it.startsWith("//") }

/**
 * 会话中途加一个工作目录（CC `/add-dir`；SDK 的 `register_repo_root` 走 [source] = register_repo_root）。只有宿主（用户命令）能调，没有对应的模型工具——
 * 模型自己不能给自己扩大文件访问的范围。
 *
 * 权限口径（permissions 页「Working directories」「Additional directories grant file access, not configuration」）：
 * - 加进来的目录与 `--add-dir` 同档：读不再问，写按当前权限模式——折进 [SessionState.additionalDirectories]，权限引擎已经按它判；
 *   手机文件底线（[PathScope.Device]）不被它覆盖，所以这种目录直接拒绝，免得让人以为加上了；
 * - 只授予文件访问：目录里的 settings / hooks / MCP 配置**不**生效，也不因此信任那个目录；
 * - 只在本会话有效，**不写任何 settings 文件**（跨会话保留要用户自己写 `permissions.additionalDirectories`）；日志里的 [DirectoryAdded] 让重开会话恢复；
 * - 必须是存在的目录；不能是网络路径（[isNetworkPathText]，宿主在转换前检查）；已经是工作目录或在其中的不重复加、不触发 hook；
 * - 回合进行中也能加（原文：确认后「Claude's next tool call in the same turn can access it」）：折叠一提交，下一次权限判定就读到新目录。
 *
 * 触发 DirectoryAdded hooks：加完**之后**（权限已刷新）、在后台跑，不拦加目录；它没有决策控制。
 * `slash_command` 的 hook `systemMessage` 作为上下文交给模型（下一条输入），不显示给人；`register_repo_root` 的只进日志。
 */
suspend fun SessionRuntime.addDirectory(directory: GuestPath, source: String = DIRECTORY_SOURCE_SLASH_COMMAND): AddDirectoryResult {
    require(source == DIRECTORY_SOURCE_SLASH_COMMAND || source == DIRECTORY_SOURCE_REGISTER_REPO_ROOT) { "未知的加目录来源：$source" }
    if (state.value.origin != null) return AddDirectoryResult.Refused("子 agent 会话不能加工作目录。")
    val normalized = GuestPath(normalizePolicyPath(directory.value))
    val fs = deps.host.fs
    val stat = catchingNonCancellation { fs.stat(normalized) }.getOrNull()
    if (stat == null) return AddDirectoryResult.Refused("目录不存在或读不了：$normalized")
    if (stat.kind != FileKind.Directory) return AddDirectoryResult.Refused("不是目录：$normalized")
    if (fs.scopeOf(normalized) == PathScope.Device) return AddDirectoryResult.Refused("$normalized 在手机文件区，附加目录不能覆盖手机文件底线，没有加。")
    val real = deps.canonicalPath(normalized)
    val roots = listOf(state.value.settings.cwd) + state.value.additionalDirectories
    roots.firstOrNull { Worktrees.under(real, deps.canonicalPath(it)) }?.let { return AddDirectoryResult.AlreadyIncluded(normalized, it) }
    // 并发的两次 /add-dir 同一个目录：条件与写入同在一把锁里，只有一次真的加上
    val written = commitIf(listOf(DirectoryAdded(normalized, source)), Durability.Sync) { s ->
        s.additionalDirectories.none { Worktrees.under(real, deps.canonicalPath(it)) } && !Worktrees.under(real, deps.canonicalPath(s.settings.cwd))
    }
    if (written == null) return AddDirectoryResult.AlreadyIncluded(normalized, roots.first())
    val job = if (HookDriver(this).has(HookEvent.DirectoryAdded, source)) hostScope.launch { runDirectoryAddedHooks(normalized, source) } else null
    return AddDirectoryResult.Added(normalized, job)
}

/** DirectoryAdded hooks（后台）：输入 `directory`（hook 的 shell 看得见的绝对路径）与 `source`，matcher 对 source；超时默认 600 秒 */
private suspend fun SessionRuntime.runDirectoryAddedHooks(directory: GuestPath, source: String) {
    val specific = HookJson.encodeToJsonElement(DirectoryAddedInput.serializer(), DirectoryAddedInput(deps.hookShellPath(directory), source)).jsonObject
    // systemMessage 不当成「给人看的提示」：slash_command 交给模型、register_repo_root 只进日志（HooksRan 的记录）
    val outcome = HookDriver(this).run(HookEvent.DirectoryAdded, source, specific, notifyUser = false) ?: return
    if (source == DIRECTORY_SOURCE_SLASH_COMMAND && outcome.systemMessages.isNotEmpty()) {
        commit(outcome.systemMessages.map { HookContextAdded(HookContextTarget.NextUserMessage, HookEvent.DirectoryAdded.name, it) }, Durability.Sync)
    }
}
