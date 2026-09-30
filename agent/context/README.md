# :agent:context

提示装配、记忆发现、压缩流水线、缓存规划、循环检测（02-总体架构.md §2）。

- 允许依赖：protocol, spi, llm
- 设计：docs/agent/04-子系统设计.md §4.5（上下文与缓存）、§4.6（记忆）

## 模型看到什么
SystemPrompt 与 RequestAssembler 装配 profile、环境、日志历史和工具；当前为 M1 最小提示。
## token 代价
system / tools 每次请求都带；尚无 token 估算与压缩流水线。
## 缓存影响
固定环境、模型与 schema 保证同配置前缀稳定；cacheKey 来自会话，longTtl=true。
## 已知限制
记忆发现、世界状态增量、压缩、缓存规划和循环检测尚未实现；配置版本 / 前缀未完整持久化。
