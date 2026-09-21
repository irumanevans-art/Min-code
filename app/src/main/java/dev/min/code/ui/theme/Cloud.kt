package dev.min.code.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.highlight.HighlightTextColorPalette

/**
 * # 云：灰白仙境
 *
 * 「海」是蓝色取底那张酒精墨，界面上每一块强调色都是从它上面镂出来的。
 * 云换一张底：一幅灰白的云海（`tools/build_cloud_plate.py` 从「灰白仙境」压出来）。
 * 规则一字不改——每一块强调色仍然是「把纸镂空成那个形状盖在纹理上」，
 * 只是窗外面那片东西从海变成了云。
 *
 * 所以这里没有新的组件，也没有一个 `cloudInk()`：[Modifier.seaInk] / [Modifier.seaFill]
 * 读 [LocalSeaPlate]，换掉它，整棵子树——会话流左边那条轨道、发送键里流动的窗、
 * 所有 Ink* 控件——自己就成了云。
 *
 * 它最初是 Codex 页专属的一张皮（`CloudTheme` 把它罩在那一页上）。现在三套取底
 * 都是全局可选的一种风格，Codex 页跟着全局走 —— 喜欢这套灰白的人，在 Claude 页
 * 也该能用它，而「两个引擎必须长得不一样」本来就不是一条必要的规矩。
 * 组装在 [Skins]。
 *
 * ## 为什么不是简单地"把蓝改成灰"
 *
 * 云纹理的明度分布是照着 sea_plate 直方图匹配出来的（mean 148、std 28 两边对齐到小数点后一位），
 * 彩度则从 152 掉到 10。这意味着现有每一处 alpha、每一处对比度假设原样成立：
 * 海上白字能读，云上白字就能读。换底不需要把组件重调一遍——这正是当初把蓝做成
 * 「一扇窗」而不是一个色值换来的。
 *
 * 两套配色共用朱（[SeaPalette.vermilion]）与竹青（[SeaPalette.bamboo]）：
 * 判定和 diff 不分引擎，一个用户在两边看到的"错了"必须是同一个红。
 */

/** 昼：近白的纸，冷墨。比「海」更白——云那边不掺青，掺的是一点灰蓝 */
val LightCloud = SeaPalette(
    paper = Color(0xFFFBFBFC),
    paper2 = Color(0xFFF4F5F7),
    paper3 = Color(0xFFEBEDF0),
    paper4 = Color(0xFFDEE1E6),
    paperBright = Color(0xFFFFFFFF),
    ink = Color(0xFF1B1F26),
    ink2 = Color(0xFF343A44),
    graphite = Color(0xFF666E7A),
    graphiteLight = Color(0xFFA8AFB9),
    rule = Color(0xFFE2E5EA),
    sea = Color(0xFF8A93A0),
    // 纸上的链接与焦点边：纹理中位（≈#92）在白纸上只有 3:1，读不动，
    // 平面取样按用途压到这一档
    seaDeep = Color(0xFF4A5361),
    seaBright = Color(0xFFB9C2CE),
    seaFoam = Color(0xFFEDF1F6),
    seaFlat = Color(0xFF5C6675),
    vermilion = Color(0xFFC8433B),
    vermilionDeep = Color(0xFF8E2A24),
    bamboo = Color(0xFF4E8A63),
    dark = false,
)

/** 夜：云影做纸，云光做墨 */
val DarkCloud = SeaPalette(
    paper = Color(0xFF14161A),
    paper2 = Color(0xFF191C21),
    paper3 = Color(0xFF21252B),
    paper4 = Color(0xFF2C3138),
    paperBright = Color(0xFF3A4048),
    ink = Color(0xFFECEEF2),
    ink2 = Color(0xFFC9CED6),
    graphite = Color(0xFF98A0AB),
    graphiteLight = Color(0xFF5E6670),
    rule = Color(0xFF2C3138),
    sea = Color(0xFFA8B2BF),
    seaDeep = Color(0xFFC6CEDA),
    seaBright = Color(0xFFD9E0E9),
    seaFoam = Color(0xFFEDF1F6),
    seaFlat = Color(0xFF6D7787),
    vermilion = Color(0xFFF06A5E),
    vermilionDeep = Color(0xFFFFA096),
    bamboo = Color(0xFF8CC5A0),
    dark = true,
)

internal val LightCloudColors: ColorScheme = with(LightCloud) {
    lightColorScheme(
        primary = seaDeep,
        onPrimary = paper,
        primaryContainer = Color(0xFFDFE3E9),
        onPrimaryContainer = seaDeep,
        secondary = sea,
        onSecondary = paper,
        secondaryContainer = Color(0xFFE6EAEF),
        onSecondaryContainer = seaDeep,
        tertiary = graphite,
        onTertiary = paper,
        tertiaryContainer = paper3,
        onTertiaryContainer = ink2,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = paper3,
        onSurfaceVariant = graphite,
        surfaceContainerLowest = paperBright,
        surfaceContainerLow = paper2,
        surfaceContainer = Color(0xFFF0F1F4),
        surfaceContainerHigh = paper3,
        surfaceContainerHighest = paper4,
        surfaceBright = paperBright,
        surfaceDim = paper4,
        outline = graphiteLight,
        outlineVariant = rule,
        error = vermilion,
        onError = paper,
        errorContainer = Color(0xFFF9E0DD),
        onErrorContainer = vermilionDeep,
        inverseSurface = ink,
        inverseOnSurface = paper,
        inversePrimary = DarkCloud.sea,
        scrim = ink,
    )
}

internal val DarkCloudColors: ColorScheme = with(DarkCloud) {
    darkColorScheme(
        primary = sea,
        onPrimary = Color(0xFF14161A),
        primaryContainer = Color(0xFF353B44),
        onPrimaryContainer = seaFoam,
        secondary = seaDeep,
        onSecondary = Color(0xFF14161A),
        secondaryContainer = paper4,
        onSecondaryContainer = seaFoam,
        tertiary = graphite,
        onTertiary = paper,
        tertiaryContainer = paper4,
        onTertiaryContainer = ink2,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = paper3,
        onSurfaceVariant = graphite,
        surfaceContainerLowest = Color(0xFF0E1013),
        surfaceContainerLow = paper2,
        surfaceContainer = Color(0xFF1D2126),
        surfaceContainerHigh = paper3,
        surfaceContainerHighest = paper4,
        surfaceBright = paperBright,
        surfaceDim = paper,
        outline = graphiteLight,
        outlineVariant = rule,
        error = vermilion,
        onError = Color(0xFF3D0A16),
        errorContainer = Color(0xFF5C1E1A),
        onErrorContainer = vermilionDeep,
        inverseSurface = ink,
        inverseOnSurface = LightCloud.paper,
        inversePrimary = LightCloud.seaDeep,
        scrim = Color.Black,
    )
}

/**
 * 云图是竖构图，按屏宽 2.2 倍铺时高度正好盖满一屏，不进 MIRROR 平铺。
 * 海那边 1.3 倍靠平铺补，墨纹看不出对称；云的形态连贯，镜像接缝一眼就认出来。
 */
internal const val CLOUD_WIDTH_FACTOR = 2.2f

/**
 * 代码高亮：云的那一套。
 *
 * 不能照搬海的——那一套里关键字是海的深处、数字是石墨蓝，整屏都是蓝的。
 * 这里改成一套冷灰的层级：关键字最重、字符串仍是竹青、注释最淡。
 * 语义分工一字不改，只是把色相抽掉。
 */
internal val CloudCodeLight = HighlightTextColorPalette(
    keyword = Color(0xFF3C4450),
    string = Color(0xFF3F7A52),
    number = Color(0xFF5B6675),
    comment = Color(0xFFA0A7B1),
    function = Color(0xFF4A5361),
    operator = Color(0xFF6B7482),
    punctuation = Color(0xFF6B7482),
    className = Color(0xFF5B6675),
    property = Color(0xFFA84A3C),
    boolean = Color(0xFF5B6675),
    variable = Color(0xFF1B1F26),
    tag = Color(0xFFA84A3C),
    attrName = Color(0xFF5B6675),
    attrValue = Color(0xFF3F7A52),
    fallback = Color(0xFF1B1F26),
)

internal val CloudCodeDark = HighlightTextColorPalette(
    keyword = Color(0xFFD6DCE4),
    string = Color(0xFF9BD1AB),
    number = Color(0xFFB2BAC6),
    comment = Color(0xFF6E7681),
    function = Color(0xFFC2C9D3),
    operator = Color(0xFF9AA3AE),
    punctuation = Color(0xFF9AA3AE),
    className = Color(0xFFB2BAC6),
    property = Color(0xFFF08A7E),
    boolean = Color(0xFFB2BAC6),
    variable = Color(0xFFECEEF2),
    tag = Color(0xFFF08A7E),
    attrName = Color(0xFFB2BAC6),
    attrValue = Color(0xFF9BD1AB),
    fallback = Color(0xFFECEEF2),
)
