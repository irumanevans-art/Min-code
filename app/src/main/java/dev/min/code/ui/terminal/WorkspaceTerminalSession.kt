package dev.min.code.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.network.activeDnsServers
import dev.min.code.util.LocalPreviewBus
import dev.min.code.util.LocalUrls
import dev.min.code.util.openExternalUrl
import me.rerere.workspace.ProotShellEntry
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsPatchOptions
import me.rerere.workspace.RootfsPatcher
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellContext
import java.io.File

/**
 * 终端页签那条 proot 的参数。
 *
 * 命令行本身由 [ProotShellRunner.buildCommand] 拼 —— 和 Claude 会话、长驻本地服务同一份来源。
 * 这里只负责说清楚终端**故意**跟它们不一样的地方：
 * - [ProotShellEntry.InteractiveShell]：接在 pty 上的交互 shell，没有要 eval 的命令，
 *   也因此不吃 `CI` / `NO_COLOR` / `PAGER=cat` 那套非交互约定（用户在看，颜色和分页要留着）。
 * - `killOnExit = true`：关掉页签就该把里面跑的东西一起带走，和长驻服务正相反。
 * - 显式注入 Node 的 PATH：交互 shell 不是登录 shell，读不到
 *   `/etc/profile.d/node.sh`，不给的话终端里 `claude` / `node` / `npm` 全是 command not found，
 *   而同一个 rootfs 在别处都有。
 *
 * @param cwd 开进哪个目录（guest 侧绝对路径，如 `/workspace/api`）。给会话开的终端用它落在
 *   那个会话自己的工作目录里。**必须先验证存在**：proot 的 `-w` 指到一个不存在的目录时
 *   shell 会莫名其妙地起在别处，不如当场退回 `/workspace`。
 * @param credentials 当前供应商的环境变量（`shellCredentialEnv`）。空 = 不注入。
 *
 *   在这之前这里**一个凭据都没有**：同一个 App 里，会话页能用的 token，换到终端页敲
 *   `claude` 就是「未配置」。Node 的 PATH 当初补上了、凭据却没有，于是终端里那个
 *   `claude` 看着装好了、跑起来却连不上。
 */
internal fun buildTerminalShellContext(
    root: String,
    filesDir: File,
    linuxDir: File,
    tempDir: File,
    cwd: String? = null,
    credentials: Map<String, String> = emptyMap(),
): WorkspaceShellContext {
    val workspaceDirGuest = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    // guest `/workspace/x` 就是宿主 `files/x`（runner 拼的那条 bind mount），所以存在性直接在宿主侧问
    val relativeCwd = cwd
        ?.takeIf { it == workspaceDirGuest || it.startsWith("$workspaceDirGuest/") }
        ?.removePrefix(workspaceDirGuest)
        ?.trim('/')
        ?.takeIf { it.isEmpty() || File(filesDir, it).isDirectory }
        .orEmpty()

    return WorkspaceShellContext(
        root = root,
        command = "", // 交互 shell 不 eval 任何东西
        cwd = relativeCwd,
        filesDir = filesDir,
        linuxDir = linuxDir,
        tempDir = tempDir,
        workingDir = filesDir,
        timeoutMillis = 0L, // 终端由用户关，不超时
        // 凭据排在 PATH 之后：RESERVED_ENV_KEYS 已经把 PATH 挡在 credentials 之外，
        // 这里的顺序只是让「谁是基础、谁是这次带进来的」一眼可读
        env = ClaudeCodeInstaller.nodeEnv() + credentials,
        killOnExit = true,
        entry = ProotShellEntry.InteractiveShell,
    )
}

internal fun createWorkspaceTerminalSession(
    context: Context,
    root: String,
    client: TerminalSessionClient,
    cwd: String? = null,
    credentials: Map<String, String> = emptyMap(),
): TerminalSession {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val runner = ProotShellRunner(File(appContext.applicationInfo.nativeLibraryDir))
    val shellContext = buildTerminalShellContext(
        root = root,
        filesDir = File(workspaceDir, "files"),
        linuxDir = File(workspaceDir, "linux"),
        tempDir = File(workspaceDir, "tmp"),
        cwd = cwd,
        credentials = credentials,
    )

    // TerminalSession 自己拼 argv[0]，所以第一项（proot 本身）要摘掉
    val command = runner.buildCommand(shellContext)
    val env = runner.hostEnvironment(shellContext).map { (key, value) -> "$key=$value" }

    return TerminalSession(
        command.first(),
        shellContext.filesDir.absolutePath,
        command.drop(1).toTypedArray(),
        env.toTypedArray(),
        2_000,
        client,
    ).apply {
        mSessionName = root
    }
}

internal fun prepareWorkspaceTerminalSession(context: Context, root: String) {
    val appContext = context.applicationContext
    val workspaceDir = File(File(appContext.filesDir, "workspaces"), root)
    val linuxDir = File(workspaceDir, "linux")
    File(workspaceDir, "files").mkdirs()
    File(workspaceDir, "tmp").mkdirs()
    RootfsPatcher().patch(
        linuxDir,
        RootfsPatchOptions(nameservers = appContext.activeDnsServers())
    )
}

internal fun workspaceRootfsReady(context: Context, root: String): Boolean {
    val linuxDir = File(File(File(context.applicationContext.filesDir, "workspaces"), root), "linux")
    return linuxDir.isDirectory && File(linuxDir, "bin/sh").isFile
}

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    /**
     * 检测点击位置是否落在一个 URL 上, 是则用浏览器打开并返回 true.
     * TerminalView 0.118.0 没有内置链接点击, 这里基于 getColumnAndRow() + 屏幕缓冲文本自行实现,
     * 并通过 getLineWrap() 还原被软换行拆开的长 URL.
     */
    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val rows = emulator.mRows
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = rows - 1
        if (column < 0 || column >= columns) return false
        if (row < minAccessibleRow || row > maxAccessibleRow) return false

        // 向上/向下扩展到完整逻辑行(被软换行拆开的行 mLineWrap 为 true).
        // 限制最多扩展 URL_MAX_WRAP_ROWS 行: 真实 URL 跨不了这么多行, 同时避免连续无换行的
        // 长输出导致单次点击遍历整个 transcript.
        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (r in startRow..endRow) {
            if (r == row) {
                // 用 [0, column] 这段文本的长度精确换算点击字符在本行内的下标, 避免宽字符错位
                tapIndex = line.length + (screen.getSelectedText(0, r, column, r).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, r, columns - 1, r))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return runCatching {
            if (LocalUrls.isLoopbackHttp(url)) {
                LocalPreviewBus.offer(url)
                return@runCatching true
            }
            context.openExternalUrl(url)
        }.getOrElse {
            Log.w("WorkspaceTerminal", "Failed to open url: $url", it)
            false
        }
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}


// 一个 URL 最多还原跨越的软换行行数(向上/向下各算), 足够覆盖任意真实 URL
private const val URL_MAX_WRAP_ROWS = 50

private val URL_REGEX =
    Regex("""(https?|ftp)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""", RegexOption.IGNORE_CASE)

// 终端里 URL 后面常跟标点(行尾句号、被括号包裹等), 打开前去掉这些结尾字符
private val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

