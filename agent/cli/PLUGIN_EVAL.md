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

支持六种 grader：`regex`（最终回复、Min 事件 trace、创建文件名、指定文件正文，contains/not_contains/count:N）、`tool_used`（工具名、参数正则、次数）、`tool_order`（先后工具及参数）、`file_exists`（工作目录相对 glob）、`llm`（criteria 与 focus）、`baseline`（用例内 baseline_file 和 criteria）。正则使用 JVM 的可移植 JS 子集，flags 接受 i/m/s/u/g，不接受 JS 专有正则语义。LLM/baseline 使用三个独立 judge 会话，多数 PASS 才通过。

## 运行

```sh
min-agent plugin eval ./my-plugin --trust-plugin --model my-model --provider my-provider --runs 1 --ablation none --json
min-agent plugin eval ./my-plugin --trust-plugin --model my-model --case 'summary*' --concurrency 2 --threshold 0.8 --allow-tools Write Edit
min-agent plugin eval init --bare summary
```

模型沿用 CLI 的 `--model/--provider/--base-url/--wire/--auth/--api-key-env/--context/--max-output/--images/--effort/--fast-model`。显式 `--model` 覆盖用例 model；否则采用用例 model 或 ANTHROPIC_MODEL。judge 默认 fast-model/供应商 fast model/主模型，也可用 `--judge-model`。凭证只供模型客户端使用，不进入插件 hook 的最小环境。

默认每用例每臂三次，with/without 双臂；`--runs` 范围 1..50，`--concurrency` 范围 1..8。`--ablation none` 只运行 with 臂。评分是正权重的通过比例；双臂默认不计 Skill 调用指标及 `arm: with-only`，`arm: both` 可覆盖，若全部排除则回退全部 graders。delta 是 with 均分减 without 均分。

模型只可访问独立工作目录和目标插件。插件副本隐藏评测定义及结果目录，不读用户/项目设置、记忆、其它插件或 bundled skills；Linux 托管设置复制到隔离 rootfs 后继续执行。只读 `allowed_tools` 自动批准，其余须显式 `--allow-tools`，未授权工具不出现在模型工具表。原生 host 没有 OS shell sandbox 后端，所以任何 Bash/PowerShell/Monitor grant 都拒绝。插件 hooks 属作者代码信任范围，在宿主执行并使用独立 HOME/TMPDIR 与最小环境；这与官方的作者代码信任边界一致，不能把本地 rootfs 当作不可信 hook 的 OS 沙箱。目录链接越界与循环被拒绝。

`--case` 按 name glob 过滤，`--tag` 按 tags 过滤；变长的 tag/allow-tools 选项前先放 target。也支持安装名、单个 prompt.md/case.yaml。`--keep-temp` 保留运行根目录供检查。`--max-cost-usd` 在新运行和付费 grader 前检查已知费用，已开始的并发任务及三票 judge 可能超过限额；未知费用会阻止后续付费工作。

## 报告与退出码

默认写 `evals/results/<时间>/aggregate-result.json` 和静态 `report.html`；`--output-dir` 覆盖。`--json` 只在 stdout 打印 JSON，`--json path.json` 另存 JSON。HTML 中模型内容全部转义。

JSON 使用 `schemaVersion: 1`、`engine: Min`，包含 configuration、cases、loadErrors、costUsd、durationSeconds、partial，以及 aggregates 的 overallScore/casesPassed/casesTotal/meanDelta。每个 case 包含定义、with/without arms、每次评分/graders/最后回复/Min trace/文件名/错误/费用/耗时，以及均分和 delta。这是本地 Min 报告，trace 不是 Claude transcript 的逐字兼容格式。

退出码：0 所有有效用例的 with 臂均分达阈值且成功启动；1 参数/格式错误、没有用例、不能启动或低分；2 花费上限/未知费用触发部分运行，或供应商鉴权失败触发部分运行。外部 coroutine 取消继续抛出 CancellationException；尚未实现 SIGINT 的部分报告落盘与官方 exit 2 路径。HTML 写入失败不改变评分退出码。

## 当前范围

尚不支持 context 的 scaffold_script/history_file/add_dirs、MCP mocks、真实 MCP/LSP 服务、覆写 plugins、交互式模型生成 init、OS shell sandbox、JS 专有正则及云报告发布。前述非空字段/未知参数明确拒绝；`init --bare` 可用。`--no-publish` 可接受，因为本地本来就不发布。文件正文 grader 上限 4 MB，超限明确失败。

官方云报告发布、账号登录与 Claude 专属运行参数不能直接用于 Min；本地模型和 judge 使用现有供应商配置。以上本地未实现项仍需后续补齐，不能据此宣称完整 Claude eval 兼容。

核对来源（2026-10-11）：[llms.txt](https://code.claude.com/docs/llms.txt)、[Plugin evals](https://code.claude.com/docs/en/plugin-evals.md)、[Plugin CLI](https://code.claude.com/docs/en/plugins/cli-reference.md)、[Plugin manifest](https://code.claude.com/docs/en/plugins/manifest-reference.md)。
