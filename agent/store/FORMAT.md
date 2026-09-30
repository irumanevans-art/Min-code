# 会话日志格式

`formatVersion: 1`（M0 占位，M1 定稿）

**格式纪律**：日志文件头写 `formatVersion`；新增事件类型或字段只加不改；旧版本永远可读；
读到比自己新的主版本直接拒绝并提示升级（学 OpenAI Agents SDK 的 RunState）。每次版本变更在
`agent/store/FORMAT.md` 追加一行说明。

## 变更记录
