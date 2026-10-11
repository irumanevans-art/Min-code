package dev.min.agent.kernel

import dev.min.agent.ext.config.AvailableModels
import dev.min.agent.ext.config.ModelPolicy
import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.llm.catalog.ModelAliases
import dev.min.agent.protocol.ModelRef

/**
 * 别名 + 模型限制（[ModelPolicy]）一起算「这个选择落到哪个型号」，CLI 与 App 两个宿主共用（CC 模型配置文档
 * 「Restrict model selection」）：
 * - 点名的选择（[named]）：别名先解析成这家供应商目录里的型号；被名单挡住时，家族别名（opus / sonnet / haiku / fable）
 *   换成名单允许的同家族最新版并说明换了什么，换不到就是 [Result.Blocked]，由调用方按「在哪设的」处理；
 * - 默认选项（[default]）：这家供应商的默认档（opus 档；目录里没有就是目录里这家的第一个型号）；被挡时按原文逐级退：
 *   同家族允许的最新版 → sonnet → haiku → 名单里第一个允许的条目；都没有就是 [Result.Blocked]（CC：拒绝启动并点名该改的键）。
 *
 * 「最新版」= 标了这一档的那个，其次按型号 id 倒序。非 Anthropic 的型号认不出家族，不做同家族替换。
 */
object ModelChoice {
    sealed interface Result {
        /** [ref] 是落到的型号（`opusplan` 原样留着别名，见 [named]）；[substitutedFrom] 非空 = 原来要的被挡、换成了它 */
        data class Ok(val ref: ModelRef, val oneMillion: Boolean = false, val substitutedFrom: ModelRef? = null) : Result
        /** 名单挡住、也换不到允许的版本 */
        data object Blocked : Result
        /** 别名在这家供应商目录里没有这一档、或这家目录里一个型号都没有：配置问题，不是名单问题 */
        data class Unknown(val message: String) : Result
    }

    fun named(ref: ModelRef, catalog: Catalog, policy: ModelPolicy): Result {
        val (name, oneMillion) = ModelAliases.splitSuffix(ref.modelId)
        if (name == "default") return default(ref.providerId, catalog, policy)
        // opusplan 是「随模式切换」的组合别名：会话里留着别名本身（内核按计划模式展开，见 planAwareModel），
        // 这里只看两个档至少有一个能用；各档被挡时的替换在展开后的解析里再做
        if (name == "opusplan") {
            val plan = named(ModelRef(ref.providerId, "opus"), catalog, policy)
            val run = named(ModelRef(ref.providerId, "sonnet"), catalog, policy)
            return when {
                run is Result.Ok -> Result.Ok(ref, oneMillion)
                run is Result.Unknown -> run
                plan is Result.Ok -> Result.Ok(ref, oneMillion)
                else -> Result.Blocked
            }
        }
        val resolved = try {
            ModelAliases.resolve(ref, catalog)
        } catch (e: IllegalArgumentException) {
            return Result.Unknown(e.message ?: "别名 ${ref.modelId} 解析不了")
        }
        if (policy.allows(resolved.ref.modelId)) return Result.Ok(resolved.ref, resolved.oneMillion)
        if (!ModelAliases.isAlias(name)) return Result.Blocked
        val family = AvailableModels.family(name) ?: AvailableModels.family(resolved.ref.modelId) ?: return Result.Blocked
        val substitute = newestPermitted(ref.providerId, family, catalog, policy::allows, policy) ?: return Result.Blocked
        return Result.Ok(ModelRef(ref.providerId, substitute), resolved.oneMillion, substitutedFrom = resolved.ref)
    }

    fun default(providerId: String, catalog: Catalog, policy: ModelPolicy): Result {
        val ids = catalog.models.filter { it.ref.providerId == providerId }.map { it.ref.modelId }
        val wanted = catalog.models.firstOrNull { it.ref.providerId == providerId && it.tier == "opus" }?.ref?.modelId
            ?: ids.firstOrNull()
            ?: return Result.Unknown("供应商 $providerId 的目录里没有型号，请用 --model 指定")
        val wantedRef = ModelRef(providerId, wanted)
        if (policy.allowsDefault(wanted)) return Result.Ok(wantedRef)
        val steps = listOfNotNull(AvailableModels.family(wanted), "sonnet", "haiku").distinct()
        for (family in steps) {
            newestPermitted(providerId, family, catalog, policy::allowsDefault)?.let { return Result.Ok(ModelRef(providerId, it), substitutedFrom = wantedRef) }
        }
        val firstListed = policy.listing?.entries.orEmpty().firstNotNullOfOrNull { entry ->
            val concrete = try { ModelAliases.resolve(ModelRef(providerId, entry), catalog).ref.modelId } catch (_: IllegalArgumentException) { entry }
            concrete.takeIf { policy.allowsDefault(it) && (it in ids || it.startsWith("claude-")) }
        }
        return firstListed?.let { Result.Ok(ModelRef(providerId, it), substitutedFrom = wantedRef) } ?: Result.Blocked
    }

    /** 这家供应商目录里 [family] 家族第一个（最新的）被 [allowed] 放行的型号；目录里没有时退到名单里点名的同家族版本 */
    private fun newestPermitted(providerId: String, family: String, catalog: Catalog, allowed: (String) -> Boolean, policy: ModelPolicy? = null): String? =
        // 目录顺序不保证新旧（同一家族里旧版可能排在前面）：标了这一档的（当前版）在先，其余按 id 倒序（版本号大的在前）
        catalog.models.filter { it.ref.providerId == providerId && AvailableModels.family(it.ref.modelId) == family }
            .sortedWith(compareByDescending<dev.min.agent.llm.ModelProfile> { it.tier == family }.thenByDescending { it.ref.modelId })
            .map { it.ref.modelId }
            .firstOrNull(allowed)
            // CC：`["sonnet","claude-opus-4-6"]` 下 opus 选到 4.6——名单点名的旧版本目录里未必有
            ?: policy?.listing?.entries.orEmpty().map { it.trim() }.firstOrNull { it.startsWith("claude-") && AvailableModels.family(it) == family && allowed(it) }
}
