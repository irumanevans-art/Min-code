package dev.min.agent.llm.catalog

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

        // T2 填完数据后打开：两个列表都非空
        // assertTrue("providers.json 不应为空", catalog.providers.isNotEmpty())
        // assertTrue("models.json 不应为空", catalog.models.isNotEmpty())

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
    }
}
