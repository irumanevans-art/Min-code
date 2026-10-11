package dev.min.agent.tools

import dev.min.agent.spi.FeedbackDraft
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包：把一份反馈草稿导出成 zip，供用户自己拿去发（Min 不替用户发）。
 *
 * 包里的东西：`draft.md`（草稿）、`version.txt`（版本）、`config.txt`（环境与设置，去掉凭证）、可选的 `session-log.txt`（草稿附带的本会话日志，草稿没附则不带）。
 * **不含任何 key、令牌**：凭证来自两处——键名像凭证的环境变量与设置（[SECRET_NAME]）里的值，以及草稿与日志里长得像凭证的字符串（[SHAPED]）。
 * 两道都在导出前替换掉；最后还要扫一遍成品，扫到残留就抛错、不写 zip（[leaks]），宁可导出失败也不泄露。
 */
object FeedbackBundle {
    /** 键名像凭证的项，它的值就是要去掉的凭证 */
    private val SECRET_NAME = Regex("(?i)key|token|secret|password|passwd|auth|credential")

    /**
     * 长得像凭证的字符串：即便不在环境里（模型或工具把它打印出来了），也要去掉。
     * 手机端没有凭证名单（env 与 settings 传空表），只能靠这张表，所以按常见几家的公开格式列全：
     * 各家 API key / 令牌、JWT、PEM 私钥块、HTTP 认证头、Slack Webhook。
     */
    private val SHAPED = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
        Regex("sk-[A-Za-z0-9_\\-]{8,}"),
        Regex("[sr]k_(?:live|test)_[A-Za-z0-9]{8,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{8,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("glpat-[A-Za-z0-9_\\-]{20,}"),
        Regex("hf_[A-Za-z0-9]{30,}"),
        Regex("xox[abposr]-[A-Za-z0-9\\-]{10,}"),
        Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
        Regex("AIza[0-9A-Za-z_\\-]{35}"),
        Regex("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"),
        Regex("https://hooks\\.slack\\.com/services/\\S+"),
        Regex("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/=\\-]{6,}"),
    )

    /** 地址里的 `用户:口令@`（数据库地址、带认证的中转地址）：只换掉这一段，主机名留着方便排查 */
    private val URL_USERINFO = Regex("(?<=://)[^/\\s:@]+:[^/\\s@]+@")

    /** 文本里的口令赋值（`password: …`、`api_key=…`）：键名留着，值换掉。值已经是占位的不再算 */
    private val ASSIGNED = Regex("(?i)(?<![A-Za-z0-9])((?:api[_-]?key|access[_-]?key|secret|token|password|passwd)\\s*[:=]\\s*)(?!\\[已去除\\])[^\\s,;]+")

    /**
     * 诊断包只收 agent 自己的环境变量。桌面传进来的是整份 System.getenv()：数据库地址、Webhook、本机用户名这些
     * 既与排查无关，名字又不一定像凭证（`SLACK_WEBHOOK_URL` 的值本身就是钥匙），只按名字去凭证兜不住，所以先按前缀筛
     */
    private val RELEVANT_ENV_PREFIXES = listOf("CLAUDE_", "ANTHROPIC_", "MIN_")

    private const val REDACTED = "[已去除]"

    /** 太短的值（如 `1`）不当凭证：否则会把正文里所有的 `1` 都抹掉 */
    private const val MIN_SECRET_LENGTH = 6

    /**
     * 组出 zip。[env] 与 [settings] 是会话看得到的环境变量与设置；[sessionLog] 为 null 表示草稿没附日志。
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
        val shownEnv = env.filterKeys { key -> RELEVANT_ENV_PREFIXES.any { key.startsWith(it) } }
        val files = linkedMapOf<String, String>()
        files["draft.md"] = scrub("# ${draft.title}\n\n${draft.body}\n", secrets)
        files["version.txt"] = scrub(versionInfo + "\n", secrets)
        files["config.txt"] = scrub(
            (shownEnv.map { "${it.key}=${it.value}" } + settings.map { "${it.key}=${it.value}" }).joinToString("\n", postfix = "\n"),
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
        out = URL_USERINFO.replace(out, "$REDACTED@")
        out = ASSIGNED.replace(out) { it.groupValues[1] + REDACTED }
        return out
    }

    /** 成品里有没有任何一个凭证的值。导出前的最后一道扫描 */
    internal fun leaks(files: Map<String, String>, secrets: Set<String>): Boolean =
        files.values.any { text -> secrets.any { text.contains(it) } || SHAPED.any { it.containsMatchIn(text) } || URL_USERINFO.containsMatchIn(text) }

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
