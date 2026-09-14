package dev.min.code.ui.session

/**
 * 斜杠命令 / 模型说明的中文对照。
 *
 * ## 为什么是查表而不是翻译
 *
 * 这些说明是 CLI 在 `initialize` 应答里现给的英文原文，内容随版本和已装插件变。
 * 想中文化只有两条路：调模型翻译（每开一次面板烧一次钱、还得等），或者查表。
 * 表是对的选择 —— 命令集是有限且稳定的，而且译文可以写得比直译更贴合手机上的用法。
 *
 * **查不到的一律回退到原文**，绝不硬译也绝不留空：插件命令、用户自定义命令、
 * 新版本新增的命令都会走到这条路上，显示英文原文远好过显示一个错的中文。
 */

/**
 * 内置斜杠命令的中文说明。只收录**确定含义**的那些 —— 猜着写一条中文说明，
 * 比让用户读英文原文糟得多。
 */
private val SLASH_COMMAND_ZH = mapOf(
    "help" to "列出可用命令和基本用法",
    "clear" to "清空当前会话的上下文，从头开始",
    "compact" to "把已有对话压缩成摘要，腾出上下文空间",
    "cost" to "显示本次会话的 token 用量和花费",
    "context" to "显示当前上下文被什么占满了",
    "usage" to "查看用量额度",
    "model" to "切换模型（/model <名字> 会存成新会话的默认）",
    "effort" to "调整思考强度 low / medium / high / xhigh / max",
    "fast" to "快速模式：同一模型、更快出字、按更高单价计费",
    "agents" to "管理子 agent（subagent）定义",
    "mcp" to "管理 MCP 服务器",
    "memory" to "编辑 CLAUDE.md 记忆文件",
    "config" to "查看和修改配置",
    "statusline" to "配置状态栏",
    "output-style" to "切换输出风格",
    "permissions" to "管理工具权限规则",
    "hooks" to "配置钩子（在工具调用前后自动跑命令）",
    "init" to "扫描当前项目并生成 CLAUDE.md",
    "review" to "审查代码改动",
    "security-review" to "对当前改动做一次安全审查",
    "pr-comments" to "拉取并查看 PR 上的评论",
    "resume" to "继续之前的会话",
    "rewind" to "回退到会话中的某个检查点",
    "export" to "把当前会话导出成文件",
    "status" to "显示当前会话和环境状态",
    "doctor" to "自检安装和配置是否正常",
    "bug" to "提交问题反馈给 Anthropic",
    "feedback" to "提交反馈",
    "release-notes" to "查看版本更新说明",
    "upgrade" to "升级订阅方案",
    "login" to "登录账号",
    "logout" to "退出登录",
    "exit" to "结束会话",
    "add-dir" to "把另一个目录加入可访问范围",
    "bashes" to "查看后台运行的 shell 任务",
    "todos" to "查看当前待办列表",
    "vim" to "启用 vim 按键模式",
    "terminal-setup" to "配置终端按键绑定",
    "install-github-app" to "安装 GitHub App，让 Claude 在仓库里干活",
    "migrate-installer" to "把安装方式迁移到本地安装",
    "privacy-settings" to "隐私设置",
    "plugin" to "管理插件",
    "sandbox" to "沙箱设置",
    "tasks" to "查看正在跑的任务",
    "workflows" to "查看工作流的实时进度",
    "loop" to "按固定间隔反复跑一条提示词或命令",
    "simplify" to "审查改动里可以复用 / 简化 / 提速的地方并直接改掉",
    "code-review" to "审查当前 diff，找正确性缺陷和可清理的地方",
    "fewer-permission-prompts" to "扫描历史记录，把常用的只读命令加进白名单，减少权限询问",
    "remember" to "把一件事记进长期记忆",
    "skill-doctor" to "自检技能（skill）定义是否有问题",
)

/**
 * 模型说明的中文对照。**按模型 id 查而不是按英文原文查** —— CLI 给的那段英文里
 * 嵌着当前默认模型名（`Use the default model (currently Opus 5 (1M context))`），
 * 一换默认模型字符串就变了，拿原文当键第二天就失配。
 */
private val MODEL_DESCRIPTION_ZH = mapOf(
    "default" to "跟随 CLI / 中转站决定的默认模型（直连 API 是 Opus 5，1M 上下文）",
    // 别名 `fable` 在 CLI 里按供应商解析（直连 → 5.1，gateway → 5），说明里不写死版本号
    "fable" to "Fable · 最难、最长的任务 · 每百万 token \$10 / \$50 · 1M 上下文（原生）· 思考常开",
    "fable[1m]" to "Fable · 同上；Fable 原生就是 1M，[1m] 可加可不加",
    "claude-fable-5-1" to "Fable 5.1 · 最强，最难、最长的任务 · 每百万 token \$10 / \$50 · 1M 上下文（原生）· 思考常开",
    "claude-fable-5" to "Fable 5 · 上一代 Fable · 每百万 token \$10 / \$50 · 1M 上下文（原生）",
    "best" to "自动选当前最强的模型（现在是 Fable）",
    "opusplan" to "计划阶段用 Opus，执行阶段用 Sonnet",
    "opus" to "Opus 5 · 日常和复杂任务都最强 · 每百万 token \$5 / \$25",
    "opus[1m]" to "Opus 5，1M 上下文 · 日常和复杂任务都最强 · 每百万 token \$5 / \$25",
    "claude-opus-5" to "Opus 5 · 日常和复杂任务都最强 · 每百万 token \$5 / \$25 · 1M 上下文（原生）",
    "sonnet" to "Sonnet 5 · 常规任务，性价比高 · 每百万 token \$2 / \$10",
    "sonnet[1m]" to "Sonnet 5，1M 上下文 · 适合长会话 · 每百万 token \$2 / \$10",
    "claude-sonnet-5" to "Sonnet 5 · 常规任务，性价比高 · 每百万 token \$2 / \$10 · 1M 上下文（原生）",
    "haiku" to "Haiku 4.5 · 最快，适合简单问答 · 每百万 token \$1 / \$5 · 200k 上下文 · 不支持思考强度",
    "haiku[1m]" to "Haiku 4.5，1M 上下文 · 最快，适合简单问答 · 每百万 token \$1 / \$5 · 不支持思考强度",
    "claude-haiku-4-5" to "Haiku 4.5 · 最快，适合简单问答 · 每百万 token \$1 / \$5 · 200k 上下文 · 不支持思考强度",
)

/**
 * 中转站给的裸 id 常带日期后缀或 `[1m]`（`claude-opus-5-20260401[1m]`），
 * 精确查不到时退一步按**固定 id 前缀**查 —— 版本号相同的模型说明一样。
 * 再查不到就老实回退原文，绝不按家族瞎猜（`claude-opus-4-1` 和 Opus 5 价格不同）。
 */
private fun lookupModelDescription(value: String): String? {
    val key = value.lowercase().trim()
    MODEL_DESCRIPTION_ZH[key]?.let { return it }
    val bare = key.removeSuffix("[1m]")
    MODEL_DESCRIPTION_ZH[bare]?.let { return it }
    return MODEL_DESCRIPTION_ZH.entries
        .filter { it.key.startsWith("claude-") }
        .sortedByDescending { it.key.length }
        .firstOrNull { bare.startsWith(it.key + "-") || bare.startsWith(it.key + "@") }
        ?.value
}

/**
 * 一条斜杠命令该显示什么说明。
 *
 * @param chinese true 用中文表，查不到回退原文；false 一律原文
 */
internal fun slashCommandDescription(name: String, original: String?, chinese: Boolean): String? {
    if (!chinese) return original
    return SLASH_COMMAND_ZH[name.lowercase()] ?: original
}

/** 同上，模型说明。按 [value]（`opus[1m]` 这类 id）查表 */
internal fun modelDescription(value: String, original: String?, chinese: Boolean): String? {
    if (!chinese) return original
    return lookupModelDescription(value) ?: original
}

/** 有多少条命令能给出中文说明 —— 用来在面板上如实说明覆盖范围 */
internal fun translatedCommandCount(names: List<String>): Int =
    names.count { it.lowercase() in SLASH_COMMAND_ZH }
