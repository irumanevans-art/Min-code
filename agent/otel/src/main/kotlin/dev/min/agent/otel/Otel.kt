package dev.min.agent.otel

/*
 * 遥测模块的总说明（见 docs/agent/05-对标清单.md 的 OpenTelemetry 行）。
 *
 * 数据源只有一个：会话日志（SessionLog）。这里不另记一份账，所有指标与事件都是对日志事件的纯函数映射，
 * 所以关掉遥测、删掉导出文件，会话本身一点都不受影响。默认关（CLAUDE_CODE_ENABLE_TELEMETRY 不为 1 就什么都不导出）。
 */

/** 遥测仪表的作用域名，写进 OTLP 的 scope 里，便于后端识别来源 */
const val OTEL_SCOPE_NAME = "dev.min.agent.otel"
