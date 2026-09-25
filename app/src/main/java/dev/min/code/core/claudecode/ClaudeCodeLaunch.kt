package dev.min.code.core.claudecode

import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.DEFAULT_BASE_URL
import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.EFFORT_LEVELS
import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.EFFORT_ULTRACODE
import dev.min.code.core.claudecode.ClaudeCodeManager.Companion.PROMPT_CACHE_TTLS
import dev.min.code.core.network.NetworkSnapshot
import dev.min.code.core.settings.ApiProfile
import dev.min.code.core.settings.sanitizedProfileEnv

/*
 * 起 CLI 那一刻的 argv 与 env，从 [ClaudeCodeManager.launchCli] 里拆出来的纯函数：
 * 不碰进程、不碰磁盘、不挂起，输入一样输出就一样 —— 所以能在 JVM 单测里逐条钉住。
 * 这两张单子上每一项都是踩过坑才加的（注释原样跟过来了），以前藏在 3000 行的类里没人测。
 */

/**
 * claude 的完整 argv。[entry] 是 [ClaudeCodeInstaller.claudeEntry] 给的启动前缀（node + cli.js 或原生二进制），
 * [sessionId] 在续会话时就是要续的那个 id。
 */
internal fun claudeLaunchArgs(
    entry: List<String>,
    options: ClaudeCodeManager.SessionOptions,
    sessionId: String,
): List<String> = buildList {
        addAll(entry)
        add("-p")
        add("--output-format"); add("stream-json")
        add("--input-format"); add("stream-json")
        add("--verbose")
        add("--include-partial-messages")
        // 没有这个 flag 就没有权限询问面，需审批的工具会被直接拒绝，见 [ClaudeCodeManager] 类注释 1
        add("--permission-prompt-tool"); add("stdio")
        // 续会话用 --resume，新会话才自己指定 --session-id（两者互斥）
        if (options.resumeSessionId != null) {
            add("--resume"); add(sessionId)
        } else {
            add("--session-id"); add(sessionId)
        }
        options.model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
        // CLI 没有 set_effort，effort 只能在启动时定；中途改由 applyEffort() 走重启续会话。
        //
        // ultracode 本身就是 --effort 的一个（未文档化但合法的）取值，会被解析成
        // xhigh + 打开工作流编排；它和阶梯档位互斥，所以这里二选一。
        // 实测启动路径可靠生效（get_settings 回报 applied.ultracode=true），
        // 只有运行时改档才会撞上 launch-effort pin。
        if (options.ultracode) {
            add("--effort"); add(EFFORT_ULTRACODE)
        } else {
            // 阶梯档位必须过白名单：CLI 对非法 --effort 是 **warn-and-ignore**
            // （不报错、不退出、退出码仍是 0），传错了这边一点感知都没有。
            options.effort?.takeIf { it in EFFORT_LEVELS }?.let { add("--effort"); add(it) }
        }
        // 不加这个 flag 的话，运行时切到 bypassPermissions 会被 CLI 拒绝：
        //   "Cannot set permission mode to bypassPermissions because the session
        //    was not launched with --dangerously-skip-permissions"
        // 而 --allow-dangerously-skip-permissions 的语义正是「让它成为可选项，但不默认开启」，
        // 实测带上它之后四种模式都能热切，且默认仍是 Manual。
        add("--allow-dangerously-skip-permissions")
        // 始终显式带上：续会话时不传的话，plan 模式里结束的会话会掉回 default
        // （CLI 2.1.246，`--permission-prompt-tool` + `-p --resume`）。
        add("--permission-mode")
        add(
            if (options.skipPermissions) ClaudeCodePermissionMode.BYPASS.wire
            else options.permissionMode.wire
        )
}

/**
 * 会话进程的环境变量。[relayBaseUrl] 是本地协议路由给的地址（方言不是原生时才有），
 * 要挂起才拿得到，所以由调用方先算好传进来；[netSnap] 为空时不注入网络身份卡。
 *
 * [profile] 为 null = 走 Claude 订阅：供应商的自定义 env、地址、token **一个都不写**（见 `ClaudeSubscription.kt`），
 * CLI 读它自己登录留下的凭证文件。
 */
internal fun claudeSessionEnv(
    options: ClaudeCodeManager.SessionOptions,
    profile: ApiProfile?,
    relayBaseUrl: String?,
    netSnap: NetworkSnapshot?,
): Map<String, String> = buildMap {
        putAll(ClaudeCodeInstaller.nodeEnv())
        // proot 以 --root-id 运行，不声明沙箱的话 bypassPermissions 会直接 exit(1)，见 [ClaudeCodeManager] 类注释 2
        put("IS_SANDBOX", "1")
        // 让 CLI 自己把 Fable 列进 /model 目录，并把别名 `fable` 钉到 5.1。
        // v2.1.261 的可见性门槛 `_se()` 里有一条 `if (ANTHROPIC_DEFAULT_FABLE_MODEL) return true`，
        // 而别名解析 `fable:{default:"claude-fable-5-1", per_provider:{gateway:"claude-fable-5"}}`
        // 在 gateway 下会退回上一代 —— 这两个问题官方给的钥匙都是这一个环境变量
        // （model-config 文档：ANTHROPIC_DEFAULT_*_MODEL 决定别名解析到哪个 id）。
        put("ANTHROPIC_DEFAULT_FABLE_MODEL", ClaudeCodeModelCatalog.FABLE_MODEL_ID)
        put("ANTHROPIC_DEFAULT_FABLE_MODEL_NAME", "Fable 5.1")
        // CLI 对不在它表里的 id 按 200k 处理并在大约八成时自动压缩。
        // Fable / Opus 5 / Sonnet 5 原生 1M，不注入的话底栏会画成 97k/200k。
        put(
            "CLAUDE_CODE_MAX_CONTEXT_TOKENS",
            ClaudeCodeModelCatalog.assumedContextWindow(options.model).toString(),
        )
        put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
        put("CLAUDE_CODE_ATTRIBUTION_HEADER", "0")
        // 提示缓存 TTL。CLI 的解析顺序是
        //   FORCE_PROMPT_CACHING_5M > 环境变量 > settings.json > 按认证方式自动决定，
        // 环境变量优先级高于 settings，所以直接注入最省事。
        // 主对话和子 agent / 工作流 / 后台请求是**两个独立开关**，只设前者的话
        // 子 agent 仍然跑 5 分钟缓存，长任务里这部分开销并不小。
        options.promptCacheTtl.takeIf { it in PROMPT_CACHE_TTLS }?.let { ttl ->
            put("CLAUDE_CODE_PROMPT_CACHE_TTL", ttl)
            put("CLAUDE_CODE_SUBAGENT_PROMPT_CACHE_TTL", ttl)
        }
        // 沙箱里没法交互，自动更新只会把已装版本覆盖坏
        put("DISABLE_AUTOUPDATER", "1")
        put("DISABLE_TELEMETRY", "1")
        put("DISABLE_ERROR_REPORTING", "1")
        // agent 真 apt install 时别卡 dpkg 交互
        put("DEBIAN_FRONTEND", "noninteractive")
        put("USER", "root")
        put("SHELL", "/bin/bash")
        if (netSnap != null) putAll(GuestRuntimeDocs.envFrom(netSnap))

        // 这条供应商自己带的环境变量。放在**这里**而不是更前面：它可以覆盖上面
        // 那些 App 的偏好项（比如中转站的上下文窗口并不是 1M，用户要自己改
        // CLAUDE_CODE_MAX_CONTEXT_TOKENS），但 RESERVED_ENV_KEYS 里的一律被
        // sanitizedProfileEnv 丢掉 —— 那几个是事实来源自己的位置
        if (profile == null) return@buildMap
        putAll(sanitizedProfileEnv(profile))

        // 地址与 token 最后落笔，保证它们赢。
        //
        // 已知取舍: env -i 会把值写进 argv, 沙箱内可从 /proc/<pid>/cmdline 读到。
        // 试过改用 `--settings <file>` 的 env 块把 token 挪出 argv, 实测 -p 模式下
        // CLI 会读取该文件但**不应用**其中的 env（用 ANTHROPIC_BASE_URL 对拍验证过），
        // 所以只能走环境变量。能读到它的只有沙箱内的 Claude Code 自己 —— 它本来就持有
        // 这个 token, 因此不构成额外的权限提升。
        //
        // 同一条实测也是「托管 settings.json」只能是投影、不能是事实来源的原因，
        // 见 ProviderSync 的类注释。
        // 方言不是原生时改写成本地路由地址。路由只在 App 活着时通，
        // 界面上已经把这句话说出来了（见供应商页高级区的方言说明）
        val base = relayBaseUrl
            ?: profile.baseUrl.ifBlank { DEFAULT_BASE_URL }
        put("ANTHROPIC_BASE_URL", base.ifBlank { DEFAULT_BASE_URL })
        // 放进哪个头由这条供应商说了算：`ANTHROPIC_AUTH_TOKEN` 发的是
        // `Authorization: Bearer`，`ANTHROPIC_API_KEY` 发的是 `x-api-key`。
        // 有的中转只认后者 —— 探活一直有这条回退，真正发请求的路上以前没有，
        // 于是出现「测得通、用不了」
        put(profile.tokenEnvKey, profile.token)
}
