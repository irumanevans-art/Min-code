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
 * 楷书（霞鹜文楷 GB，完整字库）——**人的声音**：品牌字、页面标题、题跋、以及你自己写下的话。
 * 这本日志里三种字各说一种话：楷书是人写的，等宽是机器吐的，sans 是模型讲的。
 * 只带常规一档，标题不要请求粗体，系统会伪加粗，楷体一伪粗就糊。
 */
val MinKai = FontFamily(Font(resId = R.font.lxgw_wenkai_gb, weight = FontWeight.Normal))

/**
 * 首页字标的字：Playfair Display 斜体（可变字重，取 Bold）。高对比的衬线斜体在水里折射时最好看，
 * 只用在那一个巨大的 Min 上。
 */
val MinDisplay = FontFamily(
    Font(
        resId = R.font.playfair_display_italic,
        weight = FontWeight.Bold,
        style = androidx.compose.ui.text.font.FontStyle.Italic,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Bold.weight)),
    )
)

/**
 * 字号阶梯比 Material 默认收得紧一点：这是一屏要装下几十条工作日志的界面，
 * 不是营销页。labelSmall 是这里用得最多的样式（工具摘要、元信息），给它够用的行高。
 * 标题走楷书，字号比同级 sans 放大一号——楷体的字面本来就比黑体小。
 * displaySmall / headlineSmall 只给品牌字和加载页。
 */
val MinTypography = Typography(
    displaySmall = TextStyle(fontFamily = MinKai, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = MinKai, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontFamily = MinKai, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontFamily = MinKai, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    titleSmall = TextStyle(fontFamily = MinKai, fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

/**
 * 代码高亮配色，和界面同一套物质：纸上是墨字，关键字海的深处、字符串竹青、数字与类名石墨蓝、
 * 属性与标签朱砂、注释石墨。夜里全部提亮一档。
 */
val InkCodeLight = HighlightTextColorPalette(
    keyword = Color(0xFF1562BC),
    string = Color(0xFF3F7A52),
    number = Color(0xFF2E7A9C),
    comment = Color(0xFF93A0AE),
    function = Color(0xFF2A4D8F),
    operator = Color(0xFF4A5566),
    punctuation = Color(0xFF4A5566),
    className = Color(0xFF2E7A9C),
    property = Color(0xFFA84A3C),
    boolean = Color(0xFF2E7A9C),
    variable = Color(0xFF0E1A2B),
    tag = Color(0xFFA84A3C),
    attrName = Color(0xFF2E7A9C),
    attrValue = Color(0xFF3F7A52),
    fallback = Color(0xFF0E1A2B),
)

val InkCodeDark = HighlightTextColorPalette(
    keyword = Color(0xFF7FC4F2),
    string = Color(0xFF9BD1AB),
    number = Color(0xFF6FD3EE),
    comment = Color(0xFF5F7793),
    function = Color(0xFFA9BEE6),
    operator = Color(0xFFB6C4D3),
    punctuation = Color(0xFFB6C4D3),
    className = Color(0xFF6FD3EE),
    property = Color(0xFFF08A7E),
    boolean = Color(0xFF6FD3EE),
    variable = Color(0xFFEAF3FB),
    tag = Color(0xFFF08A7E),
    attrName = Color(0xFF6FD3EE),
    attrValue = Color(0xFF9BD1AB),
    fallback = Color(0xFFEAF3FB),
)
