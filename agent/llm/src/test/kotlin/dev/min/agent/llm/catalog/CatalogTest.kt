package dev.min.agent.llm.catalog

import dev.min.agent.llm.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包目录的健全性：两个 JSON 都能加载、id / ref 唯一、引用一致、数值边界合理。
 * 数据本身的事实出处见 resources/dev/min/agent/llm/catalog/SOURCES.md。
 */
class CatalogTest {

    @Test
    fun `bundled catalog loads and is internally consistent`() {
        val catalog = Catalog.bundled()

        // 两个列表都非空（T1 空目录期间这条先关着，数据落地后打开）
        assertTrue("providers.json 不应为空", catalog.providers.isNotEmpty())
        assertTrue("models.json 不应为空", catalog.models.isNotEmpty())

        // id / ref 唯一
        assertEquals(
            "供应商 id 重复：${catalog.providers.map { it.id }}",
            catalog.providers.size,
            catalog.providers.map { it.id }.distinct().size,
        )
        assertEquals(
            "模型 ref 重复：${catalog.models.map { it.ref }}",
            catalog.models.size,
            catalog.models.map { it.ref }.distinct().size,
        )

        // 每个模型都挂在已收录的供应商下
        val providerIds = catalog.providers.map { it.id }.toSet()
        for (model in catalog.models) {
            assertTrue(
                "模型 ${model.ref} 的 providerId 不在供应商目录里",
                model.ref.providerId in providerIds,
            )
        }

        // OpenAI Chat 协议的供应商（本家 openai 与各家 -openai 后缀）必须用 openai_chat 线协议
        for (provider in catalog.providers) {
            if (provider.id == "openai" || provider.id.endsWith("-openai")) {
                assertEquals(
                    "供应商 ${provider.id} 应为 openai_chat 线协议",
                    Wire.OpenAIChat,
                    provider.wire,
                )
            }
        }

        // baseUrl 形如 https://host[/path]，不带到别的斜杠结尾（编解码器自己拼 /v1/messages）
        for (provider in catalog.providers) {
            assertTrue(
                "baseUrl 不合法：${provider.id} -> ${provider.baseUrl}",
                provider.baseUrl.startsWith("https://") && !provider.baseUrl.endsWith("/"),
            )
        }

        // 数值边界
        for (model in catalog.models) {
            assertTrue(
                "上下文窗口应大于最大输出：${model.ref} ${model.contextWindow} !> ${model.maxOutputTokens}",
                model.contextWindow > model.maxOutputTokens,
            )
            assertTrue("最大输出应大于 0：${model.ref}", model.maxOutputTokens > 0)
        }

        // effortPatches 的值都是非空 JSON 对象（深合并进请求体的片段）
        for (model in catalog.models) {
            for ((effort, patch) in model.effortPatches) {
                assertTrue(
                    "effortPatches[${effort}] 应为非空 JSON 对象：${model.ref}",
                    patch is kotlinx.serialization.json.JsonObject && patch.isNotEmpty(),
                )
            }
        }

        // pricing（可选字段）：价格为正、asOf 是日期、分档边界落在 0 与上下文窗口之间
        for (model in catalog.models) {
            val pricing = model.pricing ?: continue
            assertTrue(
                "标价应为正：${model.ref} ${pricing.inputPerMTok}/${pricing.outputPerMTok}",
                pricing.inputPerMTok > 0 && pricing.outputPerMTok > 0,
            )
            pricing.cacheWritePerMTok?.let { assertTrue("缓存写价应为正：${model.ref} $it", it > 0) }
            pricing.cacheWrite1hPerMTok?.let { assertTrue("缓存写 1h 价应为正：${model.ref} $it", it > 0) }
            pricing.cacheReadPerMTok?.let { assertTrue("缓存读价应为正：${model.ref} $it", it > 0) }
            assertTrue(
                "asOf 应为 ISO 日期：${model.ref} ${pricing.asOf}",
                Regex("""\d{4}-\d{2}-\d{2}""").matches(pricing.asOf),
            )
            for (tier in pricing.tiers) {
                assertTrue(
                    "分档边界应在 0 与上下文窗口之间：${model.ref} ${tier.aboveInputTokens} (窗口 ${model.contextWindow})",
                    tier.aboveInputTokens in 1 until model.contextWindow.toLong(),
                )
            }
        }
    }
}
