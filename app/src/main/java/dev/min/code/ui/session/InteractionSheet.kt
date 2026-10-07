package dev.min.code.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.min.code.R
import dev.min.code.core.claudecode.subagentTypeName
import dev.min.code.core.engine.ApprovalOption
import dev.min.code.core.engine.InteractionAnswer
import dev.min.code.core.engine.PendingInteraction
import dev.min.code.core.engine.awaitedToolName
import dev.min.code.core.session.PathScope
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkDivider
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.Seal
import dev.min.code.ui.theme.JetbrainsMono
import dev.min.code.ui.theme.sea
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01

/**
 * 会话停下来等人的三种事（审批、提问、计划审批）的**唯一一处分发**（设计 §3.6）：三张卡片仍是原来的组件，
 * 这里只按 [PendingInteraction] 的种类选哪一张、把来源（哪个子 agent 发起的）与答复接好。
 * 页面和各引擎都不再自己判「这是提问还是审批」。
 *
 * @param onAnswer 答复：审批 / 计划审批是 [InteractionAnswer.Choose]（按钮的 id），提问是 [InteractionAnswer.Answer]
 *   或「跳过」的 [InteractionAnswer.Decline]
 */
@Composable
internal fun InteractionSheet(
    pending: PendingInteraction,
    onAnswer: (InteractionAnswer) -> Unit,
) {
    val forkName = stringResource(R.string.agent_type_fork)
    val origin = permissionOriginOf(pending.origin) { subagentTypeName(it, forkName) }
    // 提问走的是同一个 can_use_tool 帧，但要的是「在选项里挑」而不是「允许/拒绝」，
    // 答案还得回传给模型 —— 两种面板不能混用
    if (pending is PendingInteraction.Question) {
        ClaudeCodeQuestionSheet(
            pending = pending,
            origin = origin,
            onAnswer = { answers -> onAnswer(InteractionAnswer.Answer(answers)) },
            // 跳过 = 允许工具带着空答案跑完。不能回 deny：那会被模型当成
            // "用户拒绝了这次提问"，它多半会停下来问为什么，而不是继续干活
            onDismiss = { onAnswer(InteractionAnswer.Decline) },
        )
    } else {
        PermissionSheet(
            pending = pending,
            origin = origin,
            onAnswer = { optionId -> onAnswer(InteractionAnswer.Choose(optionId)) },
        )
    }
}

/**
 * 权限确认。用底部 sheet 而不是 AlertDialog：审批是**高频一键动作**，
 * 模态对话框把整屏盖掉、按钮还在屏幕中间，单手够不着；底部 sheet 拇指可达，
 * 上面正在读的内容也还看得见。
 *
 * 「始终允许 …」来自 `can_use_tool` 请求附带的 `permission_suggestions`：
 * 把用户选中的那条原样回传，CLI 就会写规则，之后同类调用不再询问。
 * 没有它的话，默认 Manual 模式下每一条 Bash、每一次 Edit 都要重新点一遍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionSheet(
    /** 审批卡或计划审批卡（提问卡走 [ClaudeCodeQuestionSheet]） */
    pending: PendingInteraction,
    origin: PermissionOrigin,
    /** 点了哪个按钮，传它的 [ApprovalOption.id] */
    onAnswer: (optionId: String) -> Unit,
) {
    val approval = pending as? PendingInteraction.Approval
    // **不允许滑动关掉**。这个 sheet 是在会话流上方弹出来的，而用户很可能正在往回滑看历史 ——
    // 弹出的那一瞬间手指还在移动，一下就把它滑走了，而滑走等于 onDismissRequest，
    // 之前那会直接回一个 deny。ExitPlanMode 被误拒的后果尤其重：模型会以为你否了这份计划，
    // 转头去改方案。出口只留两个明确的按钮。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    InkSheet(
        onDismissRequest = { /* 只能按按钮，见上 */ },
        sheetState = sheetState,
        dismissible = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Seal()
                Text(
                    pending.awaitedToolName,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = JetbrainsMono,
                )
            }
            PermissionOriginLines(origin, denyHint = true)
            approval?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            approval?.blockedPath?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.session_permission_blocked_path, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.sea.vermilion,
                )
            }
            approval?.reason?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.session_permission_reason, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 要动的东西跑到 /workspace 之外了。挂了整机存储之后这是唯一能把
            // 「改容器里的 /etc」和「改你相册里的照片」分开说的地方
            val escaped = approval?.paths.orEmpty().map { it.path }
            val onDevice = approval?.paths.orEmpty().any { it.scope == PathScope.Device }
            if (escaped.isNotEmpty()) {
                Text(
                    text = if (onDevice) {
                        stringResource(R.string.session_permission_device_path, escaped.first())
                    } else {
                        stringResource(R.string.session_permission_outside_workspace, escaped.first())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = if (onDevice) {
                        MaterialTheme.sea.vermilion
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 按工具类型渲染入参：编辑类给 diff，Bash 给高亮命令，其余给美化 JSON。
            // 原来这里是 `input.toString()` 截断成一行流水账 —— 用户点「允许」时
            // 其实看不到自己批准了什么，而那正是权限确认唯一的意义。
            ToolInputPreview(
                name = pending.awaitedToolName,
                // 计划审批卡只带计划正文：ExitPlanMode 的预览也只读 `plan`
                input = approval?.input ?: planInputOf(pending),
                labels = rememberTranscriptLabels(),
            )

            InkDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 拒绝是判定（朱砂），允许是主动作（墨 / 金）
                InkButton(
                    onClick = { onAnswer(ApprovalOption.DENY_AND_STOP) },
                    modifier = Modifier.weight(1f),
                    tone = InkButtonTone.Vermilion,
                ) { Text(stringResource(R.string.session_permission_deny)) }
                InkButton(
                    onClick = { onAnswer(ApprovalOption.ALLOW) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.session_permission_allow_once)) }
            }
            // 「始终允许」放在主按钮下方：它改的是持久规则，不该和一次性批准同等醒目。
            //
            // 碰到设备上的真实文件时**一条都不给**：那条规则会把往后所有同类写入都放过去，
            // 而这一档改坏了没有 undo（rootfs 里改砸了重装就回来，相册里的照片不会）。
            // 每一次都值得单独看一眼——这正是把整机交出去之后仅剩的那道闸。
            if (!onDevice) {
                val always = approval?.options ?: (pending as? PendingInteraction.PlanReview)?.extraOptions
                always.orEmpty().filter { it.kind == ApprovalOption.Kind.AllowAlways }.forEach { suggestion ->
                    InkButton(
                        onClick = { onAnswer(suggestion.id) },
                        modifier = Modifier.fillMaxWidth(),
                        tone = InkButtonTone.Paper,
                        icon = HugeIcons.Tick01,
                    ) {
                        Text(suggestion.label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** 计划审批卡 -> 预览组件要的入参形状（只有 `plan` 一项） */
private fun planInputOf(pending: PendingInteraction): JsonObject = buildJsonObject {
    (pending as? PendingInteraction.PlanReview)?.let { put("plan", it.plan) }
}
