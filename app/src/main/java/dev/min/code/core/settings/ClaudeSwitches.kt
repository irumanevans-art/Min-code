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
enum class ClaudeSwitch(val key: String, val on: String, val off: String? = null) {
    /** 子 agent 团队（实验特性） */
    AGENT_TEAMS("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "1"),

    /**
     * 工具搜索。CLI 2.1.304 起自定义 `ANTHROPIC_BASE_URL` 后面也默认打开（API Key 档总设这个），
     * 不设就是开，只有写成 false 一类的值才关。实测：不设时工具表是 ToolSearch + 占位，设 false 才是完整的。
     */
    TOOL_SEARCH("ENABLE_TOOL_SEARCH", "true", off = "false"),

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

    /** 有 [off] 的键默认是开的：没设也算开，只有 CLI 认作假的值（false / 0 / no / off）才算关 */
    fun isOn(env: Map<String, String>): Boolean {
        val value = env[key]
        if (off == null) return value.equals(on, ignoreCase = true)
        return value == null || value.trim().lowercase() !in CLI_FALSY
    }

    private companion object {
        /** CLI 判「明确关掉」的取值，大小写不敏感 */
        val CLI_FALSY = setOf("false", "0", "no", "off")
    }
}

/**
 * 开 / 关一颗。默认关的键，关 = **把键删掉**，不是写一个 `0`：
 * CLI 对这些键多半只看「有没有值」，写 0 反而是打开。
 * 默认开的键（有 [ClaudeSwitch.off]）删掉等于开着，关只能写它的关值。
 */
fun Map<String, String>.withClaudeSwitch(switch: ClaudeSwitch, on: Boolean): Map<String, String> = when {
    on -> this + (switch.key to switch.on)
    switch.off != null -> this + (switch.key to switch.off)
    else -> this - switch.key
}
