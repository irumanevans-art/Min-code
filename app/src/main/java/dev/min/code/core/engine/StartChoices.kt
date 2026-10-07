package dev.min.code.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 开始面板上某个引擎专有的选项。现在只有 Min 有：供应商档、各档能选的模型、权限模式
 * （Claude Code 的开始面板只有「跳过权限确认」，沿用上次的其余设置，不需要它）。
 *
 * 为什么放在 `core/engine` 而不是让界面去问 Min 适配器：规矩 1 ——界面只认这个包里的类型，引擎自己把设置里的档案
 * 与目录翻译成这份表。设置一变（连了新的供应商档、删了一个档）引擎重算，开始面板自己跟上。
 *
 * @param defaultProfileId 默认选中的档：最近一次用的 > 全局当前档 > 第一个能用的（设计 10.2 拍板 3）
 * @param modes 权限模式表（与设置面板同一份，Auto 不列）
 */
data class StartChoices(
    val profiles: List<StartProfile>,
    val defaultProfileId: String?,
    val modes: List<Choice>,
    val defaultModeId: String,
)

/**
 * 一个能用的供应商档。[models] 是这个档能选的模型（档案 env 里写的加目录里对得上的），可能为空——
 * 那就让用户手填 id（不在目录里的模型能力按保守值，[Choice.warning] 标出）。
 * [defaultModelId] 是这个档上次选的，没有就取 [models] 第一个。
 */
data class StartProfile(
    val id: String,
    val label: String,
    val models: List<Choice>,
    val defaultModelId: String?,
)

/** 没有引擎专有开始选项的引擎共用这一个常量流 */
internal val NoStartChoices: StateFlow<StartChoices?> = MutableStateFlow(null)
