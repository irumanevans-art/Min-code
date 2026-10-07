package dev.min.agent.ext.mcp

/**
 * MCP 服务器配置的数据形状与闸门结论（MCP设计 §3；CC 语义见 research/inventory/mcp-client.md §1.1–1.3）。
 * 这一单只有「读配置 → 解析 → 每台服务器能不能启动、为什么」；连接、协议、工具是 MCP1a / MCP1c 的事。
 */

/** 配置来源，声明顺序即优先级（同名取最高优先级的**整条**定义，不按字段合并；cc-mcp.md「Scope hierarchy」） */
enum class McpScope { Managed, Local, Project, User }

enum class McpTransportKind { Stdio, Http, Sse, Ws }

/**
 * 一台服务器的闸门结论。声明顺序从宽到严：同时命中多条规则时取最严的一条（[McpConfigLoader] 里靠 ordinal 比）。
 * ws 传输首期不连（MCP设计 §13），解析出来但恒为 [Disabled]。
 */
enum class McpGate { Enabled, NeedsApproval, Untrusted, Disabled, Denied }

/**
 * 一台服务器解析后的定义：环境变量已按 CC 规则展开（未设且无默认的保留字面 `${VAR}`，见 [McpServerEntry.unresolvedVars]）。
 *
 * **toString 只写名字、作用域、传输与键名**（env / headers 的值、URL、命令行都不写）：它会进日志，
 * 而这里最不缺的就是凭证。需要给人看 URL / 命令行的界面走原始配置或脱敏后的展示，不走 toString。
 */
sealed interface McpServerSpec {
    val name: String
    val scope: McpScope

    /** 条目里的 `timeout`（毫秒）：≥1000 才生效（CC：是每次工具调用的硬性墙钟上限），否则 null 并已记 note */
    val timeoutMs: Long?
    val alwaysLoad: Boolean

    /** 本地进程服务器（stdio）。[env] 的值已展开 */
    data class Stdio(
        override val name: String,
        override val scope: McpScope,
        override val timeoutMs: Long?,
        override val alwaysLoad: Boolean,
        val command: String,
        val args: List<String>,
        val env: Map<String, String>,
    ) : McpServerSpec {
        override fun toString(): String =
            "McpServerSpec.Stdio(name=$name, scope=$scope, envKeys=${env.keys.sorted()}, timeoutMs=$timeoutMs, alwaysLoad=$alwaysLoad)"
    }

    /** 远程服务器（http / sse / ws）。[url] 与 [headers] 的值已展开（凭证类变量恒为空，见 McpVars） */
    data class Remote(
        override val name: String,
        override val scope: McpScope,
        override val timeoutMs: Long?,
        override val alwaysLoad: Boolean,
        val kind: McpTransportKind,
        val url: String,
        val headers: Map<String, String>,
        /** 动态头命令：project / local 来源且工作区未信任时置空（CC：helper 要等工作区信任后才许跑） */
        val headersHelper: String?,
        val oauth: McpOAuthConfig?,
    ) : McpServerSpec {
        override fun toString(): String =
            "McpServerSpec.Remote(name=$name, scope=$scope, kind=$kind, headerKeys=${headers.keys.sorted()}, " +
                "headersHelper=${headersHelper != null}, timeoutMs=$timeoutMs, alwaysLoad=$alwaysLoad)"
    }
}

/** `oauth` 对象（cc-mcp.md「Override OAuth metadata discovery」「Restrict OAuth scopes」）。加载器只解析不校验，用的时候再判 https 之类 */
data class McpOAuthConfig(
    val clientId: String? = null,
    val callbackPort: Int? = null,
    val authServerMetadataUrl: String? = null,
    /** 空格分隔的 scope 串（RFC 6749 §3.3 的格式），原样保留 */
    val scopes: String? = null,
    val clientSecret: String? = null,
) { override fun toString() = "McpOAuthConfig(***)" }

/** 一台服务器 + 它的闸门结论。[reason] 给人看（gate 不是 Enabled 时必有一句），不进模型上下文 */
data class McpServerEntry(
    val spec: McpServerSpec,
    val gate: McpGate,
    val reason: String?,
    /** 配置里引用了、环境里没设也没默认值的变量名（保留字面 `${VAR}` 的那些） */
    val unresolvedVars: List<String>,
    /** 同名被它盖住的低优先级来源（按优先级从高到低） */
    val shadowed: List<McpScope>,
)

/** 一次加载的结果。[managedExclusive]：managed-mcp.json 存在（含坏了走「失败即关」的场合）——其余来源一概没读 */
data class McpConfigSnapshot(
    /** 按名字排序 */
    val servers: List<McpServerEntry>,
    val notes: List<String>,
    val managedExclusive: Boolean,
)
