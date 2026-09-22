package dev.min.code.core.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import dev.min.code.util.fileProviderUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 Releases 上的 APK 拉到 cache，再交给系统安装器。
 *
 * 下载模式照 [dev.min.code.core.claudecode.ClaudeCodeInstaller]：认 Range 就续传，
 * 不认就从头覆盖。安装走 FileProvider + ACTION_VIEW，不自己实现 PackageInstaller 会话。
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val HTTP_PARTIAL = 206
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    sealed interface DownloadResult {
        data class Ok(val file: File) : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** 跳到「允许安装未知应用」——Android O+ 必须用户自己开 */
    fun unknownSourcesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    /**
     * @param onProgress 0f..1f，在 IO 线程同步回调——界面用 `withContext(Main)` 再写 state。
     */
    suspend fun download(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            dest.parentFile?.mkdirs()
            downloadResumable(url, dest, onProgress)
            DownloadResult.Ok(dest)
        } catch (e: Exception) {
            Log.w(TAG, "download failed: $url", e)
            DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun installApk(context: Context, apk: File): Boolean {
        val uri = context.fileProviderUri(apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure {
            Log.e(TAG, "install intent failed", it)
        }.getOrDefault(false)
    }

    fun openReleasePage(context: Context, htmlUrl: String) {
        if (htmlUrl.isBlank()) return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, htmlUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "open release page failed", it) }
    }

    fun defaultApkFile(context: Context, version: String): File {
        val dir = File(context.cacheDir, "app-update").apply { mkdirs() }
        return File(dir, "Min-code-$version.apk")
    }

    private fun downloadResumable(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val existing = if (dest.isFile) dest.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
        connection.setRequestProperty("User-Agent", "Min-code-updater")
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
}
