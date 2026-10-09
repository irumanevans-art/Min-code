package dev.min.agent.tools.web

import dev.min.agent.spi.PageContent
import dev.min.agent.spi.PageConverter

/**
 * host 只认识 SPI 的 [PageConverter]，不依赖 tools；HTML 转换器在各宿主的组合根注入。
 * 桌面 CLI 和 Android 的 Min 引擎要的是同一份适配，写在这里一处，免得两个组合根各抄一遍
 * （转换器是纯 Kotlin 手写的，不用反射也不带原生库，两边都能直接跑）。
 */
val HtmlPageConverter = PageConverter { html, baseUrl ->
    val converted = HtmlToMarkdown.convert(html, baseUrl)
    PageContent(converted.title, converted.markdown)
}
