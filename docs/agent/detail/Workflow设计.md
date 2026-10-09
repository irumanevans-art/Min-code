# Workflow（M5 · 独立复核修复）

模型：GPT 6.1 Sol。基线合并：agent/m0 13c74026。

QuickJS 的同一份引擎源码分别由 Android agent/workflow 和桌面 CLI 编译，链接 version catalog 中同版本的 Android / JVM 原生库。内核依赖 spi.WorkflowEngine。手机默认并发 4，桌面 16；CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS 接受 1–256，每个 run 冻结并发与 schema 尝试次数。Workflow 只在主会话可见。

项目保存于 .claude/workflows/*.js，个人保存于 ~/.claude/workflows/*.js，项目同名优先。发现过程仅解析字面量 meta，不执行 JS，并使用凭据策略检查实际路径。首语句为 export const meta = {name, description}；phases 兼容字符串和 {title, detail}。未传 args 为 undefined，结构化 args 用 JSON.parse 注入，__proto__ 保持普通自有属性。

```javascript
export const meta = {name:'review', description:'Review', phases:[{title:'Review',detail:'Check changes'}]};
await phase('Review');
return await pipeline(['correctness','tests'],
  (previous,item,index) => agent(`Review ${item}`,{label:item,effort:'high'}),
  (previous,item,index) => ({item,result:previous}));
```

agent(prompt, options) 支持 schema、label、model、phase、effort、isolation:'worktree'、agentType；额外字段忽略。isolation 走已有 worktree 端口，agentType 走已有定义。失败或子会话中断返回 null；父回合 CancellationException 传播。pipeline(items,...stages) 每阶段接收 (上一阶段结果,原项,序号)，null 跳过后续阶段并保留位置；parallel 数组或多参数中某项失败返回该位置 null。最多 4096 项和每 run 1000 个 agent，边界包含。

schema 使用子会话内的 StructuredOutput 工具交卷，复用 --json-schema 的校验和重试循环。默认五次，MAX_STRUCTURED_OUTPUT_RETRIES 可配置，冻结值传给子会话。围栏自由文本不会被当作交卷，模型仍能在后续请求用工具交出答案。工作流树不接受 AskUserQuestion / MCP elicitation，工具权限仍挂根 Hub。

沙箱屏蔽当前时间、随机数、文件、网络、shell、输入和模块加载。引擎专用 dispatcher 有四个守护线程。明确配置 32 MiB JS 内存、256 KiB 原生栈；自有看门狗仅在没有宿主调用在途且 30 秒无进展时中断，落盘回放也是宿主调用。阈值可注入，开始计时前完成固定沙箱初始化；进展更新与中断判定同步，避免宿主调用刚启动就被旧时间误杀。父协程取消仍中断原生循环。

JS 在异步桥之前限制 prompt 300000 字符、日志/阶段 30000 字符；JS 与宿主各限制每 run 4096 条 / 1 MiB 进度，并仅加一条丢弃说明。宿主预算计入历史回放进度。Workflow 返回值通过现有工具预算限制为 12000 字符，完整节点/运行结果保留在持久日志。

bypass 直接运行；Manual / acceptEdits 每次询问，只有明确选择本项目不再问才记住 cwd + 源码哈希。Auto 的一次同意由宿主保存，后续调用免额外确认。Workflow / Workflow(name) 规则、PreToolUse allow、AutoReviewer 可预授权；deny / ask 与 auto hard_deny 仍优先。批准后只执行冻结源码。App 和 CLI 共用私有 FileWorkflowApprovals，包含读写、revoke 和 revokeAuto 接口。App 显示查看脚本与项目记住选项；CLI -p 无交互，由原有权限链路决定是否允许。

节点 started / completed 同步落盘后返回给 JS。只有 run 属于刚被中断回合时 ▶ 自动恢复；失败/取消分别落 failed/cancelled 终态。显式 resume_run 可使用修改源码、原 args，按 prompt/options 比较复用最长未改动前缀，首次变动或失败处起全部重跑。新的 started 废弃旧 completed。写盘异常广播给序号等待者；取消收尾写盘失败保留原取消原因。保存命令执行失败使回合与 CLI 结果失败。

CLAUDE_CODE_DISABLE_WORKFLOWS=1、settings.disableWorkflows=true 和 App 全局开关隐藏工具与命令；已开始运行继续使用冻结配置。settings 禁用事件只在禁用时落盘，默认日志形状不变，重开保持。许可页及 APK assets 同步包含 quickjs-kt Apache、QuickJS MIT、c-vector MIT（Evan Teran 2015）原文与固定来源。

核验依据：[Workflow 官方页](https://code.claude.com/docs/en/workflows)、[Agent SDK Workflow 输入](https://code.claude.com/docs/en/agent-sdk/typescript#workflow)、[子 agent 工具可见性](https://code.claude.com/docs/en/sub-agents)。公开页当前没有给出脚本 budget / workflow() 的签名和语义，因此未猜测实现这两项。页面新增的 stallMs / 单节点无输出自动重启管线尚未实现，stallMs 当前被忽略；节点失败按本轮任务单统一返回 null，不宣称已实现 stalled agent 直接 await 的专门终止语义。

正式测试覆盖原生递归五种、内存、30 秒无进展、35 秒宿主后 CPU 工作、进度洪泛、边界、批准、结构化交卷、修改前缀和写盘失败；CLI 端到端使用真引擎、真 SessionHub 与真持久回放。变异脚本 tools/workflow_fixes_mutations.py 保存每项真实退出码、指定断言失败和恢复结果。
