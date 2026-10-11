package dev.min.agent.otel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * OTLP/HTTP JSON 编码（ProtoJSON 形状：驼峰字段，int64 与纳秒时间戳用字符串）。
 * 不引 OpenTelemetry SDK：仓库没有它，而我们只需要 metrics 与 logs 两类，编码几十行即可。
 */
internal object OtlpJson {
    /** 指标：CC 的指标都是计数器，按增量（DELTA = 1）发。同一批里同名同属性的先求和，减少数据点 */
    fun metrics(items: List<OtelMetric>, resource: List<Pair<String, String>>): String {
        val byName = LinkedHashMap<String, MutableList<OtelMetric>>()
        for (m in items) byName.getOrPut(m.name) { mutableListOf() } += m
        val metrics = byName.map { (name, group) ->
            val points = LinkedHashMap<Map<String, OtelValue>, Point>()
            for (m in group) {
                val p = points[m.attributes]
                points[m.attributes] = if (p == null) Point(m.value, m.timeMs, m.timeMs) else Point(
                    p.sum + m.value, minOf(p.startMs, m.timeMs), maxOf(p.endMs, m.timeMs),
                )
            }
            JsonObject(mapOf(
                "name" to JsonPrimitive(name),
                "unit" to JsonPrimitive(group.first().unit),
                "sum" to JsonObject(mapOf(
                    "aggregationTemporality" to JsonPrimitive(DELTA),
                    "isMonotonic" to JsonPrimitive(true),
                    "dataPoints" to JsonArray(points.map { (attrs, p) ->
                        JsonObject(mapOf(
                            "attributes" to keyValues(attrs),
                            "startTimeUnixNano" to JsonPrimitive(nanos(p.startMs)),
                            "timeUnixNano" to JsonPrimitive(nanos(p.endMs)),
                            "asDouble" to JsonPrimitive(p.sum),
                        ))
                    }),
                )),
            ))
        }
        return envelope("resourceMetrics", "metrics", metrics, resource)
    }

    fun logs(items: List<OtelLog>, resource: List<Pair<String, String>>): String {
        val records = items.map { log ->
            val attrs = LinkedHashMap<String, OtelValue>()
            attrs["event.name"] = OtelValue.Text(log.name)
            attrs.putAll(log.attributes)
            JsonObject(mapOf(
                "timeUnixNano" to JsonPrimitive(nanos(log.timeMs)),
                "observedTimeUnixNano" to JsonPrimitive(nanos(log.timeMs)),
                "severityNumber" to JsonPrimitive(SEVERITY_INFO),
                "severityText" to JsonPrimitive("INFO"),
                "body" to JsonObject(mapOf("stringValue" to JsonPrimitive(log.name))),
                "attributes" to keyValues(attrs),
            ))
        }
        return envelope("resourceLogs", "logRecords", records, resource)
    }

    /** span：traceId / spanId 是十六进制字符串（OTLP JSON 的约定），kind = INTERNAL，状态 ERROR 时带说明 */
    fun spans(items: List<OtelSpan>, resource: List<Pair<String, String>>): String {
        val spans = items.map { span ->
            JsonObject(buildMap {
                put("traceId", JsonPrimitive(span.traceId))
                put("spanId", JsonPrimitive(span.spanId))
                span.parentSpanId?.let { put("parentSpanId", JsonPrimitive(it)) }
                put("name", JsonPrimitive(span.name))
                put("kind", JsonPrimitive(SPAN_KIND_INTERNAL))
                put("startTimeUnixNano", JsonPrimitive(nanos(span.startMs)))
                put("endTimeUnixNano", JsonPrimitive(nanos(span.endMs)))
                put("attributes", keyValues(span.attributes))
                if (span.events.isNotEmpty()) put("events", JsonArray(span.events.map { e ->
                    JsonObject(mapOf(
                        "timeUnixNano" to JsonPrimitive(nanos(e.timeMs)),
                        "name" to JsonPrimitive(e.name),
                        "attributes" to keyValues(e.attributes),
                    ))
                }))
                span.traceState?.let { put("traceState", JsonPrimitive(it)) }
                span.error?.let {
                    put("status", JsonObject(mapOf("code" to JsonPrimitive(STATUS_ERROR), "message" to JsonPrimitive(it))))
                }
            })
        }
        return envelope("resourceSpans", "spans", spans, resource)
    }

    /** 本机 JSONL 的一行：不是 OTLP 形状，是给人看、给脚本读的平铺形状 */
    fun fileLine(item: OtelItem): String {
        val attrs = JsonObject(item.attributes.mapValues { (_, v) -> value(v) })
        val json = when (item) {
            is OtelMetric -> JsonObject(mapOf(
                "t" to JsonPrimitive(item.timeMs),
                "kind" to JsonPrimitive("metric"),
                "name" to JsonPrimitive(item.name),
                "unit" to JsonPrimitive(item.unit),
                "value" to JsonPrimitive(item.value),
                "attributes" to attrs,
            ))
            is OtelLog -> JsonObject(mapOf(
                "t" to JsonPrimitive(item.timeMs),
                "kind" to JsonPrimitive("log"),
                "name" to JsonPrimitive(item.name),
                "attributes" to attrs,
            ))
            is OtelSpan -> JsonObject(buildMap {
                put("t", JsonPrimitive(item.endMs))
                put("kind", JsonPrimitive("span"))
                put("name", JsonPrimitive(item.name))
                put("trace_id", JsonPrimitive(item.traceId))
                put("span_id", JsonPrimitive(item.spanId))
                item.parentSpanId?.let { put("parent_span_id", JsonPrimitive(it)) }
                put("start_ms", JsonPrimitive(item.startMs))
                put("end_ms", JsonPrimitive(item.endMs))
                item.error?.let { put("error", JsonPrimitive(it)) }
                if (item.events.isNotEmpty()) put("events", JsonArray(item.events.map { e ->
                    JsonObject(mapOf(
                        "t" to JsonPrimitive(e.timeMs),
                        "name" to JsonPrimitive(e.name),
                        "attributes" to JsonObject(e.attributes.mapValues { (_, v) -> value(v) }),
                    ))
                }))
                item.traceState?.let { put("trace_state", JsonPrimitive(it)) }
                put("attributes", attrs)
            })
        }
        return json.toString()
    }

    private class Point(val sum: Double, val startMs: Long, val endMs: Long)

    private fun envelope(outer: String, inner: String, records: List<JsonElement>, resource: List<Pair<String, String>>): String {
        val res = JsonObject(mapOf("attributes" to keyValues(resourceAttrs(resource))))
        val scope = JsonObject(mapOf("name" to JsonPrimitive(OTEL_SCOPE_NAME)))
        val scoped = JsonObject(mapOf("scope" to scope, inner to JsonArray(records)))
        return JsonObject(mapOf(
            outer to JsonArray(listOf(JsonObject(mapOf(
                "resource" to res,
                (when (outer) {
                    "resourceMetrics" -> "scopeMetrics"
                    "resourceSpans" -> "scopeSpans"
                    else -> "scopeLogs"
                }) to JsonArray(listOf(scoped)),
            )))),
        )).toString()
    }

    private fun resourceAttrs(extra: List<Pair<String, String>>): Map<String, OtelValue> {
        val out = LinkedHashMap<String, OtelValue>()
        out["service.name"] = OtelValue.Text("min-agent")
        for ((k, v) in extra) out[k] = OtelValue.Text(v)
        return out
    }

    private fun keyValues(attrs: Map<String, OtelValue>): JsonArray = JsonArray(attrs.map { (k, v) ->
        JsonObject(mapOf("key" to JsonPrimitive(k), "value" to value(v)))
    })

    private fun value(v: OtelValue): JsonObject = when (v) {
        is OtelValue.Text -> JsonObject(mapOf("stringValue" to JsonPrimitive(v.value)))
        // int64 在 ProtoJSON 里是字符串
        is OtelValue.Whole -> JsonObject(mapOf("intValue" to JsonPrimitive(v.value.toString())))
        is OtelValue.Real -> JsonObject(mapOf("doubleValue" to JsonPrimitive(v.value)))
        is OtelValue.Flag -> JsonObject(mapOf("boolValue" to JsonPrimitive(v.value)))
        is OtelValue.Texts -> JsonObject(mapOf("arrayValue" to JsonObject(mapOf("values" to JsonArray(v.value.map { JsonObject(mapOf("stringValue" to JsonPrimitive(it))) })))))
    }

    private fun nanos(ms: Long): String = (ms * 1_000_000L).toString()

    private const val DELTA = 1
    private const val SPAN_KIND_INTERNAL = 1
    private const val STATUS_ERROR = 2
    private const val SEVERITY_INFO = 9
}
