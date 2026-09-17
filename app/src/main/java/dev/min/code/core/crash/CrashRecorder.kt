package dev.min.code.core.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 异常落盘，下次启动时展示：致命崩溃与被协程 handler 接住的非致命异常
 * 分两份文件（last_crash.txt / last_error.txt），见 [Severity]。
 *
 * 这个 App 没有联网上报，唯一的排错手段就是用户把堆栈复制出来发给人看 ——
 * 所以崩溃页必须能"复制"，而且要保留到用户主动清掉为止（只看一眼就没了等于没有）。
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val FILE_NAME = "last_crash.txt"
    private const val FILE_NAME_NON_FATAL = "last_error.txt"

    /**
     * [FATAL]：未捕获异常，App 崩掉。[NON_FATAL]：被协程的 CoroutineExceptionHandler
     * 接住、App 还在跑的那种。两者分开落盘 —— 启动横幅（「上次运行崩溃过」）只认
     * FATAL 的文件，否则一个被接住的异常也会竖起假崩溃横幅，还会覆盖掉用户
     * 没来得及看的真崩溃堆栈。
     */
    enum class Severity { FATAL, NON_FATAL }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(app, thread, throwable, Severity.FATAL)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 落盘一份异常堆栈，格式两种严重程度一致，关于页都能看到。
     * 默认 [Severity.NON_FATAL]：直接调 record 的（协程 handler 等）都是 App 没崩的场景。
     */
    fun record(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        severity: Severity = Severity.NON_FATAL,
    ) {
        runCatching {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            file(context.applicationContext, severity).writeText(
                buildString {
                    appendLine("Thread: ${thread.name}")
                    appendLine("Time: ${java.util.Date()}")
                    appendLine()
                    append(sw.toString())
                }
            )
        }.onFailure { Log.e(TAG, "failed to record crash", it) }
    }

    /** 致命崩溃（last_crash.txt）。启动横幅只认这份 */
    fun read(context: Context): String? = readFile(context, Severity.FATAL)

    /** 被接住的非致命异常（last_error.txt），关于页单独展示 */
    fun readNonFatal(context: Context): String? = readFile(context, Severity.NON_FATAL)

    private fun readFile(context: Context, severity: Severity): String? = runCatching {
        file(context, severity).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context, Severity.FATAL).delete() }
    }

    fun clearNonFatal(context: Context) {
        runCatching { file(context, Severity.NON_FATAL).delete() }
    }

    private fun file(context: Context, severity: Severity) = File(
        context.filesDir,
        if (severity == Severity.FATAL) FILE_NAME else FILE_NAME_NON_FATAL,
    )
}
