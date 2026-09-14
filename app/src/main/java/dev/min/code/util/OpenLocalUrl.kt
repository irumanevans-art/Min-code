package dev.min.code.util

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri

private const val TAG = "OpenLocalUrl"

/**
 * 用系统浏览器打开本地 / 局域网 URL。
 * 不嵌 WebView：用户要的是「服务起来 → 一眼能看」的闭环，不是应用内浏览器。
 */
fun Context.openLocalUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, trimmed.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "openLocalUrl failed: $trimmed", it)
        Toast.makeText(this, "无法打开 $trimmed", Toast.LENGTH_SHORT).show()
        false
    }
}
