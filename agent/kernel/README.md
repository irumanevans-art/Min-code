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

## Skills（第一期）
- 会话创建时（`SessionRuntime.create`）读一次 skills / commands（`ClaudeConfigLoader`）与 `skillListingBudgetFraction` / `skillListingMaxDescChars`，写 `skills_loaded` 快照（`Skills.kt`）；
  重新打开不重读。子 agent（分身与新开的）继承父会话那份，不自己读盘（`SessionHub.inheritedSkills`）。
- 系统提示的清单段只从快照拼，且只在会话的工具表里有 Skill 时出现。`KernelSkillPort` 按快照找 skill：不在快照里（会话开始后才新增）、
  `disable-model-invocation`（拒绝并说明，叫模型别用别的办法复现步骤）、文件读不了都是写给模型的错误；正文调用时读、此刻渲染，结果走现有的工具结果预算。
- 已知限制：正文是调用那一刻磁盘上的（只有清单冻结）；`allowed-tools` 预批准、`context: fork`、`model` / `effort` 覆盖、`hooks` 没做；Skill 工具默认不弹审批（见 :agent:tools）。
