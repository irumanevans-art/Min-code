# :agent:spi

内核对外部世界的全部端口接口 + 共享的值类型（02-总体架构.md §2）。

- 允许依赖：protocol
- 设计：docs/agent/03-核心契约.md §1（持久事件）、§6（宿主端口）

## 模型看到什么
规范 ModelMessage、工具结果与事件；端口本身不发给模型。
## token 代价
ToolResult 默认预算 30000 字符，超预算由内核附件策略处理。
## 缓存影响
持久事件与规范历史供内核重建请求，前缀由 context 装配。
## 已知限制
Exec 仅 run / kill，无 poll / stdin；Replay / Reattach 当前不自动执行。
