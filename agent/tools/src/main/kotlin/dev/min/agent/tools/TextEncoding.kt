package dev.min.agent.tools

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本文件的编码判断，Read / Write / Edit / NotebookEdit 共用这一处（对齐 Claude Code 2.1.296 / 2.1.299 / 2.1.301）。
 *
 * 为什么要严格：旧实现用宽松的 `decodeToString()`，GBK / Shift-JIS / Windows-1252 文件里每个非 ASCII 字符都变成 U+FFFD，
 * 写回去就是静默毁文件。所以改写类工具只认三种能无损来回的形式（[writable]）：UTF-8、UTF-8 带 BOM、UTF-16LE 带 BOM；
 * 认不出的一律拒绝并让模型用 `iconv` 显式转码，而不是替它悄悄转。
 * 只读的 Read 另外认 UTF-16BE 带 BOM，其余非法 UTF-8 照旧宽松显示。
 */
internal enum class TextEncoding(private val bom: ByteArray, private val charset: Charset, val writable: Boolean) {
    Utf8(byteArrayOf(), Charsets.UTF_8, writable = true),
    Utf8Bom(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), Charsets.UTF_8, writable = true),
    Utf16Le(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), Charsets.UTF_16LE, writable = true),
    Utf16Be(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), Charsets.UTF_16BE, writable = false),
    ;

    /** 文本编码成这种形式的字节（带 BOM 的补上 BOM；文本自己开头已有 U+FEFF 时不重复） */
    fun encode(text: String): ByteArray {
        if (bom.isEmpty()) return text.toByteArray(charset)
        return bom + text.removePrefix(FEFF).toByteArray(charset)
    }

    /** 严格解码 [bytes]（已知它以本编码的 BOM 开头或无 BOM 的 UTF-8）；有非法序列返回 null */
    private fun strict(bytes: ByteArray): String? = strictDecode(charset, bytes, bom.size)

    /** 宽松解码：非法序列变成 U+FFFD，不抛 */
    private fun lenient(bytes: ByteArray): String = String(bytes, bom.size, bytes.size - bom.size, charset)

    private fun matchesBom(bytes: ByteArray): Boolean =
        bom.isNotEmpty() && bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it] }

    companion object {
        private val FEFF = Char(0xFEFF).toString()
        private const val BINARY_SNIFF_BYTES = 8 * 1024

        /** 凭 BOM 判编码；没有 BOM 就是（无 BOM 的）UTF-8。FF FE 00 00 是 UTF-32LE 的 BOM，不能当 UTF-16LE 用 */
        fun detect(bytes: ByteArray): TextEncoding {
            if (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 0.toByte() && Utf16Le.matchesBom(bytes)) return Utf8
            return entries.firstOrNull { it.matchesBom(bytes) } ?: Utf8
        }

        /** 严格解码 + 编码（改写类工具用）。不是合法的 [writable] 编码时返回 null——调用方应拒绝，而不是宽松解码后写回 */
        fun decodeWritable(bytes: ByteArray): DecodedText? {
            val encoding = detect(bytes)
            if (!encoding.writable) return null
            return encoding.strict(bytes)?.let { DecodedText(encoding, it) }
        }

        /** 给人 / 模型看的文本（Read、diff 基线）：认 BOM、剥掉 BOM，非法序列宽松替换 */
        fun decodeLenient(bytes: ByteArray): String = detect(bytes).lenient(bytes)

        /**
         * 按文本读会不会读出乱码：前 8KB 含 NUL 即判二进制，但以 UTF-16 BOM 开头的除外——UTF-16 里 ASCII 字符的高字节本来就是 NUL。
         */
        fun looksBinary(bytes: ByteArray): Boolean {
            if (detect(bytes) == Utf16Le || detect(bytes) == Utf16Be) return false
            val head = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until head) if (bytes[i] == 0.toByte()) return true
            return false
        }

        /**
         * 只看扩展名就能确定不是文本的格式，补 NUL 嗅探的漏：xlsm / parquet / epub / heic 这类前 8KB 恰好没有 NUL 的压缩或容器格式
         * 会被当文本读出一堆乱码（CC 2.1.301）。图片、PDF、ipynb 在 Read 里有专门分支，不在表里。
         */
        fun isBinaryExtension(ext: String): Boolean = ext.lowercase() in BINARY_EXTENSIONS

        private val BINARY_EXTENSIONS = setOf(
            "xlsm", "xlsx", "xls", "docx", "doc", "pptx", "ppt", "odt", "ods", "odp",
            "parquet", "epub", "heic", "heif", "avif",
            "zip", "jar", "war", "aar", "apk", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "tar",
            "class", "so", "dll", "dylib", "exe",
            "mp3", "mp4", "mov", "mkv", "avi", "wav", "flac",
        )

        /** 改写类工具遇到不认识的原编码时给模型的话：说清为什么拒绝、怎么显式转码 */
        fun refusal(path: Any, verb: String): String =
            "$path 不是 UTF-8 编码的文本（也不是带 BOM 的 UTF-16LE），可能是 GBK、Shift-JIS、Windows-1252 或 UTF-16BE。" +
                "为了不静默转码毁掉原文件，没有$verb。确实要转码的话，先用 Bash 的 iconv 显式转换" +
                "（例如 iconv -f GBK -t UTF-8 原文件 > 新文件），再对转换后的文件操作。"

        /** 严格按 [charset] 解码 [bytes] 从 [offset] 起的部分；有非法序列（含奇数字节的 UTF-16）返回 null，而不是替换成 U+FFFD */
        fun strictDecode(charset: Charset, bytes: ByteArray, offset: Int = 0): String? = try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}

/** 严格解出的文本（已剥掉 BOM）与它原来的编码 */
internal class DecodedText(val encoding: TextEncoding, val text: String)
