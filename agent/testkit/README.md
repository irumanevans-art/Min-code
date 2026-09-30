# :agent:testkit

测试替身与夹具工具（02-总体架构.md §2）。

- 允许依赖：kernel 及以下（protocol, spi, store, llm, policy, context, tools, ext, kernel）
- 设计：docs/agent/04-子系统设计.md §4.10（可观测、测试与评测）

## 模型看到什么
无生产 Kotlin 实现；各模块测试源码里有 fake / 脚本化模型与夹具。
## token 代价
单元测试不产生供应商 token；桌面真模型 runner 已在 agent/cli/scripts（20 题评测及专项冒烟），真请求用量由 CLI 的 turn_usage / 会话日志记录。
## 缓存影响
请求快照与恢复测试校验前缀；CLI Python 录制代理保存脱敏请求 / 流，llm 测试已有 Luna 的生产 SSE 回放夹具。这些尚未抽成 testkit 的 Kotlin 录放 API。
## 已知限制
模块保留依赖入口，不能声称已有可复用生产 Kotlin 录放 API。20 题的数据与评分器在 agent/cli/scripts/evaluate.py；本次 DeepSeek Flash Min 20/20 ≥ 同模型完整 CC 19/20，Claude 的同模型对照仍缺凭证，不能宣布正式 M1 通过。
