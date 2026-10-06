package dev.min.agent.llm

/**
 * 「这个模型用不了」的响应特征（模型不存在、已退役、账号没开通），各家编解码器共用一份判据。
 *
 * 为什么单列一类而不并进 BadRequest：请求本身没毛病，换个模型就能成，模型回退链据此换下一个；
 * 而 BadRequest 是请求格式有问题，换模型也一样挂，不该回退。
 * 判据故意收得窄：404 必须带「model」字样（地址写错的 404 通常没有）；其余 4xx 只认各家明确写出的措辞。
 */
internal object UnavailableModel {
    private val STRONG = listOf(
        "model_not_found", "model not found", "model_not_available", "unknown model", "invalid model",
        "no such model", "model_decommissioned", "model_deprecated",
        "model.{0,80}(has been|was|is) (deprecated|retired|decommissioned|discontinued)",
        "model.{0,80}(is )?no longer (available|supported)",
        "model.{0,80}does not exist",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val MENTIONS_MODEL = Regex("model", RegexOption.IGNORE_CASE)

    fun matches(status: Int?, body: String): Boolean {
        if (status == 404 && MENTIONS_MODEL.containsMatchIn(body)) return true
        // 401 / 403 是鉴权、429 是限流、408 / 413 另有归类：就算正文里碰巧有「model」也不算模型不可用
        if (status != null && (status !in 400..499 || status in EXCLUDED)) return false
        return STRONG.any { it.containsMatchIn(body) }
    }

    private val EXCLUDED = setOf(401, 403, 408, 413, 429)
}
