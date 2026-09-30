# :agent:testkit

测试替身与夹具工具（02-总体架构.md §2）。

- 允许依赖：kernel 及以下（protocol, spi, store, llm, policy, context, tools, ext, kernel）
- 设计：docs/agent/04-子系统设计.md §4.10（可观测、测试与评测）

## 模型看到什么
无生产 Kotlin 实现；各模块测试源码里有 fake / 脚本化模型与夹具。
## token 代价
测试不产生供应商 token；真模型评测需另建 runner。
## 缓存影响
请求快照与恢复测试校验前缀，生产 SSE 录制回放尚未实现。
## 已知限制
模块保留依赖入口，不能声称已有可复用生产录放 API 或 20 题评测集。
