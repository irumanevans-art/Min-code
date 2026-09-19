package dev.min.code.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import androidx.compose.ui.unit.dp
import dev.min.code.ui.components.InkSheet
import dev.min.code.ui.components.SectionTitle
import dev.min.code.ui.richtext.MarkdownBlock

/**
 * 计划面板。内容来自 `get_plan` 控制请求。
 *
 * 入口是会话流底部那条**按需出现**的横幅，不是常驻控件 —— 多数时候没有计划，
 * 常驻一个点不动的入口只是占地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeCodePlanSheet(
    plan: String?,
    onDismiss: () -> Unit,
) {
    InkSheet(
        onDismissRequest = onDismiss,
        // 计划通常很长，半展开只能看到开头两行，不如一开就给足
        sheetState = rememberBottomSheetState(
            SheetValue.Hidden,
            setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            SectionTitle(stringResource(R.string.plan_title), modifier = Modifier.padding(vertical = 6.dp))
            if (plan.isNullOrBlank()) {
                Text(
                    stringResource(R.string.plan_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                MarkdownBlock(content = plan, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
