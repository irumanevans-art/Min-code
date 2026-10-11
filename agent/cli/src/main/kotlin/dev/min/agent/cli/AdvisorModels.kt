package dev.min.agent.cli

import dev.min.agent.llm.catalog.Catalog
import dev.min.agent.protocol.ModelRef
import dev.min.agent.spi.AutoModeReviewer

/** 顾问只发纯文本：目录外的 id 用保守操作窗口、不借用别家价格；有 USD 预算时 ModelBilling 会拒绝未知价格。 */
internal fun resolveAdvisorSelection(ref: ModelRef, catalog: Catalog, session: ModelSelection): ModelSelection =
    if (ref == session.profile.ref) session else
        resolveReviewerSelection(AutoModeReviewer(ref, contextWindow = 16_384, maxOutputTokens = 4_096), catalog, session)
