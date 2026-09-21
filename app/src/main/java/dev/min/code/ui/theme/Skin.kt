package dev.min.code.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.min.code.R
import dev.min.code.core.settings.SkinStyle
import me.rerere.highlight.HighlightTextColorPalette

/**
 * # 一套风格 = 四件东西
 *
 * | | |
 * |---|---|
 * | [light] / [dark] | 三种物质的取值（[SeaPalette]），Ink* 组件读它 |
 * | [lightColors] / [darkColors] | Material 自己还活着的那些地方读的 colorScheme |
 * | [plate] | 取底纹理。「强调色不是色值」这条规则换了底之后原样成立 |
 * | [plateWidthFactor] | 纹理宽铺到屏宽的几倍。横构图靠 MIRROR 平铺补，竖构图要一次盖满 |
 *
 * colorScheme **非换不可**：Ink* 组件读 [LocalSea]，但 Material 自己那些还活着的地方
 * （DropdownMenu 的面、TextField 的光标、进度、涟漪）读的是 colorScheme。只换前者的话，
 * 换了风格的页面上会冒出几处上一套的颜色，而且专挑不受控的地方冒。
 *
 * 这个抽象是从 `CloudTheme` 长出来的 —— 那个函数做的就是这四件事，只不过写死成了
 * 「Codex 页换成云」。参数化之后，Codex 页不必特殊，而云也不必只属于 Codex。
 */
@Immutable
data class Skin(
    val style: SkinStyle,
    val light: SeaPalette,
    val dark: SeaPalette,
    val lightColors: ColorScheme,
    val darkColors: ColorScheme,
    @DrawableRes val plate: Int,
    val plateWidthFactor: Float,
    /**
     * 首页那个巨大的 Min 字标该对准纹理的哪一块。
     *
     * 全 App 唯一一处**指定看哪里**的窗（别处都按元素在屏幕上的位置取，那样相邻两扇窗
     * 看到的才是连续的一片）。字标是展示性的，它得落在这张底最好看的地方：海是金脉，
     * 云是中上那片破光，陶是丝络最密的一带。
     *
     * span 三套一致，字标里纹理的粗细才不会一套一个样。
     */
    val heroWindow: SeaWindow,
    val codeLight: HighlightTextColorPalette,
    val codeDark: HighlightTextColorPalette,
) {
    fun palette(dark: Boolean): SeaPalette = if (dark) this.dark else light
    fun colors(dark: Boolean): ColorScheme = if (dark) darkColors else lightColors
    fun code(dark: Boolean): HighlightTextColorPalette = if (dark) codeDark else codeLight
}

/** 字标那扇窗占纹理宽的比例。三套一致，字里纹理的粗细才不会一套一个样 */
private const val HERO_SPAN = 0.62f

object Skins {
    /** 海：蓝色取底那张酒精墨，带金脉。1.3 倍靠 MIRROR 平铺补，墨纹看不出对称 */
    val Sea = Skin(
        style = SkinStyle.SEA,
        light = LightSea,
        dark = DarkSea,
        lightColors = LightColors,
        darkColors = DarkColors,
        plate = R.drawable.sea_plate,
        plateWidthFactor = SEA_WIDTH_FACTOR,
        heroWindow = SeaWindow.Fixed(0.50f, 0.45f, HERO_SPAN),
        codeLight = InkCodeLight,
        codeDark = InkCodeDark,
    )

    /** 云：灰白仙境压出来的云海。竖构图，2.2 倍一次盖满一屏，不进平铺 */
    val Cloud = Skin(
        style = SkinStyle.CLOUD,
        light = LightCloud,
        dark = DarkCloud,
        lightColors = LightCloudColors,
        darkColors = DarkCloudColors,
        plate = R.drawable.cloud_plate,
        plateWidthFactor = CLOUD_WIDTH_FACTOR,
        heroWindow = SeaWindow.Fixed(0.55f, 0.33f, HERO_SPAN),
        codeLight = CloudCodeLight,
        codeDark = CloudCodeDark,
    )

    /** 陶：Anthropic 的 ivory / slate / clay。横构图，和海同尺寸同铺法 */
    val Clay = Skin(
        style = SkinStyle.ANTHROPIC,
        light = LightClay,
        dark = DarkClay,
        lightColors = LightClayColors,
        darkColors = DarkClayColors,
        plate = R.drawable.clay_plate,
        plateWidthFactor = SEA_WIDTH_FACTOR,
        heroWindow = SeaWindow.Fixed(0.34f, 0.47f, HERO_SPAN),
        codeLight = ClayCodeLight,
        codeDark = ClayCodeDark,
    )

    val all = listOf(Sea, Cloud, Clay)

    fun of(style: SkinStyle): Skin = when (style) {
        SkinStyle.SEA -> Sea
        SkinStyle.CLOUD -> Cloud
        SkinStyle.ANTHROPIC -> Clay
    }
}

/**
 * 当前风格。[MinTheme] 在根上 provide。
 *
 * 默认是海，和 `AppSettings.skin` 的默认值一致 —— 两处要是分开写，
 * 设置还没读出来的那一帧就会闪一下另一套颜色。
 */
val LocalSkin = staticCompositionLocalOf { Skins.Sea }
