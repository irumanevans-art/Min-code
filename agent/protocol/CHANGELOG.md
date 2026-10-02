# Min Agent Protocol（MAP）变更记录

字段只加不改不删；每次变更在此追加一行。

## 未发布

- 2026-10-01：ToolResult 新增可选 effects（默认空）；report_delivered 保存前台子报告交付标记，与工具结果原子落盘，恢复时不重复补发。
- 新增 `QuestionId`（一次向用户的提问，持久请求的 id；线上是裸字符串）。
