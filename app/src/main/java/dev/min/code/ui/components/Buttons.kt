package dev.min.code.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaFill

/**
 * 按钮的四种物质：
 * - **Ink**（主动作）：一扇海的窗，字是纸白；
 * - **Paper**（次动作）：纸底 + hairline，字是墨；
 * - **Quiet**（文字按钮）：没有底，字是海的深处；
 * - **Vermilion**（判定）：朱砂底，字是纸白。
 */
enum class InkButtonTone { Ink, Paper, Quiet, Vermilion }

private val ButtonShape = RoundedCornerShape(10.dp)

@Composable
fun InkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: InkButtonTone = InkButtonTone.Ink,
    icon: ImageVector? = null,
    busy: Boolean = false,
    compact: Boolean = false,
    shape: Shape = ButtonShape,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val active = enabled && !busy
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.sea
    val interaction = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, InkMotion.effect(), label = "buttonAlpha")
    val padding = contentPadding ?: if (compact) PaddingValues(horizontal = 12.dp, vertical = 6.dp) else PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    val minHeight = if (compact) 32.dp else 42.dp
    val foreground = when (tone) {
        InkButtonTone.Ink -> palette.onSea
        InkButtonTone.Paper -> scheme.onSurface
        InkButtonTone.Quiet -> palette.seaDeep
        InkButtonTone.Vermilion -> scheme.onError
    }
    var m = modifier
        .pressScale(interaction, if (compact) 0.96f else 0.975f)
        .alpha(alpha)
        .defaultMinSize(minHeight = minHeight)
        .clip(shape)
    m = when (tone) {
        InkButtonTone.Ink -> m.seaFill(shape)
        InkButtonTone.Paper -> m.background(scheme.surfaceContainerLow).border(1.dp, scheme.outlineVariant, shape)
        InkButtonTone.Quiet -> m
        InkButtonTone.Vermilion -> m.background(scheme.error)
    }
    m = m.clickable(
        enabled = active,
        interactionSource = interaction,
        indication = LocalIndication.current,
        role = Role.Button,
        onClick = onClick,
    ).padding(padding)
    Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            ProvideTextStyle(if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge) {
                if (busy) {
                    InkSpinner(size = if (compact) 14.dp else 16.dp, color = foreground)
                    Spacer(Modifier.width(8.dp))
                } else if (icon != null) {
                    Icon(icon, null, Modifier.size(if (compact) 15.dp else 17.dp), tint = foreground)
                    Spacer(Modifier.width(8.dp))
                }
                content()
            }
        }
    }
}

@Composable
fun InkTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: InkButtonTone = InkButtonTone.Quiet,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) = InkButton(onClick, modifier, enabled, tone, icon, compact = true, contentPadding = contentPadding, content = content)

/** 图标按钮：40dp 热区，石墨的图标；按下缩一点，点按是墨晕。 */
@Composable
fun InkIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalOnPanel.current.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val color by animateColorAsState(if (enabled) tint else tint.copy(alpha = 0.38f), InkMotion.effect(), label = "iconTint")
    Box(
        modifier
            .pressScale(interaction, 0.9f)
            .size(size)
            .clip(RoundedCornerShape(50))
            .clickable(
                enabled = enabled && !busy,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) InkSpinner(size = iconSize, color = color)
        else Icon(icon, contentDescription, Modifier.size(iconSize), tint = color)
    }
}

@Composable
fun RowScope.ButtonLabel(text: String) {
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}

@Suppress("unused")
private val Dp.half: Dp get() = this / 2
