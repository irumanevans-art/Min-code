# :agent:ext

CC 兼容配置发现与加载、hooks 执行编排、skills/agents/commands/plugins、MCP 客户端（02-总体架构.md §2）。

- 允许依赖：protocol, spi
- 设计：docs/agent/04-子系统设计.md §4.7（扩展：Claude Code 生态兼容）

## 现状

已实现的只有「读 + 解析 + 合并」（包 `dev.min.agent.ext.config`）：用户把现成的 `~/.claude/` 与项目 `.claude/` 原样放进 rootfs，
读出和 Claude Code 一样的结果。hooks 执行、skill 调用、MCP 连接、插件、output styles 都还没有。

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

这一单：什么都看不到。产物还没有接进请求装配，`notes` 也只给人看、不进模型上下文。

以后接线时的约定（尚未实现，此处只是设计意图）：skills 只把「名字 + 描述」常驻进上下文，调用时全文作为一条消息留驻；
`disable-model-invocation: true` 的 skill 连描述都不进；子 agent 的描述进 Agent 工具的说明；
commands 只进斜杠菜单；settings 里的 permissions 交给 policy，env / model / outputStyle 进会话配置。

## token 代价

这一单：0。以后：每个可被模型调用的 skill 常驻「名字 + description + when_to_use」，合计在清单里截断于 1,536 字符（CC 的上限，装配时截，
这里保留原文）；子 agent 的 description 同理；commands 与被禁止模型调用的 skill 不占常驻 token。

## 缓存影响

这一单：无（没有进入请求）。以后：配置在会话创建时读一次、冻结（与记忆文件同一套做法），会话中改了 `.claude/` 下的文件不改写稳定前缀，
要让模型知道就在末尾追加一段；settings 变化不重排工具表。

## 已知限制

- **不读的来源**：`claude --settings`、`~/.claude.json`、MDM / 注册表 / 服务端下发的 managed 设置（只读 managed 文件与 `managed-settings.d`，
  不实现 `managedSourcesBehavior`）、`CLAUDE_CONFIG_DIR`（家目录由调用方传入）、`--agents`、插件目录、`--add-dir`、
  嵌套的 `<子目录>/.claude/skills`（CC 在读到那里的文件时才惰性加载）、output-styles、`.mcp.json`。
- **信任**：这里不做 workspace trust。项目层的 hooks、`allowed-tools`、`env` 在信任之前就已被读出——**执行方必须先过信任再用**
  （CC 有过克隆仓库里的 hooks 在信任前运行的漏洞，见调研报告）。managed 文件存在却读不了 / 不是 JSON 时 CC 拒绝启动，这里只置
  `SettingsSnapshot.managedPolicyBroken`，由宿主决定。
- **与 CC 行为的差异或不确定处**：local 设置在 git worktree 里 CC 用主检出的根，这里 `.git` 是文件、读不出主检出，只当 worktree 自己的根；
  CC 对仓库根文件属主的检查端口做不到，没做；managed 层 `/x` 规则的锚点（取 managed 文件目录）、managed 下的 `.claude/skills|agents`
  位置、commands 在 user 与 project 之间的优先级，文档都没写，是照类比做的选择；frontmatter 里 YAML 不合法时整个 frontmatter 被丢（照 skills 文档的说法），
  真实的 CC 可能更宽容（例如对未加引号的 `: ` 自动加引号重试），没有对着 CC 二进制验证。
- **解析边界**：settings 按严格 JSON 读（不接受注释与尾逗号）；frontmatter 不支持锚点 / 别名 / 标签与块标量的缩进指示符；
  单个定义文件上限 1 MiB；符号链接目录在 commands / agents 的递归里不跟随（skills 的目录链接跟随一层）。
- **只校验形状不校验语义**：规则文本不解析（交给 policy，解析失败由调用方记 note）；hooks 的事件名与结构、`model` / `effort` 的取值不校验。
- 读一次、不监视文件变化。
