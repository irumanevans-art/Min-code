package dev.min.code.core.settings

/**
 * # 几颗常用开关
 *
 * 全是 [ApiProfile.env] 里的已知键。界面上给开关，**底下仍然只有 env 一份事实**——
 * 另存一个 `teammates: Boolean` 之类的字段，就等于同一件事有两个写得下的地方，
 * 而这一整套功能的前提是只能有一个（同 `RESERVED_ENV_KEYS` 的理由）。
 *
 * 所以这里没有存储，只有「这个键是不是这个值」和「打开 / 关掉」两个纯函数。
 *
 * cc-switch 那边还有一颗「隐藏署名」，写的是 `settings.json` 的顶层 `attribution` 而不是
 * env —— 这里不收：我们托管的只有 `env` 块，而会话进程根本不读那个文件（见 `ProviderSync`
 * 的头注释）。给一颗点了不生效的开关，比没有这颗开关更糟。
 */
enum class ClaudeSwitch(val key: String, val on: String) {
    /** 子 agent 团队（实验特性） */
    AGENT_TEAMS("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "1"),

    /** 工具搜索 */
    TOOL_SEARCH("ENABLE_TOOL_SEARCH", "true"),

    /** 思考强度拉满 */
    EFFORT_MAX("CLAUDE_CODE_EFFORT_LEVEL", "max"),

    /**
     * 关掉 Artifact 工具。
     *
     * 不是偏好问题：有的网关对工具的 schema 做校验，遇上这个工具直接回 400，
     * 而那个 400 里什么线索都没有。
     */
    DISABLE_ARTIFACT("CLAUDE_CODE_DISABLE_ARTIFACT", "1"),
    ;

    fun isOn(env: Map<String, String>): Boolean = env[key].equals(on, ignoreCase = true)
}

/**
 * 开 / 关一颗。关 = **把键删掉**，不是写一个 `0`：
 * CLI 对这些键多半只看「有没有值」，写 0 反而是打开。
 */
fun Map<String, String>.withClaudeSwitch(switch: ClaudeSwitch, on: Boolean): Map<String, String> =
    if (on) this + (switch.key to switch.on) else this - switch.key
