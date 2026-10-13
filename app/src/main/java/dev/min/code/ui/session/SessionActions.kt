package dev.min.code.ui.session

import android.net.Uri
import dev.min.code.core.claudecode.ComposerDraft
import dev.min.code.core.engine.ImageInput
import dev.min.code.core.engine.ResumeOffer
import dev.min.code.core.engine.TaskOutputTail
import java.io.InputStream

/**
 * 会话内容页（[SessionContent]）向 ViewModel 要做的事，收成一个对象。
 *
 * 之前页面里到处是 `vm.xxx` / `toolsVm.xxx`，读代码的人分不清哪些是「读状态」、哪些是「下命令」，
 * 想换个替身也无处下手。现在**读状态**（`vm.session`、`vm.capabilities` 这些 Flow）仍留在页面里 ——
 * 由谁在哪一层收集决定重组范围，不是这里该动的；**下命令**统一经这里，由 [ClaudeCodePage]
 * 在顶层按 ViewModel 建一份、`remember` 住，之后只往下传。
 *
 * 只收页面真用到的；要加新动作，在这里加一行并在 [from] 里接上，编译器会指出所有没接的地方。
 */
internal class SessionActions(
    /** 设置面板（Claude / Codex）要用的那一组，原样交给输入坞 */
    val settings: SessionSettingsActions,
    val send: (String, List<ImageInput>) -> Unit,
    /** (threadId, 话, 图片)：子 agent 视图里发的话 */
    val sendToSubagent: (String, String, List<ImageInput>) -> Unit,
    val interrupt: () -> Unit,
    val stopSubagent: (String) -> Unit,
    val stop: () -> Unit,
    val resume: (ResumeOffer) -> Unit,
    val runShell: (String) -> Unit,
    val newSession: () -> Unit,
    val runInit: () -> Unit,
    val refreshModels: () -> Unit,
    val refreshUsage: () -> Unit,
    /** (toolUseId, 还原成功后的回调)：只动文件、不动对话历史 */
    val revertToolCall: (String, () -> Unit) -> Unit,
    /** (文件名, 该文件的全部 toolUseId, 真的还原成功的那几个的回调) */
    val revertFile: (String, List<String>, (Set<String>) -> Unit) -> Unit,
    val checkpointed: suspend (Collection<String>) -> Set<String>,
    val withdrawQueued: (String) -> Unit,
    val searchFiles: suspend (String) -> List<String>,
    /** (会话 key, 草稿)：杀进程再进同一会话时恢复 */
    val saveComposerDraft: (String?, ComposerDraft) -> Unit,
    val snapshotComposerOnStop: (String?, ComposerDraft) -> Unit,
    val readTaskOutput: suspend (String) -> TaskOutputTail?,
    val stopTask: suspend (String) -> String?,
    val togglePreview: () -> Unit,
    val importFile: (fileName: String, stream: InputStream, onDone: (String?) -> Unit) -> Unit,
    val deleteWorkspaceFile: (String) -> Unit,
    val loadImage: suspend (Uri) -> ImageInput?,
) {
    companion object {
        /**
         * 接上真的 ViewModel。[onOpenCodexConnection]：Codex 的连接方式面板住在页面顶层，
         * 设置面板里的入口要回去开它。
         */
        fun from(vm: SessionVM, toolsVm: WorkspaceToolsVM, onOpenCodexConnection: () -> Unit) = SessionActions(
            settings = SessionSettingsActions(
                onSetModel = { model, asDefault -> vm.setModel(model, asDefault) },
                // 面板里选模型只改这个会话，不写「新会话默认」：Min 新会话默认用最近一次的档与模型
                onSetModelOf = { model, profile -> vm.setModel(model, asDefault = false, profileId = profile) },
                onSetPermissionMode = { vm.setPermissionMode(it) },
                onApplyEffort = { effort, ultracode -> vm.applyEffort(effort, ultracode) },
                onSetPromptCacheTtl = { vm.setPromptCacheTtl(it) },
                onSetEngineOption = { key, value -> vm.setEngineOption(key, value) },
                onOpenConnection = onOpenCodexConnection,
                onSetChineseDescriptions = { vm.setChineseDescriptions(it) },
                onSetCwd = { vm.setCwd(it) },
                onAddDirectory = { vm.addDirectory(it) },
                onListCwd = { vm.listCwdFolders(it) },
                onCreateCwd = { parent, name, onDone -> vm.createCwdFolder(parent, name, onDone) },
            ),
            send = { text, images -> vm.send(text, images) },
            sendToSubagent = { threadId, text, images -> vm.sendToSubagent(threadId, text, images) },
            interrupt = { vm.interrupt() },
            stopSubagent = { vm.stopSubagent(it) },
            stop = { vm.stop() },
            resume = { vm.resume(it) },
            runShell = { vm.runShell(it) },
            newSession = { vm.newSession() },
            runInit = { vm.runInit() },
            refreshModels = { vm.refreshModels() },
            refreshUsage = { vm.refreshUsage() },
            revertToolCall = { toolUseId, onRestored -> vm.revertToolCall(toolUseId, onRestored) },
            revertFile = { name, ids, onRestored -> vm.revertFile(name, ids, onRestored) },
            checkpointed = { vm.checkpointed(it) },
            withdrawQueued = { vm.withdrawQueued(it) },
            searchFiles = { vm.searchFiles(it) },
            saveComposerDraft = { key, draft -> vm.saveComposerDraft(key, draft) },
            snapshotComposerOnStop = { key, draft -> vm.snapshotComposerOnStop(key, draft) },
            readTaskOutput = { vm.readTaskOutput(it) },
            stopTask = { vm.stopTask(it) },
            togglePreview = { toolsVm.togglePreview() },
            importFile = { name, stream, onDone -> toolsVm.importFile(name, stream, onDone) },
            deleteWorkspaceFile = { toolsVm.deleteWorkspaceFile(it) },
            loadImage = { toolsVm.loadImage(it) },
        )
    }
}
