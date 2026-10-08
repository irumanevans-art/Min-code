# :agent:tools

内置工具实现（只用 spi 端口，不碰真实文件系统）（02-总体架构.md §2）。

- 允许依赖：protocol, spi, policy
- 设计：docs/agent/04-子系统设计.md §4.3（工具与执行）；docs/agent/03-核心契约.md §5（工具）
- 规格：docs/agent/detail/内置工具规范.md

已实现：Read、Write、Edit、NotebookEdit、Glob、Grep、Bash、AskUserQuestion、EnterPlanMode、ExitPlanMode、Agent、SendMessage、TaskStop、Skill。共用零件在 `ToolSupport.kt`；
测试用的假端口（内存文件系统、脚本化搜索与进程、可预置 lastSeen 的上下文）在 `src/test/.../ToolFakes.kt`，Edit 的测试可直接复用。

## 模型看到什么

每个工具给模型三样东西：名字、入参 schema（只用 type / properties / required / description / enum）、一段中文说明。

| 工具 | 入参 | 模型拿到的结果 |
| --- | --- | --- |
| Read | `file_path`（绝对）、`offset`（从 1 起）、`limit`（默认 2000） | `cat -n` 风格：行号右对齐 6 位 + 制表符 + 内容；单行超 2000 字符截断；没读完时末尾一行说明总行数与已显示范围。图片返回一句说明 + base64 图；`.ipynb` 渲染成 `<cell id index type>` 块（源码 + `<output>` 块；输出去 ANSI 色码、回车覆盖的进度条只留末版、单块超 8000 字符留头尾；png / jpeg / gif / webp 输出最多内嵌 8 张，其余占位），offset / limit 此时按单元计，没有 id 的旧格式单元叫 `cell-<序号>`（从 0 起）；空文件一句话；二进制、目录、不存在、offset 越界都是带处理建议的错误 |
| Write | `file_path`（绝对）、`content` | 一句「已写入 路径（N 行）」。覆盖已有文件必须先 Read 且读后未被改，否则错误并告诉它「先 Read / 重新 Read」 |
| Edit | `file_path`、`old_string`、`new_string`、`replace_all` | 先读后写；逐级唯一匹配，replace_all 仅精确匹配；返回附近最多 40 行带行号内容，保留 CRLF / BOM，不生成 unified diff |
| NotebookEdit | `notebook_path`（绝对，.ipynb）、`cell_id`、`new_source`、`cell_type`、`edit_mode`（replace 默认 / insert / delete） | 先读后写（同 Edit 的 lastSeen 机制）；按 id 定位单元，找不到时列出现有 id；只替换 / 插入 / 删除目标单元那一段文本，其余字节原样保留（缩进、键顺序、别的单元的输出）；replace code 单元清空 outputs 与 execution_count；insert 在 nbformat ≥ 4.5 时生成 8 位十六进制 id。回显改动后的那个单元 |
| Glob | `pattern`、`path`（可选） | 绝对路径逐行，修改时间从新到旧，最多 100 条，超出注明；无结果一句「没有匹配的文件」 |
| Grep | `pattern`、`path`、`glob`、`output_mode`、`-i`、`-n`、`-C`、`multiline`、`head_limit`（默认 250） | files_with_matches：路径列表；count：`路径:命中数`；content：`路径:行号:内容`，上下文行用 `-`，不相邻的组之间 `--`。超 head_limit 注明；命中行超 500 字符截断；无命中「没有匹配」 |
| AskUserQuestion | `questions`：1–4 题，每题 `question`、`header`、2–4 个 `options`（`label` + `description`）、`multiSelect` | 工具结果只有一句「用户已回答」；答案（「用户回答了你的问题：」+ 每题一行「问题 → 选择」，自填的「其他」标明出处）放在 `ToolResult.userText`，模型视图作为紧随 tool_result 的独立用户文本块送出；拒答与 dontAsk 模式各一句说明（不是错误）。不走审批，答复经持久事件送达 |
| EnterPlanMode | 无 | 一句「已进入计划模式：只能读与提问……用 ExitPlanMode 提交计划」，结果声明切到 plan（内核落盘）；已在计划模式时只提醒、不再切换 |
| ExitPlanMode | `plan`（Markdown） | 总是要人批准（含 bypass），审批卡片摘要即计划正文。获批：「计划已获批准，已退出计划模式（回到 X 模式）」，结果声明回到进入前的模式（没有记录则 default）；不在计划模式时获批不动模式。被拒：内核给出用户的说明，仍在计划模式 |
| Agent | `description`、`prompt` 必填；`subagent_type`（默认 general-purpose；Explore / Plan 只读；`fork` = 分身）、`model`、`run_in_background`（默认 true）、`name` | 后台：「已在后台启动……完成时你会收到报告，在那之前不要假设它的结果」；前台：`<subagent-report …>` 报告全文（结果带 ReportDelivered，内核据此不在恢复时重复补发）；被拒（类型不存在、超深度 / 数量上限、分身再委派）是写给模型的错误 |
| SendMessage | `to`（子 agent 的 id 或名字；子 agent 里 `main` 指父会话）、`message` | 一句送达说明：对方在跑就在当前工具批后看到，空闲就开回合、做完再交报告 |
| TaskStop | `task_id`（子 agent 的 id 或名字） | 一句说明：已让它停下，标为没做完的报告照常交回 |
| Skill | `skill`（必填，清单里的名字，开头的 `/` 会被去掉）、`args`（可选原文） | 渲染好的 skill 正文：skill 前有 `Base directory for this skill: 目录`，参数已替换（没给 args 时占位符原样保留）；找不到（列出可用名字）、`disable-model-invocation`（说明只能由用户 `/名字` 触发、别另起炉灶复现步骤）、文件读不了都是错误；超过结果预算的长正文落成附件只给预览 |
| Bash | `command`、`timeout`（毫秒，默认 120000，上限 600000，超了按上限算）、`description`、`run_in_background` | 输出 + 末尾「退出码 N」（非 0 为错误）；超时为错误并附已有输出；转后台给出 id / pid / 日志路径，让模型用 Read 看日志 |

所有路径入参必须是绝对路径；相对路径在 parse 阶段被拒绝，错误信息写给模型看。

## token 代价

- 七个工具的说明 + schema 每次请求都带，属于稳定前缀；合计 token 未精确计量，不能沿用旧五工具估计。
- Read 每行多约 8 个字符的行号前缀（6 位对齐 + 制表符），换来 Edit 与「第几行」的可引用性；默认最多 2000 行，是最大的单次输入来源。
- Grep 默认 files_with_matches，最省；content 模式每行带完整路径（同一文件的多条命中会重复路径），命中行截到 500 字符，输出最多 250 条。
- Glob 最多 100 条绝对路径。
- Bash 输出由端口在 30000 字符处保留头尾；内核再按结果预算落文件。
- G5 给 Bash 说明增加保留检查命令退出码的指导；增加固定文本，不产生运行时变量，供应商精确 token 增量未单独计量。DeepSeek 原 17/20 → 20/20、同模型 CC 19/20；Luna 复测 20/20，题目和文件标准未改变。
- AskUserQuestion 的说明 248 字符 + 嵌套 schema 690 字符（Bash 为 348 + 356），属稳定前缀；结果是题数行的短文本。
- EnterPlanMode 说明 164 字符 + schema 47 字符；ExitPlanMode 说明 171 + schema 139。计划正文是模型自己的输出 token，审批卡片原样展示。
- Write 的 content 是模型自己的输出 token，整文件重写很贵，说明里要求局部修改用 Edit。
- NotebookEdit 的说明约 317 字符、schema 约 601 字符，粗估合计 550–650 token（中文按每字 1–1.5 token 估，未用供应商 tokenizer 精确计量）；Read 说明因 notebook 多了约 70 字符（几十 token）。这部分是稳定前缀，每次请求都带。
- 读 notebook 的 token 成本主要在输出：单个输出块最多 8000 字符（头尾各半）、单行最多 2000 字符、最多内嵌 8 张图；图片按供应商的图片计价，一次 Read 的最坏情形是 8 张图 + 每个单元 8000 字符，仍由内核的结果预算（默认 30000 字符）再兜一次，超出部分落文件只给预览。
- NotebookEdit 只回显改动后的那个单元，不回显整本。
- Agent / SendMessage / TaskStop 的说明与 schema 是稳定前缀；Agent 按 HarnessProfile 给详细（Claude / GPT）或带例子的简短版本。子 agent 的报告进父会话的历史，长度受结果预算约束。分身的第一个请求与父会话共享整段前缀，几乎全部按缓存价计（子agent设计 §3）。

## 缓存影响

- 工具集与说明文字是常量（不含时间、cwd、随机内容），放在请求前缀里，只要工具集不变就稳定命中缓存；增删工具或改说明会使整段前缀失效。
- G5 Bash 说明变更会使旧配置缓存失效；新会话内说明恒定，实际传输前缀与 fingerprint 已复核。不能把改描述前后的指纹当成同一配置比较。
- 说明里不写当前目录（parse 拿不到 ctx），因此不会因为换目录破坏缓存；代价是模型要从别处（系统提示）得知 cwd。
- NotebookEdit 的说明同样是常量；新增这个工具、以及 Read 说明里多的那句 notebook 说明，会使一次旧缓存前缀失效，之后稳定。新单元 id 是随机的，只出现在工具结果里（历史的一部分），不进前缀。
- 工具结果是历史的一部分，一旦产生不再改写，不影响已缓存的前缀。
- 子 agent 三个工具：只读、可并发——不碰工作区，委派出去的子 agent 的每个调用各自过子会话的权限判定；默认模式不弹审批，计划模式里也能委派 Explore / Plan。

## 已知限制

- Read：文本文件一次最多读入 8 MB，更大的文件后面的内容读不到（说明里会提示）；只认 UTF-8（非 UTF-8 会出替换字符）；二进制只看前 8 KB 有无 NUL；图片不缩放，超 5 MB 直接报错。
- Write：不检查父目录是否存在，缺失时的行为（报错还是自动建目录）由宿主的 `writeAtomic` 决定；先读后写依赖 mtime 与大小，同一毫秒内大小不变的外部改动检测不到。
- Read（notebook）：整本读入再解析，上限 10 MiB（含大量内嵌图片的 notebook 容易超，超了建议 Bash + jq）；offset / limit 按单元计而不是按行；只认 nbformat 4（旧的 3 报错并建议 nbconvert）；图片只内嵌 png / jpeg / gif / webp，svg 等给占位；输出里的 text/html、application/json 等没有 text/plain 时只给占位，不渲染。
- NotebookEdit：不能新建 notebook（用 Write）；不能一次改多个单元（逐个调用）；`cell-<序号>` 的序号在插入 / 删除后会变，没有 id 的 notebook 里要先重新 Read；只用 8 位随机十六进制做新 id，不与已有 id 重复但不保证全局唯一；缩进靠文件开头的空白推断，压成一行的 notebook 写成无空格的压缩形式；检测到全文只有 ASCII 却带 `\uXXXX`（≥ 0x80）转义时，新内容也按 ensure_ascii 转义；写前把结果重新解析并与预期逐单元比对，不一致就拒绝写入（宁可失败不写坏）；replace code 单元清空输出，这与 Jupyter 界面里「改源码输出仍保留」不同，沿用 Claude Code 的做法；单元的 attachments 等附属键在 replace 时原样保留（转成 code 类型时不会清理 markdown 的 attachments）。
- Glob：向端口最多要 1000 条再排序，匹配更多时排序不完整（会注明）；模式语义（是否支持 `**`、大小写）完全由宿主实现决定。
- Grep：正则在 parse 阶段用 Java 正则预检，ripgrep（Rust regex）语法与之有差异（如 Java 独有的回溯引用与 Rust 独有的部分 Unicode 类），预检通过不保证宿主一定接受，反之亦然；端口只有逐条命中的形状，files / count 模式最多聚合 20000 条命中；文件列表按端口给的顺序，不按修改时间排；`-C` 只在 content 模式生效。
- AskUserQuestion：header 超 12 个字符不拒绝（界面自己截）、缺省时用问题开头顶上；不支持 Claude Code 的选项预览（preview）与自由回复（response）；等待没有超时，用户不在场就一直挂着，直到作答、拒答或中断。
- 计划模式：Bash 在现有权限引擎里不是只读工具，计划模式下整条拒绝（Claude Code 允许只读命令），要等命令分析落地；被拒时给模型的是内核通用的「用户拒绝了这次调用」+ 用户说明，没有计划专用的措辞。
- Bash：M1 每条命令都从会话目录起跑，`cd` / `export` 不跨调用保留；输出是 stdout 与 stderr 合并，分不开；命令分析（tree-sitter）未做，权限只能拿原始命令串判断；后台进程的取消与回收由内核凭句柄做。
- Skill：只读、可并发，**默认不弹审批**（Claude Code 默认要，因为 `allowed-tools` 能预批准工具；第一期没做那个，正文只是文字，它让模型做的事仍各自过权限）。用户的 `Skill(名字)` / `Skill(名字 *)` 允许、询问、拒绝规则照常生效（权限声明带 skill 名；policy 里尾部 ` *` 的 specifier 规则也匹配不带参数的裸名字）。说明约 190 字符 + schema 118 字符，粗估 250–350 token，属稳定前缀；加在工具表末尾，旧会话前缀不受影响。
