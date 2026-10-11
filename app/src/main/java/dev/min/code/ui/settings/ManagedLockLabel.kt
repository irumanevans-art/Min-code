package dev.min.code.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.min.code.R

/**
 * 托管层锁住的设置行的副题：原来的说明后面接一句「已由管理员锁定」。不另起一种控件、不加图标——
 * 行照旧在、值照旧显示（是管理员定的那个值），只是开关置灰、点不动，说明里写清为什么
 */
@Composable
internal fun lockedSubtitle(base: String, locked: Boolean): String =
    if (locked) stringResource(R.string.managed_locked_suffix, base) else base
