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

    /** 网页版的「最新 Release」：302 到 `/releases/tag/<tag>`。不走 API，不吃那 60 次/小时的额度 */
    private const val LATEST_RELEASE_PAGE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    private const val RELEASE_DOWNLOAD_BASE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download"
    private const val RELEASE_TAG_BASE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag"
    private const val RAW_BASE = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO"

    private const val TAG = "AppUpdateChecker"
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data object UpToDate : Result
        data class UpdateAvailable(val release: AppRelease) : Result
        data class Failed(val reason: String) : Result

        /** API 限流了，网页那条路也没走通。界面给一句人话，而不是「HTTP 403」 */
        data object RateLimited : Result
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
                val release = try {
                    parseRelease(httpGet(LATEST_RELEASE_URL), abi)
                } catch (e: HttpStatusException) {
                    // 未登录的 API 一个出口 IP 一小时 60 次。国内运营商大多是一大群手机共用出口，
                    // 实测经常一上来就是 403 —— 退到网页那条路，它不算 API 额度
                    if (e.code != 403 && e.code != 429) throw e
                    Log.w(TAG, "api rate limited (HTTP ${e.code}), falling back to the web page")
                    runCatching { latestFromWeb(abi) }
                        .onFailure { Log.w(TAG, "web fallback failed", it) }
                        .getOrNull()
                        ?: return@withContext Result.RateLimited
                } ?: return@withContext Result.Failed("empty")
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

    /**
     * 不经 API 拼出最新 Release：tag 从网页的 302 里拿，APK 按发版约定的名字去探，
     * 说明取那个 tag 下 CHANGELOG.md 里对应的一节（取不到就空着，不耽误更新）。
     */
    private fun latestFromWeb(abi: String): AppRelease? {
        val tag = httpRedirectTarget(LATEST_RELEASE_PAGE)?.let(::tagFromReleaseUrl) ?: return null
        val version = normalizeTag(tag)
        // 存在的资源 github.com 回 302（跳到存储），不存在回 404。只看这一跳，
        // 不跟到存储那边去 —— 那边是签过名的 GET 链接，HEAD 过去未必认
        val apk = conventionalApkNames(version, abi).firstNotNullOfOrNull { name ->
            val url = "$RELEASE_DOWNLOAD_BASE/$tag/$name"
            if (httpRedirectTarget(url, method = "HEAD") != null) ApkAsset(name, url, sizeBytes = 0L) else null
        }
        val notes = runCatching { changelogSection(httpGet("$RAW_BASE/$tag/CHANGELOG.md"), version) }
            .getOrNull().orEmpty()
        return AppRelease(
            tag = tag,
            version = version,
            name = "Min $version",
            body = notes,
            htmlUrl = "$RELEASE_TAG_BASE/$tag",
            apk = apk,
            apkNames = listOfNotNull(apk?.name),
        )
    }

    /** `…/releases/tag/v2.1.17` → `v2.1.17` */
    internal fun tagFromReleaseUrl(url: String): String? =
        url.substringAfter("/releases/tag/", missingDelimiterValue = "")
            .substringBefore('?').trim('/').takeIf { it.isNotBlank() }

    /** 发版约定的资源名（见 CHANGELOG 2.1.13）：先本机 ABI，再 universal */
    internal fun conventionalApkNames(version: String, abi: String): List<String> =
        listOfNotNull(
            abi.takeIf { it.isNotBlank() }?.let { "Min-code-$version-$it.apk" },
            "Min-code-$version-universal.apk",
        )

    /** CHANGELOG.md 里 `## <version> …` 那一节的正文（不含标题行）；找不到是空串 */
    internal fun changelogSection(markdown: String, version: String): String {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { line ->
            line.startsWith("## ") && line.removePrefix("## ").trim().let { it == version || it.startsWith("$version ") }
        }
        if (start < 0) return ""
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    /** 只要状态码的那种失败：调用方要分辨是不是限流 */
    private class HttpStatusException(val code: Int) : IllegalStateException("HTTP $code")

    /** 3xx 的 Location；不是跳转就是 null。不跟随跳转 */
    private fun httpRedirectTarget(url: String, method: String = "GET"): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", "Min-code-updater")
        try {
            val code = connection.responseCode
            return if (code in 300..399) connection.getHeaderField("Location") else null
        } finally {
            connection.disconnect()
        }
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
                throw HttpStatusException(code)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
