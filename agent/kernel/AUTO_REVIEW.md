# auto 审查

CLI 用 `--mode auto` 开启。权限引擎需要审批的调用先审查，结果为允许、拒绝或转人；底线与 alwaysAsk 直接转人，权限规则形式的 hard_deny 先直接拒绝。自然语言 hard_deny 由分类器报告匹配索引，内核将匹配强制归为拒绝；有此类规则时，只读并行调用也经过审查。

审查失败、超时、截断、JSON 无法解析、非法决定或索引均转人。PreToolUse 的允许不能跳过审查，其询问仍转人；审查决定转人后，PermissionRequest 的拒绝仍有效，其允许不再自动执行。外部取消继续向上传播。

审查设置只从用户与托管层读取，项目与本地层被忽略。快照进事件并随子会话继承，不随磁盘设置的中途修改变化。

```json
{
  "autoMode": {
    "environment": ["Internal build artifacts stay within this repository"],
    "hard_deny": ["Bash(terraform destroy *)", "Never publish confidential files"],
    "soft_deny": ["Avoid production deployments"],
    "allow": ["Allow routine local builds"]
  },
  "autoModeReviewer": "deepseek/deepseek-flash"
}
```

默认审查模型：Anthropic 固定 `claude-haiku-5-5`，其他供应商读取目录 `fastModel`，没有则使用会话模型。与提取模型 `--fast-model` 独立。显式配置与会话模型切换无关；CLI 可用 `--auto-mode-reviewer provider/model` 临时覆盖，交互时 `/auto-reviewer` 查看，`/auto-reviewer provider/model` 写用户设置并更新当前会话，`/auto-reviewer default` 恢复默认。

目录外模型需要用户声明能力，供应商端点必须已经可解析：

```json
{"autoModeReviewer":{"provider":"anthropic","model":"custom-small","contextWindow":8000,"maxOutputTokens":512}}
```

宿主读取 `SessionState.autoReviewSettings`，调用 `SessionRuntime.changeAutoModeReviewer` 更新；`KernelDeps.reviewerModels` 解析声明的能力，`providerFastModel` 提供同供应商目录选择。

审查请求只包含近期用户消息、工具入参、待审查调用和可信规则；不包含工具输出、凭证文件、附件或环境变量。凭证路径识别复用 policy 的 `credentialPath` 与宿主 canonicalPath。旧历史有凭证读取结果时，上下文和待审查入参使用占位符。

模型请求统一按 `modelTimeoutMs` 限时，缺省沿用 600 秒。`ModelCharged` 冻结各模型本次用量对应的币种和费用，`AutoReviewed` 的审查用量计入回合；按币种读 `SessionState.modelCosts`，未知定价读 `unpricedModelCalls`。

CLI 的 `--max-budget-usd` 在请求前检查累计费用，审查后达到预算也转人；同会话有预算的辅助请求串行检查与扣账。费用以供应商完成事件的实际用量结算，单次在途请求可能越过阈值，此后不再发模型请求。无价格或非 USD 的模型在启用 USD 预算时不发请求，不猜汇率或价格。目录尚无 Haiku 5.5 能力和价格，任务指定的默认 id 使用保守纯文本能力且保留未知价格；不可用时转人，不能冒用其他 Haiku 的价格。

四组字段沿用 CC 的自然语言数组和用户/托管层累加语义。未复制 CC 分类器内建规则、`$defaults` 展开、`classifyAllShell`、auto-mode CLI 子命令和 setup 向导；Min 的底线与保守审查提示独立。明确 ask 规则在此任务要求下同样进入审查，与 CC 的强制人工询问不同。

验证脚本：`scripts/auto-review-mutations.ps1` 保存三个正常编译后由需求断言检出的变异，finally 恢复源码；`scripts/auto-review-check.ps1` 先清十三模块测试，再完整 check，Windows 临时补 Git Bash PATH 并以新 JVM 执行，保存真实退出码、日志和 XML。
