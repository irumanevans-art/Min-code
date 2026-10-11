package dev.min.code.ui.session

import androidx.annotation.StringRes
import dev.min.code.R
import dev.min.code.core.engine.CacheBreakCause
import dev.min.code.core.engine.CacheBreakView

/** 用量段里「最近一次缓存失效」下面的一行原因：资源 id + 参数，文案由界面取 */
internal data class CacheLine(@StringRes val res: Int, val args: List<String> = emptyList())

/**
 * 失效原因 -> 一行一条。原因来自内核从日志里比出来的事实（`CacheWatch`），这里只翻译、不补充；
 * 一条原因都没有时照实写「日志里没有能解释的变化」，不替供应商找理由。纯函数，单测直接调。
 */
internal fun cacheBreakLines(view: CacheBreakView): List<CacheLine> {
    if (view.causes.isEmpty()) return listOf(CacheLine(R.string.cache_break_unknown))
    return view.causes.map { cause ->
        when (cause) {
            CacheBreakCause.ModelChanged -> CacheLine(R.string.cache_break_model, listOf(view.fromModel, view.toModel))
            CacheBreakCause.ProviderChanged -> CacheLine(R.string.cache_break_provider, listOf(view.fromModel, view.toModel))
            CacheBreakCause.PrefixChanged -> CacheLine(R.string.cache_break_prefix)
            CacheBreakCause.Compacted -> CacheLine(R.string.cache_break_compacted)
            CacheBreakCause.OutputsMasked -> CacheLine(R.string.cache_break_masked)
            CacheBreakCause.Rewound -> CacheLine(R.string.cache_break_rewound)
            CacheBreakCause.EffortChanged -> CacheLine(R.string.cache_break_effort)
            // 只写间隔这一事实：供应商那边清没清，内核看不见
            CacheBreakCause.Idle -> CacheLine(R.string.cache_break_idle, listOf((view.idleMs / 60_000).toString()))
        }
    }
}
