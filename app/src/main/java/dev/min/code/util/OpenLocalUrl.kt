package dev.min.code.util

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri

private const val TAG = "OpenLocalUrl"

/**
 * 打开 URL。
 *
 * - loopback → 调用方应填应用内预览位 [dev.min.code.ui.preview.LocalPreviewPane]，本函数不处理。
 * - 其它 → 系统浏览器（次要入口）。
 */
fun Context.openExternalUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, trimmed.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "openExternalUrl failed: $trimmed", it)
        Toast.makeText(this, "无法打开 $trimmed", Toast.LENGTH_SHORT).show()
        false
    }
}
