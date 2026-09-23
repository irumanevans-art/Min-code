package dev.min.code.core.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.min.code.MainActivity

/*
 * 通知的点击落点。
 *
 * 这是 core 层唯一直接引用 [MainActivity] 的地方，留着是有意的：通知只能指向一个具体的 Activity。
 * 换成 `getLaunchIntentForPackage` 虽然能去掉这个引用，但会带上 MAIN / LAUNCHER 和启动器的任务语义，
 * 点通知回前台时是清栈还是新开一个任务都会跟着变 —— 为了一个包依赖方向去赌这个不值得。
 *
 * 以前前台服务和会话监督器各写了一份 Claude 那条，一字不差；改一边忘一边，
 * 同一类通知就会点进两个不同的地方。
 */

/** Claude Code 通知（保活常驻、权限请求、一轮结束）：清栈回根，落在 Claude Code 页 */
internal fun openClaudeCodePageIntent(context: Context): PendingIntent =
    pageIntent(context, ClaudeCodeForegroundService.NOTIFICATION_ID) { putExtra(EXTRA_OPEN_CLAUDE_CODE, true) }

/** Codex 通知：清栈回根，再顶上 Codex 页 */
internal fun openCodexPageIntent(context: Context): PendingIntent =
    pageIntent(context, CODEX_DONE_NOTIFICATION_ID) { putExtra(EXTRA_OPEN_CODEX, true) }

private inline fun pageIntent(context: Context, requestCode: Int, extras: Intent.() -> Unit): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        extras()
    }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
