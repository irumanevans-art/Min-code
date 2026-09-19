package dev.min.code.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import androidx.compose.ui.unit.dp
import dev.min.code.core.claudecode.ClaudeCodeEvent
import dev.min.code.core.claudecode.ClaudeCodeQuestion
import dev.min.code.core.claudecode.parseAskUserQuestions
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkCheckbox
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkRadio
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.components.PaperTone
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea

/**
 * `AskUserQuestion` 的应答面板。
 *
 * ## 为什么它不能复用权限 sheet
 *
 * AskUserQuestion 和普通权限确认走的是**同一个** `can_use_tool` 帧 —— 官方对它的设计就是
 * 复用 canUseTool 回调。但语义完全不同：权限确认要的是"允许/拒绝"，提问要的是
 * "在 2–4 个选项里挑一个（或几个）"，而且选择必须经 `updatedInput.answers` 回传。
 * 之前统一按权限 sheet 渲染，用户看到的是一对allow/deny 按钮，**根本没有选项可点**，
 * 模型那边收到的就是一句"没收到选择"。
 *
 * ## 三个必须做对的点
 *
 * 1. **一次可能有 1–4 问**，每问独立单选/多选，全部答完才能提交。
 * 2. **永远要有「其他」**：官方对这个工具的约定是用户总能自己填文本，
 *    只给固定选项会把用户逼进一个都不合适的死角。
 * 3. **`preview` 要渲染**：带 preview 的选项（代码片段 / 版式草图）正是靠它比较的，
 *    只显示 label 等于把这个工具最有用的形态废掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClaudeCodeQuestionSheet(
    pending: ClaudeCodeEvent.PermissionRequest,
    onAnswer: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val questions = remember(pending.input) { parseAskUserQuestions(pending.input) }
    // 选中的**预置**选项。单选模式下这个集合最多一个元素。
    val selected = remember(pending.requestId) { mutableStateMapOf<String, Set<String>>() }
    // 「其他」有没有被勾上。和 [selected] 完全独立 —— 它就是多出来的一个选项，
    // 不是"另一种输入模式"，所以勾选状态和预置项互不影响。
    val customChecked = remember(pending.requestId) { mutableStateMapOf<String, Boolean>() }
    // 「其他」里填的文本。**任何选择动作都不许动它** —— 之前点一下预置项就把它清空，
    // 用户辛苦打的字凭空消失。
    val customText = remember(pending.requestId) { mutableStateMapOf<String, String>() }

    fun answerOf(q: ClaudeCodeQuestion): String = composeAnswer(
        question = q,
        selected = selected[q.question].orEmpty(),
        customChecked = customChecked[q.question] == true,
        customText = customText[q.question].orEmpty(),
    )

    // 勾了「其他」却没填字 = 还没答完。不能让它带着一个空串提交上去。
    val complete = questions.isNotEmpty() && questions.all { answerOf(it).isNotBlank() }

    // 不允许滑动关掉：CLI 正阻塞等这次应答，误滑一下就等于替用户答了"什么都不选"。
    // 出口只有两个明确的按钮。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )

    InkSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dismissible = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Seal()
                Text(stringResource(R.string.question_title), style = MaterialTheme.typography.titleMedium)
            }

            if (questions.isEmpty()) {
                // 解析不出问题时不能把用户卡在一个空面板里 —— 允许直接放行，
                // 让 CLI 拿空答案继续，至少不会把会话锁死
                Notice(
                    text = stringResource(R.string.question_unreadable),
                    tone = NoticeTone.Error,
                )
            }

            questions.forEachIndexed { index, q ->
                if (index > 0) InkDivider()
                QuestionBlock(
                    question = q,
                    selected = selected[q.question].orEmpty(),
                    customChecked = customChecked[q.question] == true,
                    customText = customText[q.question].orEmpty(),
                    onToggle = { label ->
                        val current = selected[q.question].orEmpty()
                        if (q.multiSelect) {
                            // 多选：只翻转这一项，既不碰别的预置项，也不碰「其他」
                            selected[q.question] = if (label in current) current - label else current + label
                        } else {
                            // 单选：选中它就取代之前的选择，并让「其他」让位 ——
                            // 但**只改勾选状态，不清空已经打好的文本**
                            selected[q.question] = setOf(label)
                            customChecked[q.question] = false
                        }
                    },
                    onToggleCustom = {
                        val next = customChecked[q.question] != true
                        customChecked[q.question] = next
                        // 单选模式下勾「其他」等于放弃预置项；反之亦然
                        if (next && !q.multiSelect) selected[q.question] = emptySet()
                    },
                    onCustomChange = { text ->
                        customText[q.question] = text
                        // 打字即勾选、清空即取消：省掉"填了字却忘了勾"这种必然发生的失误。
                        // 注意这里同样不动 selected（多选时两者可以并存）
                        val next = text.isNotBlank()
                        customChecked[q.question] = next
                        if (next && !q.multiSelect) selected[q.question] = emptySet()
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InkButton(
                    onClick = onDismiss,
                    tone = InkButtonTone.Paper,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.question_skip)) }
                InkButton(
                    onClick = { onAnswer(questions.associate { it.question to answerOf(it) }) },
                    enabled = complete,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.question_submit)) }
            }
        }
    }
}

@Composable
private fun QuestionBlock(
    question: ClaudeCodeQuestion,
    selected: Set<String>,
    customChecked: Boolean,
    customText: String,
    onToggle: (String) -> Unit,
    onToggleCustom: () -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        question.header?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.sea.seaDeep,
            )
        }
        Text(question.question, style = MaterialTheme.typography.bodyMedium)
        if (question.multiSelect) {
            Text(
                stringResource(R.string.question_multi),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        question.options.forEach { option ->
            OptionRow(
                multiSelect = question.multiSelect,
                checked = option.label in selected,
                label = option.label,
                onClick = { onToggle(option.label) },
            ) {
                option.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // preview 是等宽排版的比较素材（代码片段 / 版式草图），必须保留换行与空格，
                // 所以横向滚动而不是折行 —— 折行会把对齐关系毁掉，预览就失去意义
                option.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    PaperCard(
                        modifier = Modifier.padding(top = 4.dp),
                        tone = PaperTone.Mid,
                        padding = PaddingValues(8.dp),
                    ) {
                        Text(
                            preview,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        // 「其他」就是**多出来的一个选项**，只不过内容由用户填 —— 不是"另一种输入模式"。
        // 所以它有自己的勾选框，和上面那些完全平级：多选时可以和预置项同时选中，
        // 勾选/取消也绝不会清空已经打好的字。
        OptionRow(
            multiSelect = question.multiSelect,
            checked = customChecked,
            label = stringResource(R.string.question_other),
            onClick = onToggleCustom,
        ) {
            InkTextField(
                value = customText,
                onValueChange = onCustomChange,
                placeholder = stringResource(R.string.question_other_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                isError = customChecked && customText.isBlank(),
            )
        }
    }
}

/**
 * 一行选项：左边勾选控件，右边标题 + 任意附加内容（说明 / preview / 输入框）。
 *
 * 预置项和「其他」共用它，两者的交互才不会长得像两种东西。
 */
@Composable
private fun OptionRow(
    multiSelect: Boolean,
    checked: Boolean,
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 控件对齐标题的第一行：bodyMedium 行高 22，控件 18
        Box(Modifier.padding(top = 2.dp, end = 12.dp)) {
            if (multiSelect) {
                InkCheckbox(checked = checked, onCheckedChange = { onClick() })
            } else {
                InkRadio(selected = checked, onClick = onClick)
            }
        }
        Column(Modifier.weight(1f)) {
            // 只有标题可点。整行可点会把输入框也变成"点一下就切换勾选"，
            // 于是根本没法把光标放进去打字。
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = onClick),
            )
            content()
        }
    }
}

/**
 * 把一问的选择拼成回传给 CLI 的答案字符串。
 *
 * 单选取唯一那项；多选按**选项原始顺序**连接，勾了「其他」就把用户填的文本接在最后。
 * 按原始顺序而不是勾选顺序：同样的选择每次都应该产生同样的字符串，
 * 否则重放/对拍时会看到无意义的差异。
 */
internal fun composeAnswer(
    question: ClaudeCodeQuestion,
    selected: Set<String>,
    customChecked: Boolean,
    customText: String,
): String {
    val typed = customText.trim()
    val parts = buildList {
        question.options.forEach { if (it.label in selected) add(it.label) }
        if (customChecked && typed.isNotEmpty()) add(typed)
    }
    // 勾了「其他」却没填字：这一问就算没答完，返回空串让提交按钮保持禁用
    if (customChecked && typed.isEmpty()) return ""
    return if (question.multiSelect) parts.joinToString(", ") else parts.firstOrNull().orEmpty()
}
