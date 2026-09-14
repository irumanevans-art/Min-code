package dev.min.code.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.min.code.core.settings.ThemeMode

val LocalDarkMode = compositionLocalOf { false }

/**
 * # 「海」：纸 · 墨 · 海
 *
 * 整套界面只有三种物质：
 *
 * - **纸**：底。昼是「白色取色」那张图背景上的白（#F8FDF7，带一点点青），夜是海最深处的蓝黑；
 * - **墨**：字与线。昼是蓝黑，夜是纸白；
 * - **海**：唯一的强调色。它不是一个色值——是「蓝色取底」那张酒精墨纹理本身。
 *   界面上每一块蓝，都是把一张纸镂空成那个形状盖在纹理上（[Modifier.seaInk] / [Modifier.seaFill]）；
 *   整屏共用同一片静止的海（[SeaField]），滚动时纸在动、海不动。大面积的海（按钮底、选中块）不取纹理，
 *   用 [SeaPalette.seaFlat]；只有发送键里的海在流。
 *
 * 海给「人与主动作」：你写的话、当前会话、主按钮、发送键、链接、选中、进度。"活着的"不再靠另一种颜色，
 * 靠动：跑着的标记会起涟漪，轨道上有暗流。
 * **朱**只给判定：错误、拒绝、删除。全页只有这一处红。
 *
 * 只能填 Color 的地方（状态栏、链接字、代码高亮、Material 的 primary）用从纹理上取样的几档平面蓝
 * （[SeaPalette.sea] / [seaDeep] / [seaBright] / [seaFoam]，值由 tools/build_sea_assets.py 算出）。
 */
@Immutable
data class SeaPalette(
    /** 底：纸（昼）/ 深海（夜），由浅到深四阶 */
    val paper: Color,
    val paper2: Color,
    val paper3: Color,
    val paper4: Color,
    val paperBright: Color,
    /** 图：墨（昼）/ 纸白（夜） */
    val ink: Color,
    val ink2: Color,
    /** 石墨：元信息、次要文字 */
    val graphite: Color,
    val graphiteLight: Color,
    /** 界线：hairline */
    val rule: Color,
    /** 海的平面取样：中间值。只给填 Color 的地方 */
    val sea: Color,
    /** 海的深处：纸上的蓝字、链接、焦点边 */
    val seaDeep: Color,
    /** 海最亮的青 */
    val seaBright: Color,
    /** 浪沫：海上的白 */
    val seaFoam: Color,
    /** 大面积的海：按钮底、当前会话块这些超过 1800 dp² 的面不镂空纹理（会显得突兀），用这一档平色 */
    val seaFlat: Color,
    /** 朱：判定 */
    val vermilion: Color,
    val vermilionDeep: Color,
    /** 竹青：diff 的新增行 */
    val bamboo: Color,
    val dark: Boolean,
) {
    /** 海的水洗版：附件 chip、计划横幅、选中行这些不值得开一扇窗的地方 */
    val seaWash: Color get() = sea.copy(alpha = if (dark) 0.20f else 0.12f)
    val vermilionWash: Color get() = vermilion.copy(alpha = if (dark) 0.18f else 0.12f)

    /** 画墨用的墨：蓝黑里透一点海 */
    val inkWash: Color get() = lerp(ink, sea, if (dark) 0.10f else 0.18f)

    /** 遮罩：sheet / 对话框后面那层 */
    val scrim: Color
        get() = if (dark) Color(0xFF03070D).copy(alpha = 0.56f) else Color(0xFF0E1A2B).copy(alpha = 0.36f)

    /** 海上的字：永远是纸白 */
    val onSea: Color get() = Color(0xFFF8FDF7)
}

/** 昼：白色取色的纸，蓝黑的墨 */
val LightSea = SeaPalette(
    paper = Color(0xFFF8FDF7),
    paper2 = Color(0xFFF1F7F1),
    paper3 = Color(0xFFE8F0EA),
    paper4 = Color(0xFFDAE4DE),
    paperBright = Color(0xFFFFFFFF),
    ink = Color(0xFF0E1A2B),
    ink2 = Color(0xFF26364C),
    graphite = Color(0xFF5B6B80),
    graphiteLight = Color(0xFFA3B0C0),
    rule = Color(0xFFD9E3E1),
    sea = Color(0xFF3E96E3),
    seaDeep = Color(0xFF1562BC),
    seaBright = Color(0xFF5ED8F9),
    seaFoam = Color(0xFFDBEAF9),
    seaFlat = Color(0xFF2B7FD6),
    vermilion = Color(0xFFC8433B),
    vermilionDeep = Color(0xFF8E2A24),
    bamboo = Color(0xFF4E8A63),
    dark = false,
)

/** 夜：海最深处做纸，纸白做墨 */
val DarkSea = SeaPalette(
    paper = Color(0xFF0B1524),
    paper2 = Color(0xFF101D30),
    paper3 = Color(0xFF16263D),
    paper4 = Color(0xFF1F324D),
    paperBright = Color(0xFF2A4060),
    ink = Color(0xFFEAF3FB),
    ink2 = Color(0xFFC7D8EA),
    graphite = Color(0xFF9DB3CC),
    graphiteLight = Color(0xFF5F7793),
    rule = Color(0xFF1F324D),
    sea = Color(0xFF4FA8EC),
    seaDeep = Color(0xFF7FC4F2),
    seaBright = Color(0xFF5ED8F9),
    seaFoam = Color(0xFFDBEAF9),
    seaFlat = Color(0xFF2F86D9),
    vermilion = Color(0xFFF06A5E),
    vermilionDeep = Color(0xFFFFA096),
    bamboo = Color(0xFF8CC5A0),
    dark = true,
)

val LocalSea = staticCompositionLocalOf { LightSea }

/** `MaterialTheme.sea.seaDeep` —— 和 colorScheme 平级的物质扩展 */
val MaterialTheme.sea: SeaPalette
    @Composable @ReadOnlyComposable get() = LocalSea.current

private val LightColors: ColorScheme = with(LightSea) {
    lightColorScheme(
        primary = seaDeep,
        onPrimary = paper,
        primaryContainer = Color(0xFFD6EBFB),
        onPrimaryContainer = seaDeep,
        secondary = sea,
        onSecondary = paper,
        secondaryContainer = Color(0xFFDDF0FA),
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
        surfaceContainer = Color(0xFFEDF3EE),
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
        inversePrimary = DarkSea.sea,
        scrim = ink,
    )
}

private val DarkColors: ColorScheme = with(DarkSea) {
    darkColorScheme(
        primary = sea,
        onPrimary = Color(0xFF06182C),
        primaryContainer = Color(0xFF17395E),
        onPrimaryContainer = seaFoam,
        secondary = seaDeep,
        onSecondary = Color(0xFF06182C),
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
        surfaceContainerLowest = Color(0xFF07101C),
        surfaceContainerLow = paper2,
        surfaceContainer = Color(0xFF132238),
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
        inverseOnSurface = LightSea.paper,
        inversePrimary = LightSea.seaDeep,
        scrim = Color.Black,
    )
}

/**
 * 圆角阶梯。纸是裁出来的，边角利落：比 Material 默认收两档。
 * extraSmall 给行内标记，small 给 chip / 提示块，medium 给按钮 / 输入框 / 卡片，
 * large 给气泡与对话框，extraLarge 给 sheet 的顶边。
 */
val MinShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MinTheme(
    mode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val palette = if (dark) DarkSea else LightSea
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(
        LocalDarkMode provides dark,
        LocalSea provides palette,
        // 页面根是 Box 不是 Surface，不设这个的话 Markdown / Text 默认走黑字，
        // 深色模式正文就看不见。InkFrame 已经自己设过一份。
        LocalContentColor provides colors.onSurface,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = MinTypography,
            shapes = MinShapes,
            content = content,
        )
    }
}

/** 搬来的页面用的几组容器色，名字保持和 RikkaHub 一致 */
object CustomColors {
    val topBarColors: TopAppBarColors
        @Composable get() = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        )

    val cardColorsOnSurfaceContainer: CardColors
        @Composable get() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
}

/** 海的水洗版：选中态、附件 chip 共用 */
val ColorScheme.accentWash: Color
    @Composable @ReadOnlyComposable get() = LocalSea.current.seaWash
