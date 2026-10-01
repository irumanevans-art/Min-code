# :agent:context

提示装配、记忆发现、压缩流水线、缓存规划、循环检测（02-总体架构.md §2）。

- 允许依赖：protocol, spi, llm
- 设计：docs/agent/04-子系统设计.md §4.5（上下文与缓存）、§4.6（记忆）

## 模型看到什么
SystemPrompt 与 RequestAssembler 装配 profile、环境、日志历史和工具。系统提示分段：身份与规矩（所有会话相同）、
环境（会话开始时定下）、项目说明（记忆文件，只在有记忆时出现）。

项目说明来自会话创建时写进日志的 `MemoryLoaded` 快照（`MemoryLoader` 只经 `WorkspaceFs` 读文件），顺序宽 → 窄、全部拼接：
`~/.claude/CLAUDE.md` → `~/.claude/rules/` → 从 `/` 到 cwd 每层的 `CLAUDE.md`、`.claude/CLAUDE.md`、`AGENTS.md`、
`.claude/rules/`（递归、按名字排序、去 frontmatter）、`CLAUDE.local.md`。`@` 导入相对包含它的文件解析，`~/` 指主目录，最多 4 跳，
代码块与行内代码里的不算，被导入的文件排在导入它的文件之前。每个文件一小节，格式照 Claude Code：
`Contents of <路径> (<来源说明>):` 加正文。截断处有一行英文说明，模型可自己 Read 原文。加载说明（跳过了什么、为什么）只在日志里给人看。

## token 代价
system / tools 每次请求都带。规矩一段约 1 000 字符。项目说明：固定引言约 300 字符（约 70 token），每个文件的小节标题约 80 字符
（约 20 token），加正文；正文上限单文件 20 000 字符、合计 50 000 字符，预算用完后的文件只留一行「未加载」说明。
token 数按英文约 4 字符/token 估算、未用分词器实测；**中文大约 1 字 1 token 上下，同样字符数的中文记忆要贵 3–4 倍**。
尚无 token 估算与压缩流水线。

## 缓存影响
固定环境、模型与 schema 保证同配置前缀稳定；cacheKey 来自会话，longTtl=true。记忆属于稳定前缀：会话内冻结，
`SessionRuntime.open` 不重读磁盘，重启后系统提示逐字不变、前缀指纹不变（`MemorySessionTest`）。
记忆段排在最后，有没有记忆不改前两段一个字节。会话中途改了 CLAUDE.md 对本会话不生效，新会话才读到；
以后的显式刷新会追加一条 `MemoryLoaded`，前缀在那一次失效一次。改提示措辞要同步 `SystemPromptTest` 的快照。

## 已知限制
- 按需加载没做：cwd 之下子目录的 CLAUDE.md、带 `paths:` 的规则（后者跳过并记一条说明）。
- 工作区与 `~/.claude` 之外的导入一律跳过并记说明（CC 会弹批准框；CC 对用户级文件里的导入不问直接信任，Min 也跳过）。
- AGENTS.md 与 CLAUDE.md 同层都读（相当于 CC 的 `claude-md-and-agents-md`；CC 默认有 CLAUDE.md 就不读 AGENTS.md）；
  `.claude/AGENTS.md` 不读。
- 没做：企业策略 CLAUDE.md、auto memory（`MEMORY.md`）、`claudeMdExcludes`、`--add-dir`、HTML 注释剥离、
  缩进代码块与跨行行内代码里的 `@`（仍会被当成导入）。
- 端口不暴露链接解析后的路径：`CLAUDE.md -> AGENTS.md` 这类链接靠「内容相同就不再收」去重；`~/.claude` 范围按字面路径判定；
  规则目录里指向目录的符号链接不跟（认不出成环）。
- 上限按字符不按 token；合计超限时被截掉的是排在最后、也就是最具体的文件。
- 世界状态增量、压缩（含压缩后重挂记忆）、缓存规划和循环检测尚未实现；配置版本 / 前缀未完整持久化。
