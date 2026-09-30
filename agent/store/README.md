# :agent:store

事件日志、快照、索引、大输出、会话目录布局（02-总体架构.md §2）。

- 允许依赖：protocol, spi
- 设计：docs/agent/03-核心契约.md §8（会话存储）

## 模型看到什么
不直接进入提示；FileSessionStore 存持久事件，内核投影模型历史。
## token 代价
存储本身无 token；事件内容经投影后计入请求。
## 缓存影响
日志只追加，尾部半行修复，不改已有完整记录。
## 已知限制
已实现 JSONL v1 与 seq 连续校验；快照、索引与 children 布局未实现，见 FORMAT.md。
