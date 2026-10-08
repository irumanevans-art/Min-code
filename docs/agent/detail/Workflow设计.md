# Workflow（M5）

模型：GPT 6.1 Sol。基线：agent/m0 28ec4172。

QuickJS 只由 Android 宿主模块 agent/workflow 引入；内核使用 spi 的 WorkflowEngine，原有 13 个模块保持纯 JVM。节点使用 SessionHub，继承父会话权限、目录、MCP 与审批树。节点自己控制并发，避免再次受普通子 agent 的三个请求槽限制。禁止 AskUserQuestion 和 MCP elicitation，工具审批照常保留。

项目脚本放 `.claude/workflows/*.js`，个人放 `~/.claude/workflows/*.js`，项目同名优先。只扫描文件与字面量 meta，不执行发现到的脚本。首语句必须是 `export const meta = {name, description}`，支持可选字符串数组 phases。动态 meta 不进入命令列表。以 `/name 参数` 运行，未传参数为 undefined，参数为原始字符串，无需模型选择脚本。

```javascript
export const meta = {name: 'review', description: 'Review a change', phases: ['Review']};
await phase('Review');
await log(args ?? 'Review current changes');
return await pipeline(['correctness', 'tests'], item => agent(`Review ${item}`, {label: item}));
```

提供 agent(prompt, {schema?, label?, model?})、pipeline(list, fn)、parallel(...tasks)、phase(title)、log(message)、全局 args。parallel 接受数组或多参数、Promise 或函数。pipeline 保留 null。最多 4,096 项、1,000 个 agent 调用。未 return 时结果为按启动序排列的节点结果。

Date.now、Math.random、Date()、无参 new Date 禁用，原构造器不能从 prototype.constructor 取回；指定时间的 Date、Date.UTC/parse 可用。无文件、网络、shell、输入 API 或模块加载器。32 MiB 原生内存上限，JS 执行预算 30 秒不计模型等待；父协程取消打断原生循环并关闭引擎。

手机默认 4；设置 `env.CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS`（或宿主 env）接受 1–256，其他值回退。每次 run 冻结并发和 schema 次数。schema 与 --json-schema 共用 JsonSchema，默认总尝试五次（首次 + 四次重试），MAX_STRUCTURED_OUTPUT_RETRIES 正整数可覆盖。重试在同一子会话里续聊；子会话中断或错误返回 `{error, message}`，父工作流取消继续传播 CancellationException。

审批卡展示源码和 SHA-256，授权与内容和项目 cwd 绑定，App 在私有目录持久保存，后续会话复用；内容改变重新批准。Workflow(hash) allow/deny/ask 规则生效，deny 优先；首次确认不由 auto reviewer 或 hook allow 跳过。执行只用批准时冻结的源码。

节点 started/completed/result 同步落日志。重开以原脚本、参数和序号回放完成节点，未完成节点重跑；失败或 prompt/options 不同的节点及其后重跑。新的 started 废弃旧 completed。被中断会话点继续自动找最后未完成 run；Workflow 工具也可带 resume_run，需原脚本和 args。回合锁阻止正在运行时二次恢复。

App 用现有 Note 显示阶段和日志。许可页为 AboutPage，中英文正文和可展开完整文本显示声明；原样上游 MIT、Apache 文件与固定提交来源随 APK assets/licenses 打包。上游 AAR 本身没有声明文件，应用明确补足。

桌面 CLI 此阶段未装配 WorkflowEngine；单测使用同版本 JVM 原生引擎，桌面会话不加载 Android AAR。
