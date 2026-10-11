package dev.min.agent.tools

import dev.min.agent.spi.FeedbackDraft
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包：把一份反馈草稿导出成 zip，供用户自己拿去发（Min 不替用户发）。
 *
 * 包里的东西：`draft.md`（草稿）、`version.txt`（版本）、`config.txt`（环境与设置，去掉凭证）、可选的 `session-log.txt`（本会话日志，由用户选择是否带上）。
 * **不含任何 key、令牌**：凭证来自两处——键名像凭证的环境变量与设置（[SECRET_NAME]）里的值，以及草稿与日志里长得像凭证的字符串（[SHAPED]）。
 * 两道都在导出前替换掉；最后还要扫一遍成品，扫到残留就抛错、不写 zip（[leaks]），宁可导出失败也不泄露。
 */
object FeedbackBundle {
    /** 键名像凭证的项，它的值就是要去掉的凭证 */
    private val SECRET_NAME = Regex("(?i)key|token|secret|password|passwd|auth|credential")

    /** 长得像凭证的字符串：即便不在环境里（模型或工具把它打印出来了），也要去掉 */
    private val SHAPED = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{8,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{8,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+"),
    )

    private const val REDACTED = "[已去除]"

    /** 太短的值（如 `1`）不当凭证：否则会把正文里所有的 `1` 都抹掉 */
    private const val MIN_SECRET_LENGTH = 6

    /**
     * 组出 zip。[env] 与 [settings] 是会话看得到的环境变量与设置；[sessionLog] 为 null 表示用户没选带日志。
     * 扫描不过（成品里仍有凭证）时抛 [IllegalStateException]，不返回包。
     */
    fun build(
        draft: FeedbackDraft,
        sessionLog: String?,
        versionInfo: String,
        env: Map<String, String>,
        settings: Map<String, String>,
    ): ByteArray {
        val secrets = (env + settings)
            .filter { SECRET_NAME.containsMatchIn(it.key) }
            .values
            .filter { it.length >= MIN_SECRET_LENGTH }
            .toSet()
        val files = linkedMapOf<String, String>()
        files["draft.md"] = scrub("# ${draft.title}\n\n${draft.body}\n", secrets)
        files["version.txt"] = scrub(versionInfo + "\n", secrets)
        files["config.txt"] = scrub(
            (env.map { "${it.key}=${it.value}" } + settings.map { "${it.key}=${it.value}" }).joinToString("\n", postfix = "\n"),
            secrets,
        )
        if (sessionLog != null) files["session-log.txt"] = scrub(sessionLog, secrets)
        check(!leaks(files, secrets)) { "诊断包里还有凭证，拒绝导出。" }
        return zip(files)
    }

    /** 把 [secrets] 的每个值与形似凭证的字符串换成占位 */
    internal fun scrub(text: String, secrets: Set<String>): String {
        var out = text
        for (s in secrets) out = out.replace(s, REDACTED)
        for (r in SHAPED) out = r.replace(out, REDACTED)
        return out
    }

    /** 成品里有没有任何一个凭证的值。导出前的最后一道扫描 */
    internal fun leaks(files: Map<String, String>, secrets: Set<String>): Boolean =
        files.values.any { text -> secrets.any { text.contains(it) } }

    private fun zip(files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
