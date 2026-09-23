package dev.min.code.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * App 私有文件的可分享 URI。authority 以前在三四个地方各写一份字面量，
 * 改一次 applicationId 就得全找一遍 —— 收在这里。
 */
fun Context.fileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
