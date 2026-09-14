package dev.min.code.ui.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import dev.min.code.ui.theme.sea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Doc01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileArchive
import me.rerere.hugeicons.stroke.FileAudio
import me.rerere.hugeicons.stroke.FileCode
import me.rerere.hugeicons.stroke.FileImage
import me.rerere.hugeicons.stroke.FileScript
import me.rerere.hugeicons.stroke.FileUnknown
import me.rerere.hugeicons.stroke.FileVideo
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Pdf01
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 工作区文件的粗略分类, 用于决定点击文件时的行为:
 * - TEXT: 应用内文本编辑/预览
 * - IMAGE: 应用内可缩放图片预览
 * - OTHER: 交给系统应用 (视频/音频/文档等) 打开
 */
enum class WorkspaceFileType { TEXT, IMAGE, OTHER }

/**
 * 列表上的种类。打开行为走 [WorkspaceFileType]，这里只管「一眼能分出 md 和 docx」。
 * 不是 Linux 的限制：沙箱里的文件就是普通文件，图标完全由这边画。
 */
enum class WorkspaceFileKind {
    FOLDER, MARKDOWN, CODE, TEXT, IMAGE, AUDIO, VIDEO, ARCHIVE, PDF, DOCUMENT, UNKNOWN,
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "json5", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
    "properties", "env", "csv", "tsv", "log", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "gitignore",
    "dockerfile", "lua", "php", "pl", "r", "dart", "vue", "svelte", "gql", "graphql", "proto",
    "diff", "patch", "srt", "vtt",
)

private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdx")
private val CODE_EXTENSIONS = setOf(
    "json", "json5", "xml", "yaml", "yml", "toml", "html", "htm", "css", "scss", "sass", "less",
    "js", "mjs", "cjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c", "h",
    "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "zsh", "gradle", "sql", "lua", "php", "pl",
    "r", "dart", "vue", "svelte", "gql", "graphql", "proto", "diff", "patch",
)
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v")
private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")
private val PDF_EXTENSIONS = setOf("pdf")
private val DOCUMENT_EXTENSIONS = setOf(
    "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "pages", "numbers",
)

fun WorkspaceFileEntry.detectFileType(): WorkspaceFileType {
    if (isDirectory) return WorkspaceFileType.OTHER
    val ext = extension()
    return when {
        ext.isEmpty() -> WorkspaceFileType.OTHER
        ext in IMAGE_EXTENSIONS -> WorkspaceFileType.IMAGE
        ext in TEXT_EXTENSIONS -> WorkspaceFileType.TEXT
        else -> WorkspaceFileType.OTHER
    }
}

fun WorkspaceFileEntry.detectFileKind(): WorkspaceFileKind {
    if (isDirectory) return WorkspaceFileKind.FOLDER
    val ext = extension()
    return when {
        ext in MARKDOWN_EXTENSIONS -> WorkspaceFileKind.MARKDOWN
        ext in CODE_EXTENSIONS -> WorkspaceFileKind.CODE
        ext in IMAGE_EXTENSIONS -> WorkspaceFileKind.IMAGE
        ext in AUDIO_EXTENSIONS -> WorkspaceFileKind.AUDIO
        ext in VIDEO_EXTENSIONS -> WorkspaceFileKind.VIDEO
        ext in ARCHIVE_EXTENSIONS -> WorkspaceFileKind.ARCHIVE
        ext in PDF_EXTENSIONS -> WorkspaceFileKind.PDF
        ext in DOCUMENT_EXTENSIONS -> WorkspaceFileKind.DOCUMENT
        ext in TEXT_EXTENSIONS -> WorkspaceFileKind.TEXT
        else -> WorkspaceFileKind.UNKNOWN
    }
}

internal fun WorkspaceFileEntry.extension(): String =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase()

internal fun WorkspaceFileKind.icon(): ImageVector = when (this) {
    WorkspaceFileKind.FOLDER -> HugeIcons.Folder01
    WorkspaceFileKind.MARKDOWN -> HugeIcons.FileScript
    WorkspaceFileKind.CODE -> HugeIcons.FileCode
    WorkspaceFileKind.TEXT -> HugeIcons.File02
    WorkspaceFileKind.IMAGE -> HugeIcons.FileImage
    WorkspaceFileKind.AUDIO -> HugeIcons.FileAudio
    WorkspaceFileKind.VIDEO -> HugeIcons.FileVideo
    WorkspaceFileKind.ARCHIVE -> HugeIcons.FileArchive
    WorkspaceFileKind.PDF -> HugeIcons.Pdf01
    WorkspaceFileKind.DOCUMENT -> HugeIcons.Doc01
    WorkspaceFileKind.UNKNOWN -> HugeIcons.FileUnknown
}

@Composable
internal fun WorkspaceFileKind.tint(): Color {
    val palette = MaterialTheme.sea
    val graphite = MaterialTheme.colorScheme.onSurfaceVariant
    return when (this) {
        WorkspaceFileKind.FOLDER -> palette.seaDeep
        WorkspaceFileKind.MARKDOWN, WorkspaceFileKind.CODE -> palette.seaDeep
        WorkspaceFileKind.IMAGE -> palette.sea
        else -> graphite
    }
}
