package dev.min.code.core.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 未捕获异常落盘，下次启动时展示。
 *
 * 这个 App 没有联网上报，唯一的排错手段就是用户把堆栈复制出来发给人看 ——
 * 所以崩溃页必须能"复制"，而且要保留到用户主动清掉为止（只看一眼就没了等于没有）。
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    buildString {
                        appendLine("Thread: ${thread.name}")
                        appendLine("Time: ${java.util.Date()}")
                        appendLine()
                        append(sw.toString())
                    }
                )
            }.onFailure { Log.e(TAG, "failed to record crash", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String? = runCatching {
        file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
