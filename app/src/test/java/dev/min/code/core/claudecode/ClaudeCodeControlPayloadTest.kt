package dev.min.code.core.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * control_response 载荷的解析。
 *
 * **这些夹具是从真机 CLI v2.1.246 抓下来的原样报文**，不是照直觉编的 —— 第一版实现里
 * 三个载荷全读错了（模型 id 猜成 `id`、用量猜成 snake_case、花费猜成数字），
 * 是靠把报文喂给真 CLI 对拍才发现的。所以这里固化真实形状防回归。
 */
class ClaudeCodeControlPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 真机 `list_models` 应答节选 */
    private val listModelsPayload = """
        {"models":[
          {"value":"default","resolvedModel":"claude-opus-5[1m]","displayName":"Default (recommended)",
           "description":"Use the default model (currently Opus 5 (1M context)) · ${'$'}5/${'$'}25 per Mtok",
           "supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
          {"value":"opus[1m]","resolvedModel":"claude-opus-5[1m]","displayName":"Opus (1M context)",
           "description":"Opus 5 with 1M context","supportsEffort":true,
           "supportedEffortLevels":["low","medium","high","xhigh","max"]},
          {"value":"fable","resolvedModel":"claude-fable-5","displayName":"Fable",
           "description":"Fable 5 · Most capable","supportsEffort":true,
           "supportedEffortLevels":["low","medium","high","xhigh","max"]}
        ]}
    """.trimIndent()

    /**
     * 模型 id 在 `value` 里，不是 `id`/`model`/`name`。
     * 传错了 set_model 就切不动模型，而且不会报错，只是静默无效。
     */
    @Test
    fun `model options read the id from value not id`() {
        val models = json.parseToJsonElement(listModelsPayload).jsonObject["models"]!!
            .asJsonArrayOrNull()!!.mapNotNull { it.toModelOption() }

        assertEquals(3, models.size)
        assertEquals(listOf("default", "opus[1m]", "fable"), models.map { it.value })
        assertEquals("Default (recommended)", models[0].displayName)
        assertEquals("Opus (1M context)", models[1].displayName)
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            models[0].supportedEffortLevels,
        )
    }

    /** 旧版 CLI 可能给裸字符串，不能因此整个目录不可用 */
    @Test
    fun `bare string model entries still work`() {
        val models = json.parseToJsonElement("""["opus","sonnet"]""")
            .asJsonArrayOrNull()!!.mapNotNull { it.toModelOption() }
        assertEquals(listOf("opus", "sonnet"), models.map { it.value })
        assertEquals("opus", models[0].displayName)
    }

    @Test
    fun `model entries without a usable id are dropped`() {
        val models = json.parseToJsonElement("""[{"displayName":"没有 value"},{"value":"  "},{"value":"ok"}]""")
            .asJsonArrayOrNull()!!.mapNotNull { it.toModelOption() }
        assertEquals(listOf("ok"), models.map { it.value })
    }

    /**
     * `get_context_usage` 是 **camelCase**（totalTokens / maxTokens），
     * 不是 snake_case。第一版按 used_tokens/max_tokens 读，永远取不到值。
     */
    @Test
    fun `context usage payload is camelCase`() {
        val payload = json.parseToJsonElement(
            """{"categories":[{"name":"Skills","tokens":1620,"color":"warning"}],
               "totalTokens":1620,"maxTokens":200000,"rawMaxTokens":200000,
               "autocompactSource":"auto","percentage":1}"""
        ).jsonObject

        assertEquals(1620, payload["totalTokens"]?.jsonPrimitive?.intOrNull)
        assertEquals(200000, payload["maxTokens"]?.jsonPrimitive?.intOrNull)
        // 反面确认：snake_case 的名字确实不存在
        assertNull(payload["total_tokens"])
        assertNull(payload["max_tokens"])
    }

    /**
     * `get_session_cost` 返回一整段人类可读文本，不是数字字段。
     * 顶栏用正则摘第一个金额。
     */
    @Test
    fun `session cost payload is preformatted text`() {
        val payload = json.parseToJsonElement(
            """{"text":"Total cost:            ${'$'}0.1234\nTotal duration (API):  3s\nUsage: 10 input, 20 output"}"""
        ).jsonObject

        val text = payload["text"]!!.jsonPrimitive.content
        assertNull(payload["total_cost_usd"])
        val amount = Regex("""\${'$'}[0-9]+\.[0-9]+""").find(text)?.value
        assertEquals("${'$'}0.1234", amount)
    }

    /** `get_plan` 没有计划时回 `{exists:false}`，不能把它当成"有一个空计划" */
    @Test
    fun `get_plan reports absence via exists flag`() {
        val payload = json.parseToJsonElement("""{"exists":false}""").jsonObject
        assertEquals(false, payload["exists"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(payload["plan"])
    }

    /**
     * `resolvedModel` 是 CLI 解析后的真实 id（fable → claude-fable-5）。
     * setModel 靠它比对「请求的」和「实际生效的」是否一致。
     */
    @Test
    fun `model option keeps the resolved model id`() {
        val models = json.parseToJsonElement(listModelsPayload).jsonObject["models"]!!
            .asJsonArrayOrNull()!!.mapNotNull { it.toModelOption() }
        assertEquals("claude-opus-5[1m]", models[0].resolvedModel)
        assertEquals("claude-fable-5", models[2].resolvedModel)
    }

    /**
     * CLI 只把**当前选中**的模型放进 list_models，所以 Fable 没选中时压根不出现，
     * 用户会卡在「选不到 → 不列出 → 更选不到」。HIDDEN_MODEL_ALIASES 就是补这一格。
     *
     * 实测（v2.1.246，全新配置 + 中转站 env）：
     *   list_models                 → [default, opus[1m], sonnet, sonnet[1m], haiku]
     *   set_model {"model":"fable"} → success，applied.model = claude-fable-5
     *   再 list_models              → 多出 fable
     */
    @Test
    fun `hidden aliases cover the models the cli omits until selected`() {
        val values = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.map { it.value }
        assertTrue("fable" in values)
        assertTrue(ClaudeCodeManager.HIDDEN_MODEL_ALIASES.all { it.hiddenAlias })
        // 别名必须是 --model 认的值，displayName 只用于展示，不能拿去当 id
        assertTrue(ClaudeCodeManager.HIDDEN_MODEL_ALIASES.none { it.value.contains(' ') })
    }

    /** 目录里已有的项不该被别名重复列出（去重逻辑在 UI 侧按 value 比对） */
    @Test
    fun `hidden aliases do not collide with catalog values`() {
        val listed = json.parseToJsonElement(listModelsPayload).jsonObject["models"]!!
            .asJsonArrayOrNull()!!.mapNotNull { it.toModelOption() }.map { it.value }.toSet()
        val extras = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.filter { it.value !in listed }

        // 夹具里已有 fable，去重后就不该再出现（否则面板上会是两条 Fable）
        assertTrue("fable 已在目录里，不该重复补", extras.none { it.value == "fable" })
        // 夹具里没有的仍要补上
        assertTrue(extras.any { it.value == "fable[1m]" })
        // 不写死完整列表：别名会随 CLI 的 /model 输出增补，钉死会让每次扩充都误报
        assertTrue(extras.isNotEmpty())
    }

    /**
     * 别名清单要覆盖 CLI `/model` 自己报的可选值。真机 /model 输出原文：
     * "Available: sonnet, opus, haiku, fable, best, sonnet[1m], opus[1m], fable[1m],
     *  opusplan, default, or a full model ID."
     *
     * 其中 default / sonnet[1m] / opus[1m] 已在 list_models 目录里，不用补；
     * 剩下这些 CLI 不主动列，必须由我们补，否则用户选不到。
     */
    @Test
    fun `hidden aliases cover what slash model advertises`() {
        val values = ClaudeCodeManager.HIDDEN_MODEL_ALIASES.map { it.value }.toSet()
        listOf("fable", "fable[1m]", "best", "opusplan", "opus", "sonnet", "haiku").forEach {
            assertTrue("/model 报了 $it，但别名清单里没有", it in values)
        }
    }

    /** initialize 一次给齐斜杠命令与模型目录 */
    @Test
    fun `initialize payload carries commands and models`() {
        val payload = json.parseToJsonElement(
            """{"commands":[{"name":"compact","description":"压缩上下文"},{"name":"clear"}],
               "agents":[],"output_style":"normal","available_output_styles":["normal"],
               "models":[{"value":"default","displayName":"Default"}],"account":{},"pid":123}"""
        ).jsonObject

        val commands = payload["commands"]!!.asJsonArrayOrNull()!!.map { it.jsonObject }
        assertEquals("compact", commands[0]["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("压缩上下文", commands[0]["description"]?.jsonPrimitive?.contentOrNull)
        // description 可缺省
        assertNull(commands[1]["description"])

        val models = payload["models"]!!.asJsonArrayOrNull()!!.mapNotNull { it.toModelOption() }
        assertTrue(models.isNotEmpty())
        assertEquals("default", models[0].value)
    }

    // --- 中转站 GET /v1/models ---

    /** Anthropic 原版形状：带 type / display_name / created_at */
    @Test
    fun `relay models keep claude entries and drop the rest`() {
        val body = """
            {"data":[
              {"type":"model","id":"claude-fable-5-1","display_name":"Claude Fable 5.1","created_at":"2026-08-01T00:00:00Z"},
              {"type":"model","id":"claude-opus-5","display_name":"Claude Opus 5","created_at":"2026-05-01T00:00:00Z"},
              {"id":"gpt-5","object":"model"},
              {"id":"claude-opus-5"}
            ],"has_more":false}
        """.trimIndent()
        val models = ClaudeCodeManager.parseRelayModels(body)
        // gpt 被过滤，重复的 opus 只留一个，Fable 5.1 在
        assertEquals(listOf("claude-fable-5-1", "claude-opus-5"), models.map { it.id }.sorted())
        assertEquals("Claude Fable 5.1", models.first { it.id == "claude-fable-5-1" }.displayName)
    }

    /** OpenAI 风格的中转站：没有 display_name，且全是改过名的 id → 退回全量而不是列空 */
    @Test
    fun `relay models fall back to everything when nothing says claude`() {
        val body = """{"object":"list","data":[{"id":"fable-5.1","object":"model"},{"id":"opus-5","object":"model"}]}"""
        val models = ClaudeCodeManager.parseRelayModels(body)
        assertEquals(2, models.size)
        assertNull(models[0].displayName)
    }

    /** 真实中转站的目录：display_name 等于 id 时视为没有；顺序 fable > opus > sonnet > haiku，同系列新的在前 */
    @Test
    fun `relay models are ordered by family then version`() {
        val ids = listOf(
            "claude-3-5-haiku-20241022", "claude-sonnet-4-5-20250929", "claude-opus-4-1-20250805",
            "claude-fable-5", "claude-opus-5", "claude-sonnet-5", "claude-fable-5-1", "claude-opus-4-8",
        )
        val body = """{"data":[${ids.joinToString(",") { "{\"id\":\"$it\",\"display_name\":\"$it\"}" }}]}"""
        val models = ClaudeCodeManager.parseRelayModels(body)
        assertEquals(
            listOf(
                "claude-fable-5-1", "claude-fable-5",
                "claude-opus-5", "claude-opus-4-8", "claude-opus-4-1-20250805",
                "claude-sonnet-5", "claude-sonnet-4-5-20250929",
                "claude-3-5-haiku-20241022",
            ),
            models.map { it.id },
        )
        assertTrue(models.all { it.displayName == null })
    }

    @Test(expected = IllegalStateException::class)
    fun `relay models reject a non list body`() {
        // 中转站把 /v1/models 当成未知路径返回一段 HTML/错误 JSON 时，必须报错而不是当成空列表
        ClaudeCodeManager.parseRelayModels("""{"error":{"message":"not found"}}""")
    }
}
