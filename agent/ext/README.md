# :agent:ext

CC 兼容配置发现与加载、hooks 执行编排、skills/agents/commands/plugins、MCP 客户端（02-总体架构.md §2）。

- 允许依赖：protocol, spi
- 设计：docs/agent/04-子系统设计.md §4.7（扩展：Claude Code 生态兼容）、docs/agent/detail/MCP设计.md §3（MCP 配置）

## 现状

已实现的只有「读 + 解析 + 合并」（包 `dev.min.agent.ext.config`）：用户把现成的 `~/.claude/` 与项目 `.claude/` 原样放进 rootfs，
读出和 Claude Code 一样的结果。skill 调用与斜杠展开见下（`skills` 包）；hooks 执行（`hooks` 包，含 type:http）、
MCP **配置**读取（`mcp` 包，MCP1b：`McpConfigLoader` / `McpApprovals`，连接是 MCP1a / MCP1c 的事）已做；插件、output styles 还没有。

入口：`ClaudeConfigLoader.load(fs, cwd, home, managedPath)` → `ClaudeConfig(settings, skills, commands, agents, notes)`；
只要 settings 用 `SettingsLoader.load(...)`。全部经 `WorkspaceFs` 端口读文件，只读，不抛异常——
读不了、解析不了的文件或键被跳过，并在 `notes` 里记一条给人看的说明（哪个文件、哪个键、为什么）；文件不存在不算错误。

- **settings**：managed（`/etc/claude-code/managed-settings.json` + `managed-settings.d/*.json`）、`~/.claude/settings.json`、
  `<cwd>/.claude/settings.json`、`settings.local.json`（含 git 仓库根那份）。优先级 managed > local > project > user；
  列表合并、标量高层覆盖、安全键取最严；`ruleSpecs()` 产出权限规则**原文** + 来源层 + `/x` 路径规则的锚点目录，规则语法留给 `:agent:policy`。
  每层保留原始 JSON（`SettingsLayer.raw`、`MergedSettings.raw(key)`），hooks 原样保留。
- **skills / commands / agents**：`SkillDef` / `CommandDef` / `AgentDef`，字段照 CC 文档；同名覆盖的优先级见各 Loader 的注释
  （skills / commands：managed > user > project，skill 胜过 command；agents：managed > project > user）。
- **frontmatter**：`FrontmatterParser`，一个够用的 YAML 子集（没有 YAML 库）；不认识的语法不猜，记 note。

## 模型看到什么

配置读取本身不进模型上下文（`notes` 只给人看）。Skills 运行时（包 `dev.min.agent.ext.skills`，第一期）让 skills / commands 被用上：

- `SkillArguments`：参数替换。`$ARGUMENTS`、`$ARGUMENTS[N]`、`$N`、`$名字`（frontmatter `arguments`），shell 风格引号；越界下标原样留着；
  没有占位符收到参数时末尾追加 `ARGUMENTS: 原文`；`\$1` 转义；参数值不递归展开。边角取自 Claude Code 2.1.286 二进制（单趟扫描替代它的哨兵字符，结果一致）。
- `SkillPrompt.render`：skill 前加 `Base directory for this skill: <目录>`，参数替换之后再代入 `${CLAUDE_SKILL_DIR}`、`${CLAUDE_PROJECT_DIR}`、
  `${CLAUDE_SESSION_ID}`、`${CLAUDE_EFFORT}`；command 没有目录头。模型调 Skill 工具与用户敲 `/名字` 共用它。
- `SlashCommands.expand`：`/名字 参数` → 要发给模型的内容，或 NotACommand（含 `/etc/hosts` 这类路径）/ Unknown / NotInvocable。
  skill 与 command 同名 skill 赢；`user-invocable: false` 不能斜杠调；`disable-model-invocation` 只挡模型。
  叠放 `/a /b 参数`：最多再叠 5 个，遇到未知词、不可调的或 fork 的 skill 就停，其后的文字是参数。
- `SkillFiles.readBody`：读定义文件此刻的正文（清单快照里没有正文）。

清单进系统提示、Skill 工具与快照见 :agent:context / :agent:tools / :agent:kernel。

## token 代价

本模块自己不产生 token。每个可被模型调用的 skill 在系统提示里常驻一行（名字 + description + when_to_use，合计截断于 1,536 字符，
整份清单受窗口 1% 的预算约束，见 :agent:context）；commands 同样列出；被禁止模型调用的 skill 不占常驻 token；正文只在被调用时进上下文。

## 缓存影响

配置在会话创建时读一次、冻结成 `skills_loaded` 快照（与记忆同一套做法），会话中改了 `.claude/` 下的文件不改写稳定前缀，重新打开会话也不重读；
子 agent 继承父会话的快照。正文不进快照，调用时才读（所以 Skill 工具返回的是调用那一刻磁盘上的正文，清单本身仍是冻结的）。

## 已知限制

- **不读的来源**（settings 而言）：`claude --settings`、MDM / 注册表 / 服务端下发的 managed 设置（只读 managed 文件与 `managed-settings.d`，
  不实现 `managedSourcesBehavior`）、`CLAUDE_CONFIG_DIR`（家目录由调用方传入）、`--agents`、插件目录、`--add-dir`、
  嵌套的 `<子目录>/.claude/skills`（CC 在读到那里的文件时才惰性加载）、output-styles。
  `~/.claude.json` 与 `.mcp.json` 的 **MCP 部分**由 `mcp` 包读（MCP设计 §3），settings 部分仍不读。
- **信任**：这里不做 workspace trust。项目层的 hooks、`allowed-tools`、`env` 在信任之前就已被读出——**执行方必须先过信任再用**
  （CC 有过克隆仓库里的 hooks 在信任前运行的漏洞，见调研报告）。managed 文件存在却读不了 / 不是 JSON 时 CC 拒绝启动，这里只置
  `SettingsSnapshot.managedPolicyBroken`，由宿主决定。
- **与 CC 行为的差异或不确定处**：local 设置在 git worktree 里 CC 用主检出的根，这里 `.git` 是文件、读不出主检出，只当 worktree 自己的根；
  CC 对仓库根文件属主的检查端口做不到，没做；managed 层 `/x` 规则的锚点（取 managed 文件目录）、managed 下的 `.claude/skills|agents`
  位置、commands 在 user 与 project 之间的优先级，文档都没写，是照类比做的选择；frontmatter 里 YAML 不合法时整个 frontmatter 被丢（照 skills 文档的说法），
  真实的 CC 可能更宽容（例如对未加引号的 `: ` 自动加引号重试），没有对着 CC 二进制验证。
- **解析边界**：settings 按严格 JSON 读（不接受注释与尾逗号）；frontmatter 不支持锚点 / 别名 / 标签，重复键与未加引号的 `: `
  按坏 YAML 整体丢弃（与 js-yaml 等主流解析器一致）；
  单个定义文件上限 1 MiB；符号链接目录在 commands / agents 的递归里不跟随（skills 的目录链接跟随一层）。
- **只校验形状不校验语义**：规则文本不解析（交给 policy，解析失败由调用方记 note）；hooks 的事件名与结构、`model` / `effort` 的取值不校验。
- 读一次、不监视文件变化。
- **Skills 第一期没做**：`allowed-tools` / `disallowed-tools` 的临时预批准与移除、`context: fork`（当作内联展开）、`model` / `effort` 覆盖、`hooks`、
  `paths` 条件激活、嵌套目录 `<子目录>/.claude/skills` 的惰性加载、插件 skills、`skillOverrides` 设置、`` !`命令` `` 的动态上下文注入（标记原样留给模型）、
  `disableSkillShellExecution`、auto-compact 后重挂已调用的 skill、清单按「调用次数」排序（会话里没有统计，按清单顺序）。
- 斜杠叠放时多个展开结果拼成一条消息（空行隔开），Claude Code 是几条消息；没写参数时 `$ARGUMENTS` 展开成空串（文档没写这一点，按「没有参数 = 空」推）。
