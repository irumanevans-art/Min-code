package dev.min.code.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.min.code.ui.theme.InkMotion
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.pressScale
import dev.min.code.ui.theme.sea
import dev.min.code.ui.theme.seaFill
import kotlin.math.roundToInt

/**
 * 控件。开 / 选中 / 当前 = 一扇海的窗；关 / 未选 = 纸与 hairline。
 * 每一次状态变化都有动效：位置走弹簧，颜色走 tween。
 */

/** 开关：34×20 的轨道。开是海，纸白的钮滑到右边 */
@Composable
fun InkSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val travel = with(LocalDensity.current) { 14.dp.toPx() }
    val x by animateFloatAsState(if (checked) travel else 0f, InkMotion.spatial(), label = "switch")
    val on by animateFloatAsState(if (checked) 1f else 0f, InkMotion.effect(), label = "switchOn")
    val thumb by animateColorAsState(if (checked) MaterialTheme.sea.onSea else scheme.onSurfaceVariant, InkMotion.effect(), label = "thumb")
    var m = modifier.alpha(if (enabled) 1f else 0.45f)
    if (onCheckedChange != null) {
        m = m.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interaction,
            indication = null,
            onValueChange = onCheckedChange,
        )
    }
    Box(m.pressScale(interaction, 0.94f).size(34.dp, 20.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .size(34.dp, 20.dp)
                .clip(RoundedCornerShape(50))
                .background(scheme.surfaceContainerHighest)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50)),
        )
        Box(Modifier.size(34.dp, 20.dp).seaFill(RoundedCornerShape(50), alpha = on))
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt() + 3.dp.roundToPx(), 0) }
                .size(14.dp)
                .background(thumb, RoundedCornerShape(50)),
        )
    }
}

/** 勾选：18dp 方，勾上是海底 + 纸白的勾 */
@Composable
fun InkCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val on by animateFloatAsState(if (checked) 1f else 0f, InkMotion.spatial(), label = "check")
    val tick = MaterialTheme.sea.onSea
    var m = modifier.alpha(if (enabled) 1f else 0.45f)
    if (onCheckedChange != null) {
        m = m.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, interactionSource = interaction, indication = null, onValueChange = onCheckedChange)
    }
    val shape = RoundedCornerShape(5.dp)
    Box(m.pressScale(interaction, 0.9f).size(18.dp)) {
        Box(Modifier.size(18.dp).clip(shape).background(scheme.surfaceContainerLowest).border(1.2.dp, scheme.outline, shape))
        Box(Modifier.size(18.dp).seaFill(shape, alpha = on))
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            if (on <= 0.01f) return@Canvas
            val p = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.52f)
                lineTo(size.width * 0.43f, size.height * 0.70f)
                lineTo(size.width * 0.77f, size.height * 0.33f)
            }
            // 勾是一笔写出来的：随 on 从起点长到终点
            val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(p, false) }
            val seg = Path()
            measure.getSegment(0f, measure.length * on, seg, true)
            drawPath(seg, tick, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** 单选：18dp 环，选中是海的环里一粒海 */
@Composable
fun InkRadio(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val on by animateFloatAsState(if (selected) 1f else 0f, InkMotion.spatial(), label = "radio")
    var m = modifier.alpha(if (enabled) 1f else 0.45f)
    if (onClick != null) {
        m = m.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, interactionSource = interaction, indication = null, onClick = onClick)
    }
    Box(m.pressScale(interaction, 0.9f).size(18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(scheme.surfaceContainerLowest).border(1.2.dp, scheme.outline, RoundedCornerShape(50)))
        Box(Modifier.size(18.dp).seaFill(RoundedCornerShape(50), alpha = on))
        Box(Modifier.size(18.dp).padding(2.dp).background(scheme.surfaceContainerLowest.copy(alpha = on), RoundedCornerShape(50)))
        Box(Modifier.size(8.dp * on).seaFill(RoundedCornerShape(50)))
    }
}

/**
 * 分段选择：纸的轨道，一块海在选项之间滑（弹簧）。选中项的字是纸白，其余是石墨。
 */
@Composable
fun InkSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val index = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    var width by remember { mutableIntStateOf(0) }
    val count = options.size.coerceAtLeast(1)
    val slot = if (width == 0) 0f else width.toFloat() / count
    val x by animateFloatAsState(slot * index, InkMotion.spatial(), label = "segment")
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(3.dp)
            .onSizeChanged { width = it.width }
            .selectableGroup(),
    ) {
        if (slot > 0f) {
            val slotDp = with(LocalDensity.current) { slot.toDp() }
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .width(slotDp)
                    .height(30.dp)
                    .seaFill(RoundedCornerShape(7.dp)),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                val isSelected = i == index
                val color by animateColorAsState(
                    if (isSelected) MaterialTheme.sea.onSea else scheme.onSurfaceVariant,
                    InkMotion.effect(),
                    label = "segmentLabel",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .selectable(selected = isSelected, enabled = enabled, role = Role.Tab, indication = LocalIndication.current, interactionSource = remember { MutableInteractionSource() }) { onSelect(i) }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** chip：选中是海底纸白字；未选是纸底 hairline */
@Composable
fun InkChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: ImageVector? = null, monospace: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val on by animateFloatAsState(if (selected) 1f else 0f, InkMotion.effect(), label = "chip")
    val fg by animateColorAsState(if (selected) MaterialTheme.sea.onSea else scheme.onSurface, InkMotion.effect(), label = "chipFg")
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .pressScale(interaction, 0.96f)
            .alpha(if (enabled) 1f else 0.45f)
            .height(32.dp)
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 1f - on), shape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.matchParentSize().seaFill(shape, alpha = on))
        Row(
            Modifier.padding(horizontal = 12.dp).height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) Icon(leadingIcon, null, Modifier.size(15.dp), tint = fg)
            Text(label, style = MaterialTheme.typography.labelMedium, fontFamily = if (monospace) JetbrainsMono else null, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 输入框：纸底、hairline，聚焦时边线变成海的深处 */
@Composable
fun InkTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null, placeholder: String? = null, singleLine: Boolean = false, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, enabled: Boolean = true, isError: Boolean = false, supporting: String? = null, monospace: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None, keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default, textStyle: TextStyle = MaterialTheme.typography.bodyMedium, minHeight: Dp = 0.dp, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val palette = MaterialTheme.sea
    OutlinedTextField(
        value, onValueChange, modifier, enabled, readOnly = false,
        textStyle = textStyle.copy(fontFamily = if (monospace) JetbrainsMono else textStyle.fontFamily),
        label = label?.let { { Text(it) } }, placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leading, trailingIcon = trailing, prefix = null, suffix = null,
        supportingText = supporting?.let { { Text(it) } }, isError = isError,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = palette.seaDeep,
            unfocusedBorderColor = scheme.outlineVariant,
            focusedLabelColor = palette.seaDeep,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = palette.seaDeep,
            focusedContainerColor = scheme.surfaceContainerLowest,
            unfocusedContainerColor = scheme.surfaceContainerLowest,
            errorBorderColor = scheme.error,
            errorCursorColor = scheme.error,
        ),
    )
}

@Composable
fun InkTextArea(state: TextFieldState, modifier: Modifier = Modifier, readOnly: Boolean = false, textStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono), lineLimits: TextFieldLineLimits = TextFieldLineLimits.MultiLine()) =
    BasicTextField(
        state = state,
        modifier = modifier,
        readOnly = readOnly,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        lineLimits = lineLimits,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.sea.seaDeep),
    )

@Composable
fun SettingRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true, onClick: (() -> Unit)? = null, subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier).padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = subtitleColor) }
        }
        trailing?.invoke()
    }
}

@Suppress("unused")
private fun Offset.none() = Unit
