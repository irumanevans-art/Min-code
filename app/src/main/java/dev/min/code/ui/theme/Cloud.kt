package dev.min.code.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import dev.min.code.R

/**
 * # 云：Codex 那一支的取底
 *
 * 「海」是蓝色取底那张酒精墨，整个 Claude 侧的每一块蓝都是从它上面镂出来的。
 * Codex 换一张底：一幅灰白的云海（`tools/build_cloud_plate.py` 从「灰白仙境」压出来）。
 * 规则一字不改——界面上每一块强调色仍然是「把纸镂空成那个形状盖在纹理上」，
 * 只是窗外面那片东西从海变成了云。
 *
 * 所以这里没有新的组件，也没有一个 `cloudInk()`：[Modifier.seaInk] / [Modifier.seaFill]
 * 读 [LocalSeaPlate]，换掉它，整棵子树——会话流左边那条轨道、发送键里流动的窗、
 * 所有 Ink* 控件——自己就成了云。[CloudTheme] 做的全部事情就是换三个 CompositionLocal。
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

private val LightCloudColors: ColorScheme = with(LightCloud) {
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

private val DarkCloudColors: ColorScheme = with(DarkCloud) {
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
private const val CLOUD_WIDTH_FACTOR = 2.2f

/**
 * 把这棵子树的取底换成云。
 *
 * 四件事，一件都不多：换 palette、换 colorScheme、换纹理、换那片静止的底
 * （云的尺寸和海不同，铺法也不同，[SeaField] 必须重算一份）。
 *
 * colorScheme 非换不可——Ink* 组件读 [LocalSea]，但 Material 自己那些还活着的地方
 * （DropdownMenu 的面、TextField 的光标、进度、涟漪）读的是 colorScheme。只换前者的话，
 * 云页面上会冒出几处海的蓝，而且专挑不受控的地方冒。
 *
 * 纹理要先 provide 再 [rememberSeaField]——后者读 [SeaPlate.bitmap]，
 * 而 bitmap 的默认参数取的正是 [LocalSeaPlate]。两层 Provider 的顺序就是这个原因。
 */
@Composable
fun CloudTheme(content: @Composable () -> Unit) {
    val dark = LocalDarkMode.current
    CompositionLocalProvider(
        LocalSeaPlate provides R.drawable.cloud_plate,
        LocalSea provides if (dark) DarkCloud else LightCloud,
    ) {
        val colors = if (dark) DarkCloudColors else LightCloudColors
        CompositionLocalProvider(
            LocalSeaField provides rememberSeaField(widthFactor = CLOUD_WIDTH_FACTOR),
            LocalContentColor provides colors.onSurface,
        ) {
            MaterialTheme(
                colorScheme = colors,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
                content = content,
            )
        }
    }
}
