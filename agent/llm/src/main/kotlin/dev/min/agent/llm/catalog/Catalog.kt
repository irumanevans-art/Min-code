package dev.min.agent.llm.catalog

import dev.min.agent.llm.ModelProfile
import dev.min.agent.llm.ProviderDescriptor
import dev.min.agent.protocol.AgentJson
import dev.min.agent.protocol.ModelRef
import kotlinx.serialization.builtins.ListSerializer

/** 随包的供应商与模型目录。用户与中转的覆盖在 M4 做，这里只读随包数据 */
data class Catalog(val providers: List<ProviderDescriptor>, val models: List<ModelProfile>) {
    fun provider(id: String): ProviderDescriptor? = providers.firstOrNull { it.id == id }
    fun model(ref: ModelRef): ModelProfile? = models.firstOrNull { it.ref == ref }

    companion object {
        /** 从 classpath 读 `dev/min/agent/llm/catalog/providers.json` 与 `models.json` */
        fun bundled(): Catalog {
            // 随包数据坏了是构建问题：读不到或解析失败直接抛，让测试在第一时间暴露
            val providers = AgentJson.decodeFromString(
                ListSerializer(ProviderDescriptor.serializer()),
                readResource("providers.json"),
            )
            val models = AgentJson.decodeFromString(
                ListSerializer(ModelProfile.serializer()),
                readResource("models.json"),
            )
            return Catalog(providers, models)
        }

        private fun readResource(name: String): String {
            val stream = Catalog::class.java.getResourceAsStream("/dev/min/agent/llm/catalog/$name")
                ?: throw IllegalStateException("bundled catalog resource missing: dev/min/agent/llm/catalog/$name")
            return stream.use { it.readBytes().decodeToString() }
        }
    }
}
