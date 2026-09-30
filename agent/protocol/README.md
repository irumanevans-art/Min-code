# :agent:protocol

UI↔内核的线协议类型与版本（02-总体架构.md §2）。

- 允许依赖：—（无）
- 设计：docs/agent/03-核心契约.md §2（线协议：`:agent:protocol`，UI 与内核之间）

## 模型看到什么
ID、ModelRef、Part、Usage、StopReason 等共享值类型，作为模型消息的组成部分。
## token 代价
仅实际进入请求的内容块计入 token；类型声明无代价。
## 缓存影响
AgentJson 固定序列化纪律；共享值不自行改写历史。
## 已知限制
尚无 MAP Command / ServerMessage / Snapshot。
