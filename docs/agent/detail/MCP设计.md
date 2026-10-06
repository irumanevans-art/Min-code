# MCP 客户端设计

> 状态：设计，2026-10-04。上位文档：`04-子系统设计.md` §4.7、`05-对标清单.md` 的 MCP 行。
> 事实依据：`research/inventory/mcp-client.md`（CC 官方文档、MCP 规范 2025-11-25 与 2026-07-28、Codex / Gemini CLI / opencode 源码、Kotlin / Java SDK 的逐项核对）。实现是自己的。

## 0. 一页结论

- **用户现有的 CC 配置原样能用**：`~/.claude.json`（user / local 作用域）、项目 `.mcp.json`、`managed-mcp.json` 与 settings 里的审批键，读法与优先级照 CC。
- **协议层自己写**，放新模块 `:agent:mcp`（只依赖 protocol、spi），不用 Kotlin SDK。理由见 §1。
- **新增一个「长寿进程」端口**：现在的 `Exec` 只有「跑完才返回」，stdio 服务器需要一个能一直读写 stdin / stdout 的进程。桌面用 `ProcessBuilder`，Android 进 proot，按进程组回收。
- **工具表按会话冻结**：会话创建时连上的服务器、它们的工具 schema 写进日志快照，会话内不变，提示缓存不断。工具多到超过窗口 10% 时改走 ToolSearch 按需加载（§5）。后连上的、`list_changed` 带来的变化**在下一回合开始时**生效（一次缓存失效，给人一条说明）。
- **默认问人**：MCP 工具一律当「有副作用」处理，Default 模式下要审批；规则写法与 CC 相同（`mcp__server`、`mcp__server__*`、`mcp__server__tool`）。服务器自称只读（`readOnlyHint`）只用来决定能不能并行，不用来免审批（规范：注解不可信）。
- **远程服务器的 OAuth 做**（2026-10-04 拍板，§10）：令牌存 Android Keystore，放在 MCP3；之前先支持「请求头带令牌」与无鉴权的远程服务器，加上全部 stdio 服务器。

## 1. 协议层：自己写，不用 SDK

| 考虑 | 用 Kotlin SDK 0.15 | 自己写 |
|---|---|---|
| 协议代际 | 只到 2025-11-25；2026-07-28（无状态、MRTR、`subscriptions/listen`、`Mcp-Method` 头）要自己补 | 两代都自己写，但只写客户端需要的部分 |
| HTTP | 引入 Ktor（第二套 HTTP 栈，与现有 `HttpTransport` 的取消、凭证不落日志等约定各管一套） | 扩展现有 `HttpTransport`：`stream()` 要能拿到响应头（`MCP-Session-Id`）、支持 SSE 的 `id` / `retry` |
| stdio | `StdioClientTransport` 只吃现成的流，不起进程；进程端口仍要自己做 | 同左 |
| OAuth | SDK 客户端里没有 | 同左 |
| 体量 | core + client ≈ 1.2 万行（含全部类型），jar ≈ 1.5 MB + Ktor | 预计 2–3 千行：JSON-RPC 收发、两代握手、tools / resources / prompts、取消与进度、分页 |
| Android | 只发 JVM 变体，未在 Android 上验证 | 只依赖 kotlinx（已在用） |

结论：SDK 能省下的只是 JSON-RPC 与类型定义，最难的几块（进程、两代协议、OAuth、与内核的取消和日志约定）都要自己做，还要多背一套 HTTP 栈。所以自己写。代价是协议细节要自己维护，用两样东西兜住：官方参考服务器（`@modelcontextprotocol/server-everything` 等）录下的真帧做回放测试；官方一致性测试套件能跑的部分接进 CI。

`04-子系统设计.md` §4.7 原来写的「官方 kotlin-sdk-client 外包一层」按本节改。

## 2. 长寿进程端口（spi）

> MCP0 已实现（2026-10-04）。下面是落地后的形状；与最初草案不同的地方在各条里标了「实现时改」。

```
interface ProcessPort {
    /** 起一个长寿进程。起不来（配额满、命令不存在、环境不可用）抛 ProcessSpawnException，消息可直接给用户看 */
    suspend fun spawn(spec: ProcessSpec): LiveProcess
    object Unavailable : ProcessPort            // HostPorts 的默认值：spawn 报「这个环境没有配置长寿进程端口」
}
data class ProcessSpec(val argv: List<String>, val cwd: GuestPath, val env: Map<String, String> = emptyMap(), val label: String)
interface LiveProcess {
    val stdout: Flow<ByteArray>      // 各自只能收集一次；从进程起来的第一个字节开始缓存，晚收集不丢；进程结束后流正常结束
    val stderr: Flow<ByteArray>      // 缓冲满了丢最旧的块（只是日志，不反压进程）；stdout 有界，不收集会塞满管道，和真管道一样
    suspend fun write(bytes: ByteArray)   // stdin 已关或进程已死抛 IOException
    suspend fun closeStdin()
    val exit: Deferred<Int>
    /** 关停：关 stdin → 等 2 秒 → 整棵后代树 SIGTERM → 2 秒 → SIGKILL（规范的 stdio 关停顺序 + Codex 的时限）。可重复调用，调用方被取消时收尾照样做完 */
    suspend fun shutdown()
}
```

- **取消**（实现时加）：spawn 中途被取消，不留进程；返回之后进程的存活**不超过调用 spawn 的协程的 Job**——该 Job 以取消或失败结束，整棵进程树被硬回收（直接 SIGKILL，不走礼貌顺序）；正常结束不管，由持有者 `shutdown()`。McpManager 因此应在自己长期存活的作用域里 spawn，而不是在一个会先结束的临时协程里。
- **实现的分工**（实现时改）：平台无关的包装（泵线程、有界缓冲、关停升级、取消回收、环境变量白名单）在 `:agent:host`（`JvmLiveProcess` / `ProcessEnv`），桌面与 Android 共用，两边只提供「怎么起进程」与「怎么回收树」。
  - 桌面：`LocalProcessPort` 放 `:agent:cli`（路径映射 `DesktopPaths` 与 Git Bash 的约定都在那里，`:agent:host` 看不到）。用 `LocalExec` 同一个 shell 与 cwd 映射。**argv 不放进命令行**：Windows 上 JDK 不转义内嵌的双引号、Git Bash 再拆一遍，带空格 / 引号 / 反斜杠的参数会碎；改为写一个临时启动脚本（POSIX 单引号转义），命令行里只剩脚本路径。回收按 `ProcessHandle.descendants()`；**Windows 上 Git Bash（msys）的 fork + exec 会让孙进程在 Windows 眼里没有父进程，`descendants()` 看不到**（实测漏 `sleep`），所以再用 msys 自己的进程表（`ps -W`）补全，启动脚本把根进程的 msys pid 写进文件作为种子。
  - Android：`ProotProcessPort` 在 `app/core/engine/min/`。与 `ProotExec` 同一份挂载快照（`MinHostGeneration.processes`）；换代后已起的进程留在起它的那一代（`LiveProcess` 自己握着进程与回收手段，关停不经过「当前一代」）。argv 作为 `exec 'a' 'b'` 交给 guest 的 bash。回收是 /proc 扫描后**自底向上**：先后代、最后才是 proot 宿主进程（基线：SIGKILL 掉 proot 后 tracee 存活；`ProcessBuilder` 不 setsid，杀进程组等于杀 App 自己，所以「进程组」在 Android 上实际是 /proc 里的后代树）。
- **环境变量**：白名单继承（HOME、PATH、LANG、LC_ALL、TERM、TMPDIR、TZ、USER、SHELL；Windows 另加 SYSTEMROOT、APPDATA、TEMP 等一批，没有它们 node / Git Bash 起不稳）+ `spec.env`（`${VAR}` 展开与 `CLAUDE_PROJECT_DIR` 由调用方 McpManager 在构造 `spec.env` 时做，端口不替它加）。**模型 key 与 Min 管理的任何凭证永远不进子进程环境**：宿主在构造端口时给一份「凭证变量名」，无论来自继承还是 `spec.env` 一律剔除，大小写不敏感（桌面：会话模型与快速模型的 key 变量名 + 两个搜索后端的 key）。Android 上 proot 本来就是 `env -i`，基础上下文里的 env 一概不继承，key 在 Keystore 里从不进环境，名单现为空。Android 的 `env -i` 还会固定加 `CI=true NO_COLOR=1 PAGER=cat BROWSER=…`，不继承 TMPDIR / TZ。
- **配额**：`HostPorts.processes` 恒套一层 `QuotaProcessPort`（内核里，一个宿主一份，所有会话与子 agent 共用），上限 `MAX_LONG_LIVED_PROCESSES = 8`——**暂定，真机测过 phantom 进程上限后再调**（全系统 32，给会话 shell 与后台任务留余量）。超额时 spawn 抛 `ProcessSpawnException`，说明里列出正在占名额的服务器；不排队。名额从预留（起进程前）占到进程退出。
- stderr：端口给的是字节；「逐行留在服务器的日志里（界面「MCP 服务器」面板可看），不当错误」是 McpManager 的事（按行切、留尾部若干行）。
- 已知限制：回收靠关停时（或取消时）取的树快照。进程自己已经退出、而它留下的孤儿在退出前没被快照到的，找不回来（Windows 的 msys 靠 PGID 能找回一部分；Linux / Android 上孤儿被 reparent 到 init）。服务器应当在 stdin 关闭时自己带走自己的孩子，规范也这么要求。

## 3. 配置、作用域与信任

读法照 CC（`mcp-client.md` §1.1–1.3）：

- 来源与优先级：local（`~/.claude.json` 里 `projects["<项目路径>"].mcpServers`）> project（项目根 `.mcp.json`）> user（`~/.claude.json` 顶层）；同名取最高优先级的**整条**定义，不按字段合并。`managed-mcp.json` 存在时独占；`managedMcpServers`、`allowedMcpServers` / `deniedMcpServers`、`allowManagedMcpServersOnly` 照 CC 语义。插件提供的服务器随插件（M5）。
- Android 上 `~` 是 rootfs 的 `/root`：用户在 Min 里装的 CC 写的 `~/.claude.json`，Min Agent 直接读同一份。
- **项目 `.mcp.json` 要两道关**：工作区被信任（同 hooks 的信任，宿主判定）**且**该服务器被批准（`enableAllProjectMcpServers`、`enabledMcpjsonServers`；`disabledMcpjsonServers` 任何层写了都生效）。未信任的项目里，入库的 `.claude/settings.json` 里的批准键不算（CC 同：克隆的仓库不能自己批准自己）。没批准的服务器不启动，会话里给一条说明，界面给「批准这个服务器」的卡片（批准写进 `.claude/settings.local.json` 的 `enabledMcpjsonServers`，与 CC 互通）。
- 环境变量展开：位置与语法同 CC；**远程服务器的 `url` / `headers` 里，凭证类变量恒为空**（CC 的清单照搬）；`headersHelper` 只在受信任时执行。
- 名字：只许 `[A-Za-z0-9_-]`；保留名照 CC。

快照：会话创建时读一次，连同解析结果写进日志（§11），会话内冻结；与权限规则、hooks、skills 同一做法。

> **落地情况**（2026-10-05，MCP1b，分支 agent/mcp-config）：本节已实现为 `:agent:ext` 的 `dev.min.agent.ext.mcp.McpConfigLoader`
> （读 managed-mcp.json / managedMcpServers / `~/.claude.json` 的 local 与 user 作用域 / 项目 `.mcp.json`，产出带闸门结论
> `enabled | needs-approval | untrusted | disabled | denied` 的服务器表，环境变量展开与凭证置空、allow / deny 管控、
> 批准键按信任筛层都在内；spec 的 toString 与 notes 不带任何值）；批准写回在 `McpApprovals`（settings.local.json 的
> `enabledMcpjsonServers` / `disabledMcpjsonServers`，与 CC 互通）。比 CC 严的两处：未信任的项目服务器**一律** untrusted
> （不采纳任何来源的批准键去放行）；managed-mcp.json 存在但读不了 / 不是合法 JSON 时**失败即关**（一台都不加载）。
> 兼容样本集 01–08 在 `agent/ext/src/test/resources/mcp-config-compat/`（20 号以后由 Flash 的样本合入后同一测试跑）。

## 4. 连接管理

- 一个 `McpManager` 挂在 `SessionHub` 上，按（服务器身份，项目根）共享连接：同一项目的多个会话（含子 agent）共用一个 stdio 进程；引用计数归零后等 2 分钟再关（来回切会话不反复起进程）。
- 启动：会话创建时并发连接（stdio 并发上限 3，远程 20，同 CC）；**不阻塞会话创建**，最多等 5 秒（CC 的 `MCP_CONNECT_TIMEOUT_MS` 默认值）收集已就绪的服务器进首个快照，其余后台继续连，就绪后按 §5.3 在下一回合加入。
- 两代协议：stdio 先发 `server/discover` 探测，失败或超时回退 `initialize`；HTTP 先发现代请求，400 体里没有可识别的现代错误就回退；判定结果按（命令行 / 源）缓存。首期（MCP1）先只实现 2025-11-25 这一代，但握手与请求发送从一开始就按「代」分开，加现代代不改上层。
- 失败与重连：HTTP / SSE 首次连接瞬时错误重试 3 次（1、2、4 秒）；运行中掉线指数退避最多 5 次后标 failed。**stdio 掉线自动重启一次**（规范 2026-07-28 的 SHOULD；CC 与三家开源都不重启——这是 Min 做得更好的点），再掉就标 failed，等用户在面板里点「重连」。在途的调用作为失败返回给模型（「服务器重启了，结果未知，不要盲目重试有副作用的操作」）。
- 状态：`pending / connected / failed / needs-auth / disabled / not-approved`，经 Hub 的状态流给界面。

## 5. 工具：命名、快照与缓存

### 5.1 命名

- 全名 `mcp__<server>__<tool>`，两段都把 `[A-Za-z0-9_-]` 以外的字符换成 `_`。
- **长度上限 64**：Min 是多供应商的，OpenAI 系函数名上限 64，取各家最严。超长时截断并追加原始身份的 8 位哈希；清洗后撞名同样追加哈希（Codex 的做法：排序后确定性分配）。路由用原始的 server / tool 名，不靠反解全名。
- 权限规则、hooks matcher 按全名匹配，与 CC 一致。

### 5.2 快照（会话内冻结）

会话首个快照包含就绪服务器的工具（名字、说明、inputSchema）与服务器 `instructions`，写进日志：

- 系统提示里加一段「MCP 服务器说明」（各服务器的 `instructions`，每条截到 2048 字，同 CC）。
- 工具说明同样截到 2048 字；inputSchema 不合法的工具排除并告诉模型排除了哪些（CC 同）；根级 `anyOf / oneOf / allOf` 压平成单个 object（CC 同）。

### 5.3 什么时候进工具表

工具表是稳定前缀的一部分（Anthropic 的缓存顺序是 tools → system → messages，改工具表 = 整段缓存失效）。所以：

- **全量模式**（默认）：MCP 工具定义总量不超过窗口的 10% 时，首个快照里的工具全部进工具表，排在内置工具之后，会话内不变。
- **按需模式**：超过 10%（或用户设 `ENABLE_TOOL_SEARCH=true`）时，工具表里只多一个 `ToolSearch`；系统提示列出各服务器的工具名。模型用 ToolSearch 查到的工具，**在下一次请求时追加到工具表末尾**（一批一次缓存失效）。CC 的做法依赖 Anthropic 专有的 `tool_reference` 块，Min 要跨供应商，所以用「追加」这个各家都支持的办法；对 Anthropic 系模型以后可以再接 `tool_reference` 省掉这次失效。
- **变化**（后连上的服务器、`notifications/tools/list_changed`、用户在面板里开关服务器）：排进待生效列表，**下一回合开始时**一次性生效，写一条快照事件与一条说明（「新增 github 的 12 个工具」）。不在回合中途改工具表。

## 6. 执行与权限

- 每个 MCP 工具在内核里是一个普通 `Tool`（适配器），走现有的审批、hooks、结果预算、日志流程。
- traits：`readOnly = false`（审批口径），`concurrencySafe = readOnlyHint`（只影响能不能并行），`resumePolicy = Unknown`（进程被杀后不重放）。
- 默认问人；规则照 CC：`mcp__server` / `mcp__server__*` 放行整个服务器，`mcp__server__tool` 单个工具；allow 规则里 server 段不能有通配；deny 规则命中的工具从工具表里去掉（CC 同）。`_meta["anthropic/requiresUserInteraction"]` 为真的工具任何模式都问、不给「总是允许」。
- 超时：调用空闲超时（既无响应也无 progress）stdio 30 分钟、远程 5 分钟（CC 默认）；配置里的 `timeout` 是硬上限。超时发 `notifications/cancelled`。
- 中断：用户 ■ 时发 `notifications/cancelled`，调用以「被中断」收尾。

## 7. 结果

- `content`：text → 文字；image → 图片（模型不支持图片时换成一行说明 + 附件路径）；audio → 附件路径；resource_link → 一行「资源：名字（uri）」；嵌入资源 → 文本内容或附件。
- `structuredContent`：content 里没有等价文本时，序列化成 JSON 文本给模型。
- `isError: true` → 工具结果标错误，原文交给模型（规范：让模型自我纠正）。
- 上限：文字部分默认 25,000 token（`MAX_MCP_OUTPUT_TOKENS` 可调），超出走现有的 `ResultBudget`：落附件、给预览与路径。图片单独计。
- 外部内容防注入：结果包在 `<mcp-result server="…">` 里（同子 agent 报告的转义做法），正文里出现的同名标签转义。

## 8. resources 与 prompts（MCP2）

- 模型侧工具：`ListMcpResources`、`ReadMcpResource`（与 CC 同名）。
- 输入里的 `@server:uri` 引用：发送前读出内容作为附件（界面的 `@` 补全在 M2-B 之后接）。
- prompts → 斜杠命令 `/mcp__<server>__<prompt>`，参数按空白切词；接进现有 `SlashCommands`。

## 9. 客户端能力

- **elicitation**（声明 form + url）：form → 现有的提问卡（`QuestionAsked`，界面与通知都能答）；url → 卡片展示完整 URL 与域名，用户点了才用 Custom Tab 打开（规范：未经同意不打开、不预取）。卡片写明是哪个服务器在要。
- **roots**：回应会话 cwd（以后加上 additional dirs），集合变化发 `list_changed`。
- **sampling**：不声明（CC 文档也没有）。

## 10. OAuth（已拍板：做）

远程服务器回 401 时需要 OAuth 2.1：发现（RFC 9728 → RFC 8414）、注册（预置 → CIMD → 动态注册）、PKCE S256、`resource` 参数、`iss` 校验、令牌刷新。在 Android 上的做法会是：Custom Tab 打开授权页，回调走 App 内一个只监听 127.0.0.1 的临时端口，令牌存 Android Keystore（与模型 key 同等对待：不进 rootfs、日志、事件）。

**这意味着 Min 自己保存第三方服务的 OAuth 令牌。** 与「Min 不碰 Claude 订阅凭证」是两件事（后者仍然不碰）。

2026-10-04 拍板：**做**（MCP3），令牌存 Keystore，界面上能逐个服务器「退出登录」。桌面 CLI 的令牌存放另议（不进仓库、不进日志，至少用系统凭证库）。
MCP1 / MCP2 期间远程服务器只支持请求头带令牌（用户自己在配置里写 `Authorization`，或 `headersHelper`）与无鉴权；回 401 时状态标 `needs-auth`，说明「OAuth 登录还没做」。

## 11. 事件与格式

- `mcp_servers_loaded`：会话创建时的服务器快照（名字、作用域、传输、状态、说明；**不含**请求头、环境变量的值）。
- `mcp_tools_snapshot`：生效的 MCP 工具（全名、原始 server/tool 名、说明、inputSchema、traits）。会话内每次生效的变化追加一条（整份替换）。请求只凭日志重建：工具表 = 内置工具 + 最后一份快照。
- 工具调用本身走现有的 `ToolPlanned` / `ToolOutcome`，不新增。
- 全部登记 `agent/store/FORMAT.md`。

## 12. 分期与分工

| 期 | 内容 | 谁 |
|---|---|---|
| MCP0 | 长寿进程端口（spi + 桌面实现 + proot 实现，进程组回收、配额）与集成测试 | GPT（Android 侧要真机或模拟器验证）；**已完成**（桌面真进程集成测试、模拟器 `op proc-probe` 验收通过；配额 8 暂定，待真机） |
| MCP1a | 协议层 `:agent:mcp`：JSON-RPC、2025-11-25 握手（按「代」分开）、stdio / Streamable HTTP / 旧 SSE 三种传输（含会话 id、SSE 续传）、tools/list 分页、tools/call、取消、进度、ping、list_changed；`HttpTransport` 加原样响应的流式接口；官方参考服务器录真帧做回放测试 | GPT（2026-10-05 派出） |
| MCP1b | 配置读取与信任 / 批准（§3）：`:agent:ext` 的 `McpConfigLoader`，产出带闸门结论的服务器表；批准写回 `settings.local.json`；兼容样本集（格式两单共用） | GLM 5.3 写加载器、Flash 写样本（2026-10-05 派出） |
| MCP1c | McpManager（§4）、工具适配器、命名、快照与事件、全量模式（§5.1–5.3 全量部分）、权限与结果（§6–7）、内核与两个宿主接线 | O。**底子已合入（2026-10-05）**：`mcp_servers_loaded` / `mcp_tools_snapshot` 事件与折叠、工具表 = 内置 + 快照（MCP 在后）、系统提示的「MCP 服务器说明」段、变化排到回合开头生效、命名（64 字符 + 哈希）与 schema 整形、工具适配器（默认问人、只读提示只管并行、`requiresUserInteraction` 恒问）、整工具 deny 从工具表去掉、子 agent 按定义里的 `mcp__` 写法筛、分身与子 agent 继承快照；调用经内核内部的 `McpCaller`。**McpManager 已合入（2026-10-06）**：连接按（项目、服务器、端点）共享、无人用后留 2 分钟；新会话不等连接，首份快照在第一回合开头写（最多等到接上后 5 秒）；晚到与 list_changed 排到下一回合；stdio 掉线重启一次；**服务器掉线不改工具表**（保留最后一次的工具与说明，调用时说「现在不可用」——比去掉再加回少两次缓存失效）；结果按 §7 转换并包 `<mcp-result>`；两个宿主接了 HTTP。**端到端验收（2026-10-06，桌面 Sonnet）**：桌面 CLI + 官方参考服务器全项通过，复核修了 9 处。随后拍板：首份快照等待交互 5 秒、`-p` 30 秒（`KernelDeps.mcpFirstSnapshotWaitMs`）；连接与单次请求超时桌面 30 秒、Android 120 秒（`mcpConnectTimeoutMs`）；模型不收图片时图片在组请求处集中换成文字（`ImageDegrade`，不在 MCP 结果里各自判断）；进度不落日志；headersHelper 遇 401 重跑放 MCP3；配置读取失败落一条只带异常类名的说明。**剩下**：模拟器验收（模拟器无外网、rootfs 无 node，改用纯 bash 的 stdio 桩服务器，已派桌面 Sonnet）、界面面板（随 B7） |
| MCP2 | resources / prompts（§8）、按需模式与 ToolSearch（§5.3）、elicitation / roots（§9） | GPT（2026-10-06 派出，`agent/mcp2`） |
| MCP3 | OAuth（§10，已拍板做） | 待定 |
| MCP4 | 2026-07-28 现代协议（discover、MRTR、`subscriptions/listen`、新的请求头）与两代自动探测 | GPT |
| — | 界面：服务器面板（状态、工具数、stderr、重连、开关）、批准卡片、elicitation 卡片 | 随 M2-B B7 |

## 13. 已知限制（先写下来）

- proot 里 `npx` / `uvx` 冷启动慢（文件 IO 比宿主慢 26–50 倍，见调研 §5.2），首次启动可能超过 30 秒（Android 的连接超时因此给 120 秒）；交互时首个快照只等 5 秒，慢的服务器在下一回合才加入。
- 需要图形环境的服务器（Playwright 默认有界面、chrome-devtools-mcp 只认 Chrome）在 proot 里跑不起来，除非它们支持 headless 或连接已有浏览器。
- `ws` 传输（CC 只在 `.mcp.json` 里支持）首期不做。
