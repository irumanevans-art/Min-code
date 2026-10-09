# Feature Requests

Missing capabilities requested by users, captured during development.

**Areas**: frontend | backend | infra | tests | docs | config
**Statuses**: pending | in_progress | resolved | wont_fix
**Complexity**: simple | medium | complex

## Status Definitions

| Status | Meaning |
|--------|---------|
| `pending` | Not yet addressed |
| `in_progress` | Actively being built |
| `resolved` | Capability implemented (add Resolution block) |
| `wont_fix` | Decided not to build (reason in Resolution) |

Entry format: see the self-improvement skill's "Feature Request Entry" section. IDs use `FEAT-YYYYMMDD-XXX`.

---


## [FEAT-20261009-WORKFLOW-EXTRA-API] workflow budget and nested workflow

**Logged**: 2026-10-09T10:46:00+08:00
**Priority**: medium
**Status**: pending
**Area**: backend

### Requested Capability
脚本提供与 Claude Code 一致的 budget 和嵌套 workflow()。

### User Context
Workflow 独立复核修复任务要求其余官方 API 也支持，不能支持的列明理由。

### Complexity Estimate
complex

### Suggested Implementation
等待可验证的官方脚本签名、预算计费/继承及嵌套恢复契约；当前 workflows 官方页、Agent SDK Workflow 输入和官方 cookbook 未给出这两个符号。已有会话 token/费用记账不能据此猜测脚本 API。恢复与权限设计确定后补实现及真引擎端到端。

### Metadata
- Frequency: first_time
- Related Features: workflow runtime, billing, replay

---

## [FEAT-20261009-WORKFLOW-STALL] workflow stalled agent restart

**Logged**: 2026-10-09T11:20:00+08:00
**Priority**: medium
**Status**: pending
**Area**: backend

### Requested Capability
对齐最终核对官方 workflows 页的 stallMs 与节点无输出自动重启。

### User Context
任务单要求其余公开 API 的未实现项列明；本轮已实现指定选项及 JS 无进展看门狗。

### Complexity Estimate
complex

### Suggested Implementation
另建模型输出/工具等待计时与节点重启管线，最多五次重启，废弃上一轮转录而保留文件和费用，冻结重启计数，并区分 fan-out 的 null 与直接 await 的终止错误；不能用 JS 30 秒预算替代模型停滞窗口。目前 stallMs 为未知字段，被忽略，报告和设计文档明确此边界。

### Metadata
- Frequency: first_time
- Related Features: workflow nodes, model streaming, replay

---
