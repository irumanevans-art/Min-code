package dev.min.code.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.min.code.core.settings.ThemeMode

val LocalDarkMode = compositionLocalOf { false }

/**
 * 配色：浅色为底（手机常在亮环境用），一种低饱和的赭石色做强调，其余全是暖灰。
 *
 * 不用 Material You 动态取色：壁纸一换整套对比度就变，而这个 App 的主体是长时间阅读
 * 等宽文本，对比度必须稳定。强调色刻意压低饱和度——它只用来标记"正在进行"和"选中"，
 * 不该在一屏工作日志里到处跳。
 */
private val Terracotta = Color(0xFFB85C38)
private val TerracottaDeep = Color(0xFF8E4426)
private val TerracottaSoft = Color(0xFFF6E2D8)
private val Ink = Color(0xFF1C1B19)
private val Ink2 = Color(0xFF3D3B37)
private val Stone = Color(0xFF6E6A63)
private val StoneLight = Color(0xFFB7B2A9)
private val Paper = Color(0xFFFBFAF7)
private val Paper2 = Color(0xFFF4F2ED)
private val Paper3 = Color(0xFFECE9E2)
private val Paper4 = Color(0xFFE3DFD6)
private val Slate = Color(0xFF4A5A66)
private val SlateSoft = Color(0xFFDEE5EA)
private val ErrorRed = Color(0xFFB3261E)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = TerracottaSoft,
    onPrimaryContainer = TerracottaDeep,
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = SlateSoft,
    onSecondaryContainer = Color(0xFF223038),
    tertiary = Stone,
    onTertiary = Color.White,
    tertiaryContainer = Paper3,
    onTertiaryContainer = Ink2,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Paper3,
    onSurfaceVariant = Stone,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Paper2,
    surfaceContainer = Paper2,
    surfaceContainerHigh = Paper3,
    surfaceContainerHighest = Paper4,
    surfaceBright = Color.White,
    surfaceDim = Paper4,
    outline = StoneLight,
    outlineVariant = Paper4,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    inversePrimary = Color(0xFFE8A78C),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFE8A78C),
    onPrimary = Color(0xFF4A1F0E),
    primaryContainer = Color(0xFF6B3319),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFB4C4CF),
    onSecondary = Color(0xFF1F2E36),
    secondaryContainer = Color(0xFF34444D),
    onSecondaryContainer = Color(0xFFD0DEE6),
    tertiary = StoneLight,
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF3A3834),
    onTertiaryContainer = Paper3,
    background = Color(0xFF141311),
    onBackground = Color(0xFFE7E3DC),
    surface = Color(0xFF141311),
    onSurface = Color(0xFFE7E3DC),
    surfaceVariant = Color(0xFF2B2926),
    onSurfaceVariant = Color(0xFFB0ABA2),
    surfaceContainerLowest = Color(0xFF0E0D0C),
    surfaceContainerLow = Color(0xFF1A1917),
    surfaceContainer = Color(0xFF1F1E1B),
    surfaceContainerHigh = Color(0xFF292824),
    surfaceContainerHighest = Color(0xFF34322E),
    surfaceBright = Color(0xFF3A3834),
    surfaceDim = Color(0xFF141311),
    outline = Color(0xFF7A756D),
    outlineVariant = Color(0xFF3A3834),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    inversePrimary = Terracotta,
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalDarkMode provides dark) {
        MaterialTheme(
            colorScheme = colors,
            typography = MinTypography,
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
