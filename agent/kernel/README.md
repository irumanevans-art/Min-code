# :agent:kernel

会话 actor、turn 状态机、工具调度、恢复、子 agent、工作流、组合根（02-总体架构.md §2）。

- 允许依赖：protocol, spi, store, llm, policy, context, tools, ext
- 设计：docs/agent/04-子系统设计.md §4.1（回合循环）、§4.8（多 agent 与自动化）、§4.9（检查点与回滚）；docs/agent/02-总体架构.md §3（进程与线程模型）

## 模型看到什么
日志投影的合法历史、完整工具结果；崩溃恢复补缺失结果，不把半截 AssistantSegment 发给模型。
## token 代价
记录 Usage；失败重试可能重复计费，完整成本估算尚未实现。
## 缓存影响
恢复测试比实际 system / tools 与 fingerprint；保障同配置恢复，非跨升级承诺。
## 已知限制
工具完整回复后执行；恢复先结束旧回合，不自动 Replay / Reattach；子 agent、工作流、压缩和检查点未实现。
