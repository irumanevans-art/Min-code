# M5：git worktree 与会话 cwd

依据：公开 Claude Code worktrees 文档与 research/inventory/raw/mcp 中保存的 tools/hooks/sub-agents/CLI 原文；不读取 CC 私有实现。

## 执行与路径

Git 只走 HostPorts.exec：桌面 Git Bash，Android proot。KernelDeps.gitPath 区分 git native argv 与 hookShellPath，防止 Windows 的 /c 路径被 native Git 当成 C:/c。固定 Git 控制变量作为命令内赋值，保持 Android Exec 拒绝任意 env 的凭证边界。工作副本位于仓库根 .claude/worktrees/<name>，分支 worktree-<name>；只改 info/exclude，不制造待提交的 .gitignore 改动。

默认基线为缓存 origin/HEAD，无远端时 HEAD；worktree.baseRef: head 固定当前 HEAD。FETCH_HEAD 不存在或过期 24 小时时尽力 fetch origin，5 秒预算，失败沿用缓存。创建、状态与清理统一禁用本地过滤器、Git hooks、fsmonitor，避免只读探测也执行仓库里的程序。

EnterWorktree 支持 name 或 path；已有路径必须是登记过的 linked worktree，属于同仓库或其嵌套仓库。主 checkout、跨仓库、绝对路径中的点段以及仓库内穿过 symlink/junction 的路径被拒绝。进入隔离状态后只能在默认 worktrees 目录内切换。已有目录可显式 remove，但已有分支不由内核删除；内核创建的分支才随干净目录清理。

ExitWorktree 默认 keep；remove 先检查 tracked/untracked/submodule 状态与创建基线之后的提交，任何工作都拒绝并提示 keep。审批规则和 bypass 不能越过主 checkout 隔离约束。Enter/Exit 都不是只读工具，default 模式问人；子 agent 固定 cwd 时工具表移除 Exit。

## 日志与工作区

cwd_changed 原子冻结旧目录、新目录、worktree binding、新规则与附加目录、新发现的记忆。created.cwd 保持会话起点，稳定提示前缀不改。工具中切换时提醒挂当前助手消息的工具结果，下一次模型请求立即看到；空闲切换时挂下一条输入。worktree_finished 记录清理/保留结局，cwd_watch_paths_changed 冻结动态监视路径。

- 新 cwd 是写权限的隐式工作区，旧 cwd 降回宿主 Rootfs 分类；Device 仍优先。显式 additionalDirectories 是绝对授权，保留并合并新项目设置中的目录。
- 主会话重新发现 CLAUDE.md 与按需规则；切目录新增的记忆另存 cwdMemory，子 agent 继承父记忆及这些新指令，默认 worktree 根的 CLAUDE.md/.claude/rules 不再重复加载。自动记忆沿用起点项目，父子共享。
- cwd 切换重读项目权限规则与检查点设置。hooks、skills、agent 目录、MCP 连接配置继续锚定会话起点，不因进入模型创建的目录自动启用另一套可执行配置。
- MCP roots 是连接使用者当前 cwd 与显式附加目录的并集；返回目录变更前同步发 roots/list_changed。重开连接仍以起点配置启动，roots 从日志当前目录折叠。
- 新检查点的 store 与 root 都取当前 cwd；另一个 cwd 的检查点拒绝恢复代码，须先返回其目录。对话回退不回滚会话 cwd。
- 重开根会话验证绑定；目标不见了或无法验证且原 cwd 仍存在时落事件返回原目录并显示说明。

## Hooks 与子 agent

WorktreeCreate 完全接管创建，允许非 Git 目录。command 使用 ANSI 清理后的最后非空 stdout 行；HTTP 使用匹配事件的 hookSpecificOutput.worktreePath。输出可相对 cwd；多个处理器必须成功并返回同一路径。

WorktreeRemove 读取原始运行结果：exit 0 即成功，即使目录还在，也不再执行 Git 清理；非零但目录已消失也成功；否则保留并禁止 git fallback。JSON continue/systemMessage 被丢弃。清理命令从 originalCwd 运行，避免 Windows 占用待删目录；hook JSON cwd 仍是会话当前位置。配置了清理 hook 时，目录与分支的实际清理由 hook 自己管理。Git hook 目录可检查改动；非 Git hook 目录无法可靠判定有无工作，自动清理保守保留，显式 remove 交给用户配置的 hook。

CwdChanged 在已持久化的目录变更后触发，输入 old_cwd/new_cwd，common.cwd 是新目录，CLAUDE_PROJECT_DIR 保持起点。continue 不阻断，systemMessage 只通知人；watchPaths 替换持久化列表，FileChanged 监视执行器仍属后续功能。

frontmatter isolation: worktree 创建固定 cwd 子会话，继承父指令。结束时无改动删除目录与内核创建的分支；有改动、提交或无法检查则保留。前台结果、后台报告均携带路径、分支和清理结局。普通子 agent 在父 worktree 中运行时继承固定绑定，不拥有父目录的清理权。

## CLI 与 Android

--worktree [name] / -w [name] 在第一条请求前进入；无名默认生成安全唯一名字。交互退出先停整棵会话，再检查：无名干净会话自动删除，命名或有改动询问 keep/remove，拒删时保留；-p 保存绑定以便 resume。非 Git 创建失败退出码 1。恢复绑定遵循日志，不再另建目录。

Android 使用同一内核与既有 proot Exec。AgentSessionState.worktree 暴露名字、路径、分支、原 cwd、pinned；界面与按钮由后续 UI 单实现。

## 范围

本单不实现 PR 快捷入场、tmux、.worktreeinclude、孤儿 worktree sweep、FileChanged 监视器、App 操作界面。普通 Bash 的 cd 仍是该命令进程的局部 cwd；会话持久 cwd 由 Enter/Exit 或空闲的 changeSettings(cwd) 更新。隔离是独立 Git 工作副本与工具门禁，不是进程文件系统沙箱。

## Bash 的 cd 跨命令保留（2026-10-11）

上面「范围」里说的「普通 Bash 的 cd 是局部 cwd」已被取代，口径是 CC tools-reference「What persists between commands」：

- 两个目录分开记。会话目录（`settings.cwd`，由 `cwd_changed` 改）管权限范围、记忆、worktree；shell 当前目录（`SessionState.shellCwd`，由 `shell_cwd_changed` 改）只决定下一条 Bash 从哪开始。`cd` 进子目录不改会话目录、不缩小权限范围；`cwd_changed` 一律清空 shell 目录。
- 目录由宿主在命令跑完后报告（`ExecResult.Exited.endCwd`）：宿主把脚本整个 `eval` 进一层探针（`spi/ShellCwd.kt`），探针把 `pwd` 写进宿主自己的临时文件、不走输出流。所以输出、日志、后台通知里没有标记，截断也丢不了；脚本里 `exit` / `exec` / `set -e` 失败时探针没跑，视为没换目录；超时与转后台不读。桌面 Windows 用 `pwd -W`（普通 `pwd` 会按 msys 挂载表改名），`DesktopPaths.guestOfShell` 映射回 guest 路径；Android proot 原样。
- 内核（`ShellCwd.kt` 的 `runTrackedShell`）判范围：终点先折 `..`、再交宿主解符号链接，落在会话目录或附加目录之内才带过去，并记**真实路径**；出界则重置回项目目录，在那次结果末尾附 `Shell cwd was reset to <dir>`。起点目录没了或已出界（附加目录被撤）时**不执行**该命令：权限分析是按 shell 目录做的，换个目录跑就成了没分析过的命令。
- 权限分析：Bash 命令里的相对路径与凭证目标按 shell 目录解析（`PolicyContext.shellCwd`，仅对 Bash）；规则里的相对写法与工作区范围仍以会话目录为准。worktree 隔离守卫同样从 shell 目录起算。
- 子 agent 从不带；`CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR` 为真值时每条命令都从项目目录起；环境变量本来就不带。
- shell 目录有效变化时触发 `CwdChanged` hook（`old_cwd` / `new_cwd`），hook 通用输入的 `cwd` 跟着 shell 目录走（原文「cwd follows Claude」）。`CLAUDE_ENV_FILE` 见 `hooks设计.md` 末节。

## Monitor 与 Skill 预执行的起点目录（2026-10-12）

- Skill 的 `!` 预执行：原文（skills 页「How injected commands run」）明说「run each command in the session shell's current working directory」，与 Bash 共用工作目录。实现 `shellStart()`：主会话从 shell 目录起，子 agent 与 `CLAUDE_BASH_MAINTAIN_PROJECT_WORKING_DIR` 为真时从会话目录起；带过来的目录失效（被删、出了范围）时**先重置再判定**，判定读到的 shell 目录与真正起进程的目录才是同一个。预执行里的 `cd` 不带回 shell 目录。
- Monitor：原文只说权限与 Bash 同一套、没写目录，按「同 Bash」处理：模型起的 watch 从 shell 目录起（起点失效就拒绝这次启动，让模型重发，与 Bash 同口径）；插件声明的常驻 watch 是会话级配置，仍从会话目录起。权限分析把 Monitor 与 Bash 一样按 shell 目录解析相对路径与凭证目标（`PolicyEngine.runsShellCommand`），否则「分析的目录」与「实际跑的目录」是两个。
