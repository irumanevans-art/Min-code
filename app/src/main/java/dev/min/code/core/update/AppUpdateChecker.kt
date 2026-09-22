package dev.min.code.core.update

import android.util.Log
import dev.min.code.core.claudecode.ClaudeCodeInstaller
import dev.min.code.core.rootfs.DeviceArch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * 查 GitHub Releases 有没有比当前安装更新的 App。
 *
 * 不碰 CLI 那套「检查更新」——那边查的是 npm 上的 `@anthropic-ai/claude-code`。
 * 网络层跟仓库其它地方一样：裸 [HttpURLConnection]，不引入 OkHttp。
 */
object AppUpdateChecker {

    const val GITHUB_OWNER = "irumanevans-art"
    const val GITHUB_REPO = "Min-code"
    const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val TAG = "AppUpdateChecker"
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data object UpToDate : Result
        data class UpdateAvailable(val release: AppRelease) : Result
        data class Failed(val reason: String) : Result
    }

    data class AppRelease(
        val tag: String,
        val version: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val apk: ApkAsset?,
        /** 所有 .apk 资源名，选不中时给对话框兜底展示 */
        val apkNames: List<String>,
    )

    data class ApkAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    )

    suspend fun check(currentVersionName: String, abi: String = DeviceArch.primaryAbi): Result =
        withContext(Dispatchers.IO) {
            try {
                val body = httpGet(LATEST_RELEASE_URL)
                val release = parseRelease(body, abi)
                    ?: return@withContext Result.Failed("empty")
                if (!ClaudeCodeInstaller.isNewerVersion(currentVersionName, release.version)) {
                    Result.UpToDate
                } else {
                    Result.UpdateAvailable(release)
                }
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                Result.Failed(e.message ?: e.javaClass.simpleName)
            }
        }

    /** `v2.1.13` / `2.1.13` / `Min-code 2.1.13` → `2.1.13` */
    internal fun normalizeTag(tag: String): String {
        var s = tag.trim()
        if (s.startsWith("v", ignoreCase = true) && s.length > 1 && s[1].isDigit()) {
            s = s.drop(1)
        }
        // 偶尔 tag 里夹着名字
        val digits = Regex("""\d+(?:\.\d+)+""").find(s)?.value
        return digits ?: s
    }

    /**
     * 选 APK：优先 `Min-code-<ver>-<abi>.apk`，再 `…-universal.apk`，再任意非 debug 的 `.apk`。
     * debug 包（名字里带 `debug`）一律跳过，避免 `dev.min.code.debug` 装到 release 上。
     */
    internal fun pickApkAsset(
        assets: List<ApkAsset>,
        version: String,
        abi: String,
    ): ApkAsset? {
        val candidates = assets.filter { !it.name.contains("debug", ignoreCase = true) }
        if (candidates.isEmpty()) return null
        // 只认本机 primary ABI（见 DeviceArch），不要用「列表里有没有 arm64」
        if (abi.isNotBlank()) {
            val abiNeedle = "-$abi"
            candidates.firstOrNull {
                it.name.contains(abiNeedle, ignoreCase = true) &&
                    it.name.contains(version, ignoreCase = true)
            }?.let { return it }
            candidates.firstOrNull { it.name.contains(abiNeedle, ignoreCase = true) }?.let { return it }
        }
        candidates.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
        return candidates.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    internal fun parseRelease(body: String, abi: String): AppRelease? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        val tag = (root["tag_name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (tag.isBlank()) return null
        val version = normalizeTag(tag)
        val assetsArray = root["assets"] as? JsonArray ?: JsonArray(emptyList())
        val assets = assetsArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val url = (obj["browser_download_url"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val size = (obj["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            ApkAsset(name = name, downloadUrl = url, sizeBytes = size)
        }
        return AppRelease(
            tag = tag,
            version = version,
            name = (root["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { tag },
            body = (root["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            htmlUrl = (root["html_url"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            apk = pickApkAsset(assets, version, abi),
            apkNames = assets.map { it.name },
        )
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Min-code-updater")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
