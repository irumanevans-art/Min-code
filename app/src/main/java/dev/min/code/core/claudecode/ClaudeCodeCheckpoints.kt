package dev.min.code.core.claudecode

import android.util.Log
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.editTargetPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 会话级的文件检查点：什么时候存快照、怎么撤销、撤销之后对话流里说什么。
 *
 * 从 [ClaudeCodeManager] 里整段搬出来的。落盘格式和还原顺序在 [ClaudeCodeCheckpointStore]，这里管的是
 * 它和**会话**的接缝：快照要在 `tool_use` 帧到达的那一刻（工具还没跑）拍，撤销要往聊天流里补说明。
 * 依赖都收窄成调用方真用到的几样（工作区目录、会话状态的只读视图、往聊天流补一条提示），
 * 所以不认识 Manager、也不碰 Context，可以直接拿临时目录测。
 *
 * **只还原文件，不动对话**——理由见 [revertToolCall]。
 */
internal class ClaudeCodeCheckpoints(
    /** 快照是异步拍的，跑在这个作用域上（Manager 的作用域，和 Manager 同寿命） */
    private val scope: CoroutineScope,
    /** 工作区目录（其下 `files/` 是 guest 的 `/workspace`、`linux/` 是 rootfs）；工作区还没建出来时为 null */
    private val workspaceDir: suspend () -> File?,
    /** 当前 rootfs 的 `linux/` 目录，launchCli 之后才有值；只用来在 dispatch 线程同步取样 */
    private val linuxDir: () -> File?,
    /** 只读：取 `sessionId` 和对话流里的工具卡（撤销提示里要写文件名） */
    private val state: StateFlow<ClaudeCodeManager.SessionState>,
    /** 正在回放历史 transcript：回放里的工具早跑完了，不能再拍快照 */
    private val isReplaying: () -> Boolean,
    /** 往聊天流补一条提示，让用户和模型都知道文件被改回去了 */
    private val appendNote: (text: String, isError: Boolean) -> Unit,
) {

    /**
     * 撤销一次编辑类工具调用造成的文件改动。
     *
     * **只还原文件，不动对话**。对话状态在 CLI 进程内部，无头模式下没有对应的控制请求
     * 可以驱动它；把只能还原文件的操作说成"回到这一步"会让用户误以为模型也忘了这件事。
     * 所以还原之后往聊天流里补一条说明，让模型和用户都知道文件被改回去了。
     */
    suspend fun revertToolCall(toolUseId: String): Boolean = revertFile(
        name = state.value.items.filterIsInstance<ChatItem.ToolCall>()
            .firstOrNull { it.toolUseId == toolUseId }
            ?.input?.let(::editTargetPath)
            ?.substringAfterLast('/')
            ?: "文件",
        toolUseIds = listOf(toolUseId),
        whole = false,
    ).isNotEmpty()

    /**
     * 把一个文件在这个会话里的改动**整批**撤回去：按 [toolUseIds] 从最后一次往前逐个还原，
     * 最终落到第一次改动之前（`restoreAll` 自己会逆序，这里按会话顺序传）。
     *
     * 语义与单条撤销一致 —— **只还原文件，不动对话**；所以同样往聊天流里补一条说明，
     * 让模型和用户都知道文件被改回去了。快照缺失的调用会被跳过，还原条数如实回报。
     *
     * @return **真的还原成功**的 toolUseId。界面只把这些标成「已撤销」，失败的不标
     *   —— 先标后还原的话，失败时面板会说撤销了而文件其实没动。
     */
    suspend fun revertFile(name: String, toolUseIds: List<String>, whole: Boolean = true): Set<String> {
        if (toolUseIds.isEmpty()) return emptySet()
        val sessionId = state.value.sessionId ?: return emptySet()
        val workspaceDir = workspaceDir()
        if (workspaceDir == null) {
            appendNote("撤销失败：找不到工作区", true)
            return emptySet()
        }
        val store = storeIn(workspaceDir)
        val restored = withContext(Dispatchers.IO) {
            store.restoreEach(sessionId, toolUseIds) { guestPath -> guestToHostFile(workspaceDir, guestPath) }
        }
        val missing = toolUseIds.size - restored.size
        when {
            restored.isEmpty() -> appendNote("撤销失败：找不到 $name 的快照", true)
            !whole -> appendNote("已把 $name 还原到这次修改之前（仅文件，对话历史不变）", false)
            else -> {
                val suffix = if (missing > 0) "（其中 $missing 次没有快照，未还原）" else ""
                appendNote("已把 $name 还原到这个会话开始前（${restored.size} 次改动，仅文件，对话历史不变）$suffix", false)
            }
        }
        return restored
    }

    /**
     * [toolUseIds] 里哪些存过快照 —— 「改动过的文件」面板靠它决定哪些改动给撤销按钮。
     * 子 agent 里的编辑不走 [snapshotBeforeEdit]（它的 tool_use 被包成 `ClaudeCodeEvent.Subagent`），
     * 永远不会在结果里，面板如实说撤不了。
     */
    suspend fun checkpointed(toolUseIds: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        val sessionId = state.value.sessionId ?: return@withContext emptySet()
        val workspaceDir = workspaceDir() ?: return@withContext emptySet()
        val store = storeIn(workspaceDir)
        toolUseIds.filterTo(LinkedHashSet()) { store.hasSnapshot(sessionId, it) }
    }

    /**
     * 编辑类工具执行前存一份原文件。
     *
     * 在 `tool_use` 帧到达时调用 —— 那时工具还没跑（`tool_result` 才是跑完），
     * 拿到的就是修改前的内容。失败只记日志：快照是锦上添花，绝不能让它挡住工具执行。
     */
    fun snapshotBeforeEdit(event: ClaudeCodeEvent.ToolUse) {
        // 回放只是重画卡片（见 openSession），历史里的工具早跑完了，
        // 再存快照只会把"现在的文件"当成"修改前"盖住真快照 —— 靠 sessionId 为 null
        // 早退是侥幸，这里和预览扫描 / bypass 托管一样显式拦掉
        if (isReplaying()) return
        if (event.name !in CHECKPOINT_TOOLS) return
        val guestPath = editTargetPath(event.input) ?: return
        val sessionId = state.value.sessionId ?: return
        // 快照竞态：异步协程排队期间，bypass 下的快工具可能已经改完文件，存进去的
        // 就成了修改后的内容，撤销等于空操作。先在 dispatch 线程同步取样（existence +
        // mtime，两次 stat，微秒级）；写快照前再比一次，变了就说明工具已动手，这份
        // "修改前"已经拿不回来了 —— 跳过，让用户看不到撤销钮，也好过撤销个空气。
        // launchCli 之后 linuxDir 必有值；取样不到就退回原来的异步盲拍。
        val probe = linuxDir()?.parentFile?.let { guestToHostFile(it, guestPath) }
        val existedAtDispatch = probe?.isFile == true
        val mtimeAtDispatch = if (existedAtDispatch) probe.lastModified() else null
        scope.launch(Dispatchers.IO) {
            val workspaceDir = workspaceDir() ?: return@launch
            val host = guestToHostFile(workspaceDir, guestPath)
            if (probe != null) {
                val existsNow = host?.isFile == true
                if (existsNow != existedAtDispatch ||
                    (existsNow && host.lastModified() != mtimeAtDispatch)
                ) {
                    Log.w(TAG, "skip stale snapshot for ${event.id}: $guestPath changed before snapshot")
                    return@launch
                }
            }
            storeIn(workspaceDir).snapshot(sessionId, event.id, guestPath, host)
        }
    }

    /**
     * 快照跟着会话走：会话删了不清的话，文件副本会一直堆在数据目录里，
     * 而且它们已经没有任何入口可以撤销了。
     */
    suspend fun clear(sessionId: String) {
        workspaceDir()?.let { storeIn(it).clear(sessionId) }
    }

    private fun storeIn(workspaceDir: File) = ClaudeCodeCheckpointStore(File(workspaceDir, CHECKPOINT_DIR))

    private companion object {
        // 和 Manager 共用一个 TAG：排查时 `logcat -s ClaudeCodeManager` 一条命令就能看全
        const val TAG = "ClaudeCodeManager"

        /** 检查点目录（工作区目录下）。点号开头，免得在文件浏览器里碍眼。 */
        const val CHECKPOINT_DIR = ".min-checkpoints"
    }
}
