package dev.min.code.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.min.code.R
import me.rerere.highlight.HighlightTextColorPalette

/** 代码、路径、版本号、状态摘要——一切"机器产物"都用它；正文用系统 sans */
val JetbrainsMono = FontFamily(
    Font(
        resId = R.font.jetbrains_mono,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Normal.weight)),
    )
)

/**
 * 字号阶梯比 Material 默认收得紧一点：这是一屏要装下几十条工作日志的界面，
 * 不是营销页。labelSmall 是这里用得最多的样式（工具摘要、元信息），给它够用的行高。
 */
val MinTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

// Atom One 配色，代码块用。深浅两套跟随主题。
val AtomOneDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFc678dd),
    string = Color(0xFF98c379),
    number = Color(0xFFd19a66),
    comment = Color(0xFF5c6370),
    function = Color(0xFF61afef),
    operator = Color(0xFF56b6c2),
    punctuation = Color(0xFFabb2bf),
    className = Color(0xFFe5c07b),
    property = Color(0xFFe06c75),
    boolean = Color(0xFFd19a66),
    variable = Color(0xFFe06c75),
    tag = Color(0xFFe06c75),
    attrName = Color(0xFFd19a66),
    attrValue = Color(0xFF98c379),
    fallback = Color(0xFFabb2bf),
)

val AtomOneLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFFa626a4),
    string = Color(0xFF50a14f),
    number = Color(0xFFc18401),
    comment = Color(0xFF9ca0a4),
    function = Color(0xFF4078f2),
    operator = Color(0xFF0184bc),
    punctuation = Color(0xFF383a42),
    className = Color(0xFFc18401),
    property = Color(0xFFe45649),
    boolean = Color(0xFFc18401),
    variable = Color(0xFFe45649),
    tag = Color(0xFFe45649),
    attrName = Color(0xFFc18401),
    attrValue = Color(0xFF50a14f),
    fallback = Color(0xFF383a42),
)
