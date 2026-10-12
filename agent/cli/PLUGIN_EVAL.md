# 本地插件评测

`min-agent plugin eval` 为每次运行创建独立 Min 内核、会话树、临时 rootfs 与用户目录。它不使用 Claude Code CLI，也不上传报告。

## 用例布局

默认目录是插件的 `evals/`。插件清单可以用 `experimental.evals` 指定相对路径；数组取第一项，CLI 的 `--eval-dir` 优先。目录可嵌套，`results/` 与 `mocks/` 不作为用例搜索。

```text
.claude-plugin/plugin.json
skills/summarize/SKILL.md
evals/summary/prompt.md
evals/summary/graders/answer.md
evals/summary/graders/skill.md
```

`prompt.md` 示例：

```markdown
---
name: summary
runs: 3
max_turns: 10
timeout_seconds: 300
allowed_tools: [Read, Glob, Grep, Skill]
tags: [smoke]
---
请使用示例插件总结工作目录里的资料。
```

`graders/answer.md`：

```markdown
---
type: regex
flags: i
---
summary|总结
```

`graders/skill.md`：

```markdown
---
type: tool_used
tool: Skill
input_match: summarize
arm: both
---
```

也接受 `case.yaml`：`schema_version: "1.1"`、`name`、元数据、`execution` 与 `graders` 列表。相同用例同时有 YAML 和 Markdown 时，Markdown 的执行字段与正文优先，目录里的 Markdown graders 追加到 YAML graders。未知字段或未实现的字段会报错。`expected_outcome` 是报告元数据，实际评分由 graders 决定。

支持六种 grader：`regex`（最终回复、Min 事件 trace、创建文件名、指定文件正文，contains/not_contains/count:N）、`tool_used`（工具名、参数正则、次数）、`tool_order`（先后工具及参数）、`file_exists`（工作目录相对 glob）、`llm`（criteria 与 focus）、`baseline`（用例内 baseline_file 和 criteria）。正则直接交给 QuickJS 的 RegExp，支持 JS 的未参与捕获反向引用、命名捕获、lookbehind、Unicode 属性、sticky 和零宽匹配计数；非法或重复 flags 在加载时拒绝。单次计算有 2 秒、16 MB JS 堆和 256 KB 栈上限。LLM/baseline 使用三个独立 judge 会话，多数 PASS 才通过。

## 运行

```sh
min-agent plugin eval ./my-plugin --trust-plugin --model my-model --provider my-provider --runs 1 --ablation none --json
min-agent plugin eval ./my-plugin --trust-plugin --model my-model --case 'summary*' --concurrency 2 --threshold 0.8 --allow-tools Write Edit
min-agent plugin eval init --bare summary
```

模型沿用 CLI 的 `--model/--provider/--base-url/--wire/--auth/--api-key-env/--context/--max-output/--images/--effort/--fast-model`。显式 `--model` 覆盖用例 model；否则采用用例 model 或 ANTHROPIC_MODEL。judge 默认 fast-model/供应商 fast model/主模型，也可用 `--judge-model`。凭证只供模型客户端使用，不进入插件 hook 的最小环境。

默认每用例每臂三次，with/without 双臂；`--runs` 范围 1..50，`--concurrency` 范围 1..8。`--ablation none` 只运行 with 臂。评分是正权重的通过比例；双臂默认不计 Skill 调用指标及 `arm: with-only`，`arm: both` 可覆盖，若全部排除则回退全部 graders。delta 是 with 均分减 without 均分。

模型只可访问独立工作目录、目标插件副本与显式 context 只读挂载。插件副本隐藏评测定义及结果目录，不读用户/项目设置、记忆、其它插件或 bundled skills；Linux 托管设置复制到隔离 rootfs 后继续执行。只读 `allowed_tools` 自动批准，其余须显式 `--allow-tools`，未授权工具不出现在模型工具表。原生 host 没有 OS shell sandbox 后端，所以任何 Bash/PowerShell/Monitor grant 都拒绝。插件 hooks 属作者代码信任范围，在宿主执行并使用独立 HOME/TMPDIR 与最小环境；这与官方的作者代码信任边界一致，不能把本地 rootfs 当作不可信 hook 的 OS 沙箱。目录链接越界与循环被拒绝。

`--case` 按 name glob 过滤，`--tag` 按 tags 过滤；变长的 tag/allow-tools 选项前先放 target。也支持安装名、单个 prompt.md/case.yaml。`--keep-temp` 保留运行根目录供检查。`--max-cost-usd` 在新运行和付费 grader 前检查已知费用，已开始的并发任务及三票 judge 可能超过限额；未知费用会阻止后续付费工作。

## 报告与退出码

默认写 `evals/results/<时间>/aggregate-result.json` 和静态 `report.html`；`--output-dir` 覆盖。`--json` 只在 stdout 打印 JSON，`--json path.json` 另存 JSON。HTML 中模型内容全部转义。

JSON 使用 `schemaVersion: 1`、`engine: Min`，包含 configuration、cases、loadErrors、costUsd、durationSeconds、partial，以及 aggregates 的 overallScore/casesPassed/casesTotal/meanDelta。每个 case 包含定义、with/without arms、每次评分/graders/最后回复/Min trace/文件名/错误/费用/耗时，以及均分和 delta。这是本地 Min 报告，trace 不是 Claude transcript 的逐字兼容格式。

退出码：0 所有有效用例的 with 臂均分达阈值且成功启动；1 参数/格式错误、没有用例、不能启动或低分；2 花费上限/未知费用触发部分运行，或供应商鉴权失败触发部分运行。SIGINT 取消尚未完成的运行及评分，已完成的结果保留，写 JSON/HTML 部分报告并退出 2（partialReason: interrupted）；外部 coroutine 取消继续抛出 CancellationException。HTML 写入失败不改变评分退出码。

## Context、覆写与 mocks

`case.yaml` 的 `context` 支持 `history_file`、`add_dirs`、`scaffold_script`。路径限制在用例目录的真实边界内；历史按 Claude/OpenAI 的 user/assistant 文本、tool_use/tool_result JSONL 导入为外部历史，不能作为当前人类授权。历史用例在未显式指定双臂时采用 single-arm (no Δ)。`add_dirs` 挂到 `/fixtures/<序号>`，可读而不可写，也不参与配置发现。

`scaffold_script` 只在显式 `--scaffold` 时由 bash 在运行工作目录外部执行，使用最小 HOME/TMPDIR/PATH/TERM 环境，失败中止该 run；`--no-scaffold` 可覆盖。scaffold 创建的文件可以读和评分正文，但不计入「模型创建文件」指标。作者脚本和 hooks 是受信任作者代码，不能据此声称 Windows 有 OS shell 沙箱。

用例的 `plugins` 列表替换目标插件集合，路径相对 case 目录；空列表加载零插件。配置及评测文件不会借副本带入模型视图。

默认仅提供插件声明的、在 suite/case `mocks/<server>/<tool>.md` 中定义的 MCP 工具，case 文件逐个覆盖 suite 文件；真实 MCP/LSP 不启动。fixed mock 支持 `{{input.key}}`、`{{file:fixtures/{input.key}.json}}`，模板只展开一次。`expect` 不通过或 agent mock 超过 `4 * max_turns` 会 abort，score 0、error null。`_server.md` 提供 agent 回退，`_tools.json` 定义描述与 schema。agent mock 用 judge 模型，费用计入总额；干净 run 的录制写 `mock-recordings/` 和 `ADOPT.txt`，采用到 `mocks/.replay/<server>/` 后相同工具输入不再请求模型。

交互 `plugin eval init` 要求终端与目录信任；模型询问预期，生成正负候选，隔离试跑后发布可编辑 suite，输入 `/exit` 退出。CI 使用 `init --bare`。

## 当前范围

Windows 桌面没有等价 OS shell sandbox 后端，Bash/PowerShell/Monitor grant 明确拒绝。真实服务器的 `--allow-real-servers` / `--mocks off` 尚无授权后端，明确拒绝。官方云发布与账号登录不适用于本地 Min，`--no-publish` 可接受。文本历史不支持图像块；文件正文 grader 上限 4 MB，mock fixture 上限 1 MB，mock expect 输入统一上限 4096 字符。这些预算与官方闭源的动态预算可能不同；不宣称完整 Claude eval 兼容。
