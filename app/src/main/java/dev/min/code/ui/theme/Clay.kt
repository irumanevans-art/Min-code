package dev.min.code.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.highlight.HighlightTextColorPalette

/**
 * # 陶：Anthropic 的原生配色
 *
 * 第三张取底。纸是 ivory，墨是 slate，强调色是 clay —— 色值不是自己配的，取自
 * Anthropic 站点 `:root` 上的品牌令牌（ivory-light `#FAF9F5`、ivory-medium `#F0EEE6`、
 * oat-warm `#E3DACC`、slate-dark `#141413`、slate-medium `#3D3D3A`、cloud-medium `#B0AEA5`、
 * clay `#D97757`、clay-deep `#C6613F`、kraft `#D4A27F`、manilla `#F5E3C7`）。
 * 只有「纸上要读得动」的那几档按对比度往下压过，每一处都在下面标了实测比值。
 *
 * 纹理是 `tools/build_clay_plate.py` 生成的 `clay_plate.png`，明度直方图和 `sea_plate`
 * 逐档对齐——这是「现有每一处 alpha 不用重调」的前提，和云那张是同一个做法。
 *
 * ## 朱在这一套里要挪一档，这是唯一一处破例
 *
 * `Cloud.kt` 立过一条规矩：两套配色共用朱与竹青，「一个用户在两边看到的『错了』
 * 必须是同一个红」。这里不行 —— 陶土的强调色本身就是橙红（`#C6613F`），和原来的
 * 朱（`#C8433B`）几乎是同一个颜色。照搬的结果是一个 11 sp 的错误标记和一个主按钮
 * 分不出来，而「判定」正是全页唯一一处不能被误读的颜色。
 *
 * 所以这一套里朱往深冷推（昼 `#B3261E`、夜 `#EF4E4E`），拉开色相距离；竹青不变。
 * 破的是「跨风格同一个红」，保的是「判定和强调必须分得开」—— 后者更要紧。
 */

/** 昼：ivory 的纸，slate 的墨 */
val LightClay = SeaPalette(
    paper = Color(0xFFFAF9F5),
    paper2 = Color(0xFFF5F2EA),
    paper3 = Color(0xFFF0EEE6),
    paper4 = Color(0xFFE3DACC),
    paperBright = Color(0xFFFFFFFF),
    ink = Color(0xFF141413),
    ink2 = Color(0xFF3D3D3A),
    graphite = Color(0xFF6B6A63),
    graphiteLight = Color(0xFFB0AEA5),
    rule = Color(0xFFE5E2D8),
    sea = Color(0xFFC6613F),
    // 纸上的链接与焦点边：clay 原色在 ivory 上只有 3.2:1，读不动。压到这一档是 5.5:1
    seaDeep = Color(0xFFA34C2C),
    seaBright = Color(0xFFD4A27F),
    seaFoam = Color(0xFFF5E3C7),
    // 大面积的面上要压纸白的字，4.9:1
    seaFlat = Color(0xFFAE5430),
    vermilion = Color(0xFFB3261E),
    vermilionDeep = Color(0xFF7F1D18),
    bamboo = Color(0xFF4E8A63),
    dark = false,
    // 陶上的字跟着纸走：默认那个带青的白压在暖橙上会显出一道冷边
    onSea = Color(0xFFFAF9F5),
)

/** 夜：slate 做纸，ivory 做墨 */
val DarkClay = SeaPalette(
    paper = Color(0xFF1F1E1D),
    paper2 = Color(0xFF262624),
    paper3 = Color(0xFF30302E),
    paper4 = Color(0xFF3B3A37),
    paperBright = Color(0xFF4A4843),
    ink = Color(0xFFF5F4EE),
    ink2 = Color(0xFFD6D3CA),
    graphite = Color(0xFFA3A099),
    graphiteLight = Color(0xFF6B6A63),
    rule = Color(0xFF3B3A37),
    sea = Color(0xFFD97757),
    seaDeep = Color(0xFFE8A183),
    seaBright = Color(0xFFF5E3C7),
    seaFoam = Color(0xFFF7EFE6),
    seaFlat = Color(0xFFAE5230),
    vermilion = Color(0xFFEF4E4E),
    vermilionDeep = Color(0xFFFF9089),
    bamboo = Color(0xFF8CC5A0),
    dark = true,
    onSea = Color(0xFFFAF9F5),
)

internal val LightClayColors: ColorScheme = with(LightClay) {
    lightColorScheme(
        primary = seaDeep,
        onPrimary = paper,
        primaryContainer = Color(0xFFF7E2D8),
        onPrimaryContainer = seaDeep,
        secondary = sea,
        onSecondary = paper,
        secondaryContainer = Color(0xFFF7EAE0),
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
        surfaceContainer = Color(0xFFF3F0E8),
        surfaceContainerHigh = paper3,
        surfaceContainerHighest = paper4,
        surfaceBright = paperBright,
        surfaceDim = paper4,
        outline = graphiteLight,
        outlineVariant = rule,
        error = vermilion,
        onError = paper,
        errorContainer = Color(0xFFF7DEDB),
        onErrorContainer = vermilionDeep,
        inverseSurface = ink,
        inverseOnSurface = paper,
        inversePrimary = DarkClay.sea,
        scrim = ink,
    )
}

internal val DarkClayColors: ColorScheme = with(DarkClay) {
    darkColorScheme(
        primary = sea,
        onPrimary = Color(0xFF1F1E1D),
        primaryContainer = Color(0xFF4A2A1C),
        onPrimaryContainer = seaFoam,
        secondary = seaDeep,
        onSecondary = Color(0xFF1F1E1D),
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
        surfaceContainerLowest = Color(0xFF161615),
        surfaceContainerLow = paper2,
        surfaceContainer = Color(0xFF2B2B28),
        surfaceContainerHigh = paper3,
        surfaceContainerHighest = paper4,
        surfaceBright = paperBright,
        surfaceDim = paper,
        outline = graphiteLight,
        outlineVariant = rule,
        error = vermilion,
        onError = Color(0xFF3D0A0A),
        errorContainer = Color(0xFF5C1A17),
        onErrorContainer = vermilionDeep,
        inverseSurface = ink,
        inverseOnSurface = LightClay.paper,
        inversePrimary = LightClay.seaDeep,
        scrim = Color.Black,
    )
}

/**
 * 代码高亮：陶的那一套。
 *
 * 语义分工和海那一套一字不改（关键字最重、字符串竹青、注释最淡），只是把色相换成
 * 暖的一侧。属性与标签那两档**不用朱** —— 这套里的朱是判定色，出现在代码块里会让人
 * 以为那一行有错。
 */
internal val ClayCodeLight = HighlightTextColorPalette(
    keyword = Color(0xFFA34C2C),
    string = Color(0xFF4E7A4E),
    number = Color(0xFF8A6A2F),
    comment = Color(0xFFA5A199),
    function = Color(0xFF7A5230),
    operator = Color(0xFF5E5C55),
    punctuation = Color(0xFF5E5C55),
    className = Color(0xFF8A6A2F),
    property = Color(0xFF9A5B3A),
    boolean = Color(0xFF8A6A2F),
    variable = Color(0xFF141413),
    tag = Color(0xFF9A5B3A),
    attrName = Color(0xFF8A6A2F),
    attrValue = Color(0xFF4E7A4E),
    fallback = Color(0xFF141413),
)

internal val ClayCodeDark = HighlightTextColorPalette(
    keyword = Color(0xFFE8A183),
    string = Color(0xFF9DC49D),
    number = Color(0xFFD9BC86),
    comment = Color(0xFF7D7A72),
    function = Color(0xFFD4A27F),
    operator = Color(0xFFB5B2A9),
    punctuation = Color(0xFFB5B2A9),
    className = Color(0xFFD9BC86),
    property = Color(0xFFE3A08C),
    boolean = Color(0xFFD9BC86),
    variable = Color(0xFFF5F4EE),
    tag = Color(0xFFE3A08C),
    attrName = Color(0xFFD9BC86),
    attrValue = Color(0xFF9DC49D),
    fallback = Color(0xFFF5F4EE),
)
