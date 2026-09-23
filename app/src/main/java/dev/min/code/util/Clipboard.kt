package dev.min.code.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import dev.min.code.R

private const val TAG = "Util"

fun Context.writeClipboardText(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    runCatching {
        clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
    }.onFailure {
        Log.e(TAG, "writeClipboardText failed", it)
        Toast.makeText(this, getString(R.string.common_copy_failed), Toast.LENGTH_SHORT).show()
    }
}
