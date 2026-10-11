package dev.min.code.core.engine.min

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/*
 * 系统 PdfRenderer 的渲染器（[PdfRasterizer] 的真实实现）。
 * PdfRenderer 只认文件描述符，所以先把字节写进缓存目录的临时文件，关闭时删掉；缓存目录随时可被系统清，不占用户空间。
 */
internal class AndroidPdfRasterizer(private val cacheDir: File) : PdfRasterizer {
    override fun open(bytes: ByteArray): PdfPageSource? {
        val file = try {
            File.createTempFile("pdf-pages-", ".pdf", cacheDir)
        } catch (e: IOException) {
            return null
        }
        try {
            file.writeBytes(bytes)
        } catch (e: IOException) {
            file.delete()
            return null
        }
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            file.delete()
            return null
        }
        return try {
            AndroidPdfSource(PdfRenderer(pfd), pfd, file)
        } catch (e: IOException) {
            // 文件结构坏了：系统抛 IOException
            pfd.close()
            file.delete()
            null
        } catch (e: SecurityException) {
            // 需要密码：系统抛 SecurityException
            pfd.close()
            file.delete()
            null
        }
    }
}

private class AndroidPdfSource(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val file: File,
) : PdfPageSource {
    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(index: Int): PdfPageSize {
        // PdfRenderer 同一时间只能开一页：每次开关，不并存
        val page = renderer.openPage(index)
        try {
            return PdfPageSize(page.width, page.height)
        } finally {
            page.close()
        }
    }

    override fun renderPng(index: Int, widthPx: Int, heightPx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            // 先涂白：PDF 页面背景是透明的，直接编码成 PNG 时文字落在透明底上，看起来发灰、模型读不清
            bitmap.eraseColor(Color.WHITE)
            val page = renderer.openPage(index)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
        file.delete()
    }
}
