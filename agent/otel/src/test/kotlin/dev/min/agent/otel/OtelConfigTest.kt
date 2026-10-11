package dev.min.agent.otel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtelConfigTest {
    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `什么都不设时遥测关闭，不导出任何东西`() {
        val config = OtelConfig.fromEnv(env())
        assertFalse(config.enabled)
        assertFalse(config.active)
        assertNull(config.metricsEndpoint)
        assertNull(config.logsEndpoint)
        assertNull(config.localFile)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `只开总开关而没有任何导出目的地，仍然什么都不导出`() {
        val config = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1"))
        assertTrue(config.enabled)
        assertFalse(config.active)
    }

    @Test
    fun `DISABLE_TELEMETRY 与 DO_NOT_TRACK 压过总开关`() {
        val a = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "DISABLE_TELEMETRY" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl"))
        assertFalse(a.enabled)
        assertFalse(a.active)
        val b = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "DO_NOT_TRACK" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl"))
        assertFalse(b.active)
    }

    @Test
    fun `通用端点补上各信号的路径，头部合并，协议为 http json`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_LOGS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://collector.example.com:4318/",
            "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Bearer%20abc,X-Team=min",
            "OTEL_EXPORTER_OTLP_LOGS_HEADERS" to "X-Extra=1",
        ))
        assertTrue(config.active)
        assertEquals("https://collector.example.com:4318/v1/metrics", config.metricsEndpoint)
        assertEquals("https://collector.example.com:4318/v1/logs", config.logsEndpoint)
        assertEquals(listOf("Authorization" to "Bearer abc", "X-Team" to "min"), config.headers)
        assertEquals(listOf("Authorization" to "Bearer abc", "X-Team" to "min", "X-Extra" to "1"), config.logsHeaders)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `协议不是 http json 时对应信号关闭并给出说明`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "grpc",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://localhost:4317",
        ))
        assertNull(config.metricsEndpoint)
        assertTrue(config.problems.any { "grpc" in it && "http/json" in it })
    }

    @Test
    fun `未设协议时说明缺什么，不猜`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://localhost:4318",
        ))
        assertNull(config.metricsEndpoint)
        assertTrue(config.problems.any { "OTEL_EXPORTER_OTLP_PROTOCOL" in it })
    }

    @Test
    fun `prometheus 不支持，给出说明、不静默；console 是支持的`() {
        val config = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "OTEL_METRICS_EXPORTER" to "prometheus,console"))
        assertTrue(config.problems.any { "prometheus" in it })
        assertFalse(config.problems.any { "的 console" in it })
        assertTrue(config.metricsConsole)
        assertTrue(config.active)
        // 只有 prometheus：没有任何目的地
        val only = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "OTEL_METRICS_EXPORTER" to "prometheus"))
        assertFalse(only.active)
    }

    @Test
    fun `console 导出器按信号分开选，none 与未设都不开`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRICS_EXPORTER" to "none",
            "OTEL_LOGS_EXPORTER" to "console",
            "OTEL_TRACES_EXPORTER" to "console",
            "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
        ))
        assertFalse(config.metricsConsole)
        assertTrue(config.logsConsole)
        assertTrue(config.tracesConsole)
        assertTrue(config.tracesEnabled)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `traces 是 beta：没有 ENHANCED_TELEMETRY_BETA 就不导出，并说明原因`() {
        val base = arrayOf(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com",
        )
        val off = OtelConfig.fromEnv(env(*base))
        assertFalse(off.tracesEnabled)
        assertTrue(off.problems.any { "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" in it })

        val on = OtelConfig.fromEnv(env(*base, "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1"))
        assertTrue(on.tracesEnabled)
        assertEquals("https://c.example.com/v1/traces", on.tracesEndpoint)
        assertTrue(on.problems.isEmpty())

        // 别名也认（原文：ENABLE_ENHANCED_TELEMETRY_BETA）
        val alias = OtelConfig.fromEnv(env(*base, "ENABLE_ENHANCED_TELEMETRY_BETA" to "1"))
        assertTrue(alias.tracesEnabled)
    }

    @Test
    fun `traces 的专属端点、头部与间隔，头部与通用头部合并`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1",
            "OTEL_TRACES_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "http/json",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com",
            "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT" to "http://localhost:4318/v1/traces",
            "OTEL_EXPORTER_OTLP_HEADERS" to "A=1",
            "OTEL_EXPORTER_OTLP_TRACES_HEADERS" to "B=2",
            "OTEL_TRACES_EXPORT_INTERVAL" to "1500",
        ))
        assertEquals("http://localhost:4318/v1/traces", config.tracesEndpoint)
        assertEquals(listOf("A" to "1", "B" to "2"), config.tracesHeaders)
        assertEquals(1500L, config.traceIntervalMs)
    }

    @Test
    fun `导出间隔默认值照原文：指标 60000、日志 5000、traces 5000；非正数回到默认`() {
        val d = OtelConfig.fromEnv(env())
        assertEquals(60_000L, d.metricIntervalMs)
        assertEquals(5_000L, d.logIntervalMs)
        assertEquals(5_000L, d.traceIntervalMs)
        val set = OtelConfig.fromEnv(env("OTEL_METRIC_EXPORT_INTERVAL" to "1000", "OTEL_LOGS_EXPORT_INTERVAL" to "200"))
        assertEquals(1000L, set.metricIntervalMs)
        assertEquals(200L, set.logIntervalMs)
        val bad = OtelConfig.fromEnv(env("OTEL_METRIC_EXPORT_INTERVAL" to "0", "OTEL_LOGS_EXPORT_INTERVAL" to "-5", "OTEL_TRACES_EXPORT_INTERVAL" to "abc"))
        assertEquals(60_000L, bad.metricIntervalMs)
        assertEquals(5_000L, bad.logIntervalMs)
        assertEquals(5_000L, bad.traceIntervalMs)
    }

    @Test
    fun `只有本机文件时，beta 开着也导出 traces，没开则不`() {
        val a = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl"))
        assertFalse(a.tracesEnabled)
        val b = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl", "CLAUDE_CODE_ENHANCED_TELEMETRY_BETA" to "1"))
        assertTrue(b.tracesEnabled)
    }

    @Test
    fun `隐私开关默认全关，助手回复未设时跟随用户提示词开关`() {
        val closed = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "MIN_TELEMETRY_FILE" to "/tmp/x.jsonl"))
        assertFalse(closed.options("s").logUserPrompts)
        assertFalse(closed.options("s").logAssistantResponses)
        assertFalse(closed.options("s").logToolDetails)
        assertTrue(closed.options("s").includeSessionId)
        assertEquals(61440, closed.options("s").contentMaxLength)

        val prompts = OtelConfig.fromEnv(env("CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "OTEL_LOG_USER_PROMPTS" to "1"))
        assertTrue(prompts.options("s").logUserPrompts)
        assertTrue(prompts.options("s").logAssistantResponses)

        val kept = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1", "OTEL_LOG_USER_PROMPTS" to "1", "OTEL_LOG_ASSISTANT_RESPONSES" to "0",
        ))
        assertFalse(kept.options("s").logAssistantResponses)
    }

    @Test
    fun `令牌不进入配置的字符串形式，也不进入问题说明`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRICS_EXPORTER" to "otlp",
            "OTEL_EXPORTER_OTLP_PROTOCOL" to "grpc",
            "OTEL_EXPORTER_OTLP_ENDPOINT" to "https://c.example.com",
            "OTEL_EXPORTER_OTLP_HEADERS" to "Authorization=Bearer SECRET-TOKEN-123",
        ))
        assertFalse(config.toString().contains("SECRET-TOKEN-123"))
        assertTrue(config.toString().contains("***"))
        assertFalse(config.problems.joinToString().contains("SECRET-TOKEN-123"))
    }

    @Test
    fun `间隔与资源属性解析，资源属性从 OTEL_RESOURCE_ATTRIBUTES 读`() {
        val config = OtelConfig.fromEnv(env(
            "CLAUDE_CODE_ENABLE_TELEMETRY" to "1",
            "OTEL_METRIC_EXPORT_INTERVAL" to "1000",
            "OTEL_RESOURCE_ATTRIBUTES" to "team=mobile,env=dev",
        ))
        assertEquals(1000L, config.metricIntervalMs)
        assertEquals(5000L, config.logIntervalMs)
        assertEquals(listOf("team" to "mobile", "env" to "dev"), config.resourceAttributes)
        assertNotNull(config)
    }
}
