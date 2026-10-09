# 网络批准变异验证

在仓库根目录用 Node 执行 `node tools/network_approval_mutations.cjs <证据目录> [名称,名称]`。省略名单会运行全部 13 项；每项先 cleanTest，再执行指定需求断言。脚本在 finally 逐字恢复源码，保存命令、真实退出码、日志与 XML。

只把正常编译且由 AssertionError / ComparisonFailure 检出的弱化算作杀死。成功执行而无失败是存活；编译失败不计。源码恢复后再执行基线检查。请在没有其他构建或源码编辑的隔离 worktree 中运行，避免变异影响并发操作。

2026-10-09 的首次 Auto 闸门变异存活，因为测试的裸 Bash 规则本来就需要分类器；改用窄命令允许规则后，该变异被检出。实际产品路径将 shell 审批和网络判定分开，分类器审查原始 allowed_domains 参数。
