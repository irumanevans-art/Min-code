package dev.min.code.core.claudecode

import android.content.Context
import androidx.core.content.edit

/**
 * 记住每个会话用的模型 / 思考强度 / 权限模式。
 *
 * ## 为什么需要
 *
 * 这些选择只活在 [ClaudeCodeManager] 的内存状态里。杀掉 App 再进同一个会话时，
 * 注册表会**新建一个 manager**，它的 `SessionOptions()` 全是默认值 —— `model = null`
 * 意味着交给 CLI 自己决定，于是原本的 `opus[1m]`（1M 上下文）悄悄变成默认的 200k。
 * 用户看到的现象是"上下文额度莫名其妙缩水了"，而且**没有任何提示**：
 * 顶栏显示的是 CLI 回报的当前模型，它确实变了，只是没人说为什么。
 *
 * CLI 自己的 transcript 不记这些（它记的是消息，不是客户端的启动参数），
 * 所以只能由 App 侧存。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * 读取发生在 `openSession()` 的同步路径上（要先有 options 才能拼 argv）。
 * DataStore 全是挂起 API，为这么几个标量引一次协程切换不划算；
 * 这里的数据量是每会话 5 个标量，SharedPreferences 正合适。
 *
 * 编解码本身抽成了 [encodeSessionOptions] / [decodeSessionOptions] 两个纯函数，
 * 单测直接拿普通 Map 跑，不需要为此引入 Robolectric。
 */
class ClaudeCodeSessionPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("claudecode.sessions", Context.MODE_PRIVATE)

    /**
     * 记下某个会话用的配置。[alsoAsLast] 为 true 时同时更新「最近一次」——新建会话时拿它当默认值；
     * false 对应 CLI `/model` 面板里的 `s`（仅本会话）：这一会话记住了，别的会话不跟着变。
     */
    fun save(sessionId: String, options: ClaudeCodeManager.SessionOptions, alsoAsLast: Boolean = true) {
        val encoded = encodeSessionOptions(options)
        prefs.edit {
            writeAll(key(sessionId), encoded)
            if (alsoAsLast) writeAll(LAST_PREFIX, encoded)
        }
    }

    /**
     * 读回某个会话的配置。没记过（旧会话、别的设备同步过来的 transcript）时返回 null，
     * 调用方应退回 [loadLast] 而不是硬套默认值 —— 用户多半还想用上次那个模型。
     */
    fun load(sessionId: String): ClaudeCodeManager.SessionOptions? {
        val prefix = key(sessionId)
        if (!prefs.contains("$prefix.$KEY_PRESENT")) return null
        return decodeSessionOptions(readAll(prefix))
    }

    /** 最近一次用过的配置；一次都没存过时给出厂默认 */
    fun loadLast(): ClaudeCodeManager.SessionOptions =
        if (prefs.contains("$LAST_PREFIX.$KEY_PRESENT")) {
            decodeSessionOptions(readAll(LAST_PREFIX))
        } else {
            ClaudeCodeManager.SessionOptions()
        }

    /** 删会话时一并忘掉。**不动「最近一次」** —— 删一个会话不代表放弃那套偏好。 */
    fun forget(sessionId: String) {
        val prefix = key(sessionId)
        prefs.edit {
            SESSION_OPTION_KEYS.forEach { remove("$prefix.$it") }
        }
    }

    private fun android.content.SharedPreferences.Editor.writeAll(
        prefix: String,
        values: Map<String, String>,
    ) {
        // 缺席的键要真的删掉而不是留着旧值：把模型从 opus 改回"跟随 CLI 默认"时，
        // 残留的旧值会让下次续会话又把 opus 传下去
        SESSION_OPTION_KEYS.forEach { k ->
            val v = values[k]
            if (v == null) remove("$prefix.$k") else putString("$prefix.$k", v)
        }
    }

    private fun readAll(prefix: String): Map<String, String> =
        SESSION_OPTION_KEYS.mapNotNull { k ->
            prefs.getString("$prefix.$k", null)?.let { k to it }
        }.toMap()

    private fun key(sessionId: String) = "session.${sessionId.toSafeName()}"

    private companion object {
        const val LAST_PREFIX = "last"
    }
}

// ---------------------------------------------------------------------------
// 纯编解码（可单测）
// ---------------------------------------------------------------------------

internal const val KEY_PRESENT = "present"
internal const val KEY_MODEL = "model"
internal const val KEY_EFFORT = "effort"
internal const val KEY_ULTRACODE = "ultracode"
internal const val KEY_SKIP_PERMISSIONS = "skipPermissions"
internal const val KEY_CACHE_TTL = "promptCacheTtl"
internal const val KEY_PERMISSION_MODE = "permissionMode"
internal const val KEY_CWD = "cwd"

internal val SESSION_OPTION_KEYS = listOf(
    KEY_PRESENT, KEY_MODEL, KEY_EFFORT, KEY_ULTRACODE, KEY_SKIP_PERMISSIONS, KEY_CACHE_TTL,
    KEY_PERMISSION_MODE, KEY_CWD,
)

/**
 * 只编码**跨进程有意义**的字段。
 *
 * `resumeSessionId` / `newSessionId` 是进程绑定的，由调用方当场填；存下来的话，
 * 下次续会话会拿着一个上辈子的 id 去 `--resume`，CLI 直接报 "No conversation found"。
 *
 * `model = null` 的语义是"交给 CLI 决定"，和"名字是空串的模型"完全不同 ——
 * 所以 null 编码成**键缺席**，而不是空串。空串会被原样拼进 `--model ''`。
 */
internal fun encodeSessionOptions(options: ClaudeCodeManager.SessionOptions): Map<String, String> =
    buildMap {
        put(KEY_PRESENT, "1")
        options.model?.takeIf { it.isNotBlank() }?.let { put(KEY_MODEL, it) }
        options.effort?.takeIf { it.isNotBlank() }?.let { put(KEY_EFFORT, it) }
        put(KEY_ULTRACODE, options.ultracode.toString())
        put(KEY_SKIP_PERMISSIONS, options.skipPermissions.toString())
        put(KEY_CACHE_TTL, options.promptCacheTtl)
        put(KEY_PERMISSION_MODE, options.permissionMode.wire)
        val cwd = CwdPath.normalize(options.cwd)
        if (cwd != ClaudeCodeManager.DEFAULT_CWD) put(KEY_CWD, cwd)
    }

/**
 * 反向。非法的缓存 TTL 一律退回默认值：CLI 对非法 `CLAUDE_CODE_PROMPT_CACHE_TTL`
 * 是**静默忽略**（zod 的 `.catch(void 0)`），传错了这边一点感知都没有。
 */
internal fun decodeSessionOptions(values: Map<String, String>): ClaudeCodeManager.SessionOptions =
    ClaudeCodeManager.SessionOptions(
        model = values[KEY_MODEL],
        effort = values[KEY_EFFORT],
        ultracode = values[KEY_ULTRACODE] == "true",
        skipPermissions = values[KEY_SKIP_PERMISSIONS] == "true",
        promptCacheTtl = values[KEY_CACHE_TTL]
            ?.takeIf { it in ClaudeCodeManager.PROMPT_CACHE_TTLS }
            ?: ClaudeCodeManager.DEFAULT_PROMPT_CACHE_TTL,
        permissionMode = ClaudeCodePermissionMode.fromWire(values[KEY_PERMISSION_MODE])
            ?: ClaudeCodePermissionMode.DEFAULT,
        cwd = CwdPath.normalize(values[KEY_CWD].orEmpty()),
    )
