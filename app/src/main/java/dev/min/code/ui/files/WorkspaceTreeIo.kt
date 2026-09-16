package dev.min.code.ui.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import me.rerere.workspace.WorkspaceArchive
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "WorkspaceTreeIo"

/** 一棵待导入的树里的一个节点。[relativePath] 相对树根、用 `/` 分隔 */
data class ImportNode(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * 「一棵能读的目录树」。抽成接口是为了让 VM 完全不碰 SAF 类型 ——
 * 那边只知道「按顺序拿节点、按节点开流」。
 */
interface ImportTree {
    val rootName: String

    /** 前序：目录一定排在它的孩子前面 */
    fun walk(): Sequence<ImportNode>

    fun open(node: ImportNode): InputStream?
}

/** 「一个能写的目标目录」。导出到系统文件夹那条路用它 */
interface ExportSink {
    fun mkdir(relativePath: String): Boolean
    fun create(relativePath: String, mime: String): OutputStream?
}

/** 一条待导入的流 + 它该叫什么。批量选文件那条路用它 */
class ContentImportSource(
    val name: String,
    val open: () -> InputStream?,
)

/**
 * 用 [DocumentsContract] 直接读一棵 SAF 目录树。
 *
 * 不用 `androidx.documentfile`：它不在版本目录里，而且 `DocumentFile.listFiles()` 是每个孩子一次
 * IPC；这里一次 `query` 就把一整层的所有列拿回来。三千个文件的目录差的是分钟级。
 *
 * 防御全都不是理论问题：provider 是第三方进程，`DISPLAY_NAME` 完全可以是 `../x`，
 * documentId 也可以自己指回自己。
 */
class SafTreeReader(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ImportTree {

    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * 走过的路径 → documentId。SAF 的 documentId 是不透明的，拼不出来，
     * 而 [walk] 本来就查到了 —— 记下来，[open] 才不至于每个文件重走一遍整棵树（平方级）。
     */
    private val idByPath = HashMap<String, String>()

    override val rootName: String =
        queryName(rootDocumentId) ?: treeUri.lastPathSegment?.substringAfterLast('/').orEmpty()
            .ifBlank { "imported" }

    override fun walk(): Sequence<ImportNode> = sequence {
        val visited = HashSet<String>()
        yieldAll(walkChildren(rootDocumentId, prefix = "", depth = 0, visited = visited))
    }

    private fun walkChildren(
        documentId: String,
        prefix: String,
        depth: Int,
        visited: MutableSet<String>,
    ): Sequence<ImportNode> = sequence {
        if (depth > MAX_DEPTH) return@sequence
        if (!visited.add(documentId)) return@sequence
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )
        }.getOrNull() ?: return@sequence

        // 一层先读完再往下走：游标不能跨递归活着
        val rows = mutableListOf<Triple<String, String, Pair<Boolean, Long>>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0) ?: continue
                val rawName = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                // provider 给的名字可以是任何东西，包括能跳出目录的形状
                val safe = WorkspaceArchive.sanitizeEntryName(rawName) ?: continue
                if (safe.contains('/')) continue
                rows += Triple(childId, safe, (mime == DocumentsContract.Document.MIME_TYPE_DIR) to size)
            }
        }

        for ((childId, name, meta) in rows.sortedBy { it.second }) {
            val (isDir, size) = meta
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            idByPath[path] = childId
            yield(ImportNode(path, isDir, if (isDir) 0L else size))
            if (isDir) yieldAll(walkChildren(childId, path, depth + 1, visited))
        }
    }

    override fun open(node: ImportNode): InputStream? {
        val documentId = resolveDocumentId(node.relativePath) ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return runCatching { resolver.openInputStream(uri) }.getOrNull()
    }

    /** [walk] 记下来的那张表优先；没走到过才逐层查（慢，但 documentId 拼不出来） */
    private fun resolveDocumentId(relativePath: String): String? {
        idByPath[relativePath]?.let { return it }
        var current = rootDocumentId
        for (segment in relativePath.split('/')) {
            current = childId(current, segment) ?: return null
        }
        return current.also { idByPath[relativePath] = it }
    }

    private fun childId(parentId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) return@use c.getString(0)
                }
                null
            }
        }.getOrNull()
    }

    private fun queryName(documentId: String): String? = runCatching {
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private companion object {
        /** 再深就不是"用户想导入的一个文件夹"了，多半是碰上了环 */
        const val MAX_DEPTH = 32
    }
}

/**
 * 往一棵 SAF 目录树里写。目录按需创建并缓存 documentId —— 每个文件都重新查一遍父目录
 * 会把「复制到文件夹」拖成平方级。
 */
class SafTreeWriter(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ExportSink {

    private val dirIds = HashMap<String, String>().apply {
        put("", DocumentsContract.getTreeDocumentId(treeUri))
    }

    override fun mkdir(relativePath: String): Boolean = ensureDir(relativePath) != null

    override fun create(relativePath: String, mime: String): OutputStream? {
        val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = relativePath.substringAfterLast('/')
        val parentId = ensureDir(parent) ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val uri = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mime, name)
        }.getOrNull() ?: return null
        return runCatching { resolver.openOutputStream(uri) }.getOrNull()
    }

    private fun ensureDir(relativePath: String): String? {
        if (relativePath.isEmpty()) return dirIds[""]
        dirIds[relativePath]?.let { return it }
        val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = relativePath.substringAfterLast('/')
        val parentId = ensureDir(parent) ?: return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = runCatching {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        }.getOrNull()
        val id = created?.let { DocumentsContract.getDocumentId(it) }
        if (id == null) Log.w(TAG, "could not create directory $relativePath")
        if (id != null) dirIds[relativePath] = id
        return id
    }
}

/**
 * 把一个 `content://` 变成「名字 + 可重开的流」。
 *
 * 名字查询放进 [ContentImportSource.open] 之外先做一次，但**调用方要在 IO 协程里**
 * 构造它 —— `OpenableColumns` 查询是一次 IPC，原来那份代码是在主线程上跑的。
 */
fun Uri.toImportSource(resolver: ContentResolver): ContentImportSource {
    val name = runCatching {
        resolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()
        ?: lastPathSegment?.substringAfterLast('/')
        ?: "imported_file"
    return ContentImportSource(name) { runCatching { resolver.openInputStream(this) }.getOrNull() }
}
