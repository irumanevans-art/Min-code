package dev.min.code.core.rootfs

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.min.code.R
import org.koin.core.context.GlobalContext
import java.io.File
import java.io.FileNotFoundException

/**
 * 把 Rootfs 挂进系统「文件」App 和所有 SAF 选择器。
 *
 * ## 为什么值得做
 *
 * 手机上编辑代码难受，但难受的不是没有编辑器，而是 agent 的工作区**只有这个 App 自己进得去**。
 * 注册成 DocumentsProvider 之后，系统文件管理器侧栏里会多出一个入口，任何第三方编辑器、
 * 任何「选择文件」对话框都能直接打开 `/workspace` 里的文件——不用再从文件页导出、改完再导入。
 *
 * 代价几乎为零：Rootfs 本来就在 App 私有目录里，是货真价实的 [File]，
 * 不像 SAF 的 `content://` 需要再架一层。
 *
 * ## 两个入口
 *
 * - **工作区**（[ROOT_WORKSPACE]）：`/workspace`，agent 日常干活的地方，绝大多数人只需要这个。
 * - **Ubuntu**（[ROOT_ROOTFS]）：整个容器。修 `~/.claude` 里的配置、翻日志时才用得上。
 *
 * ## 为什么排除 /dev /proc /sys
 *
 * 那几个是 proot 在**运行时**才挂上去的内核文件系统，宿主这边对应位置只是空目录。
 * 不排除的话用户点进去看到的是一片空白，然后合理地怀疑是这个 App 坏了。
 *
 * ## documentId 的形状
 *
 * `<rootId>:<相对路径>`，相对的是那个 root 的宿主目录。根自己是 `<rootId>:`。
 * 每次解析都要过 [resolve] 那道 `..` 逃逸检查——documentId 是外部传进来的字符串，
 * 系统文件 App 不会构造恶意路径，但别的 App 会。
 */
/**
 * 把 documentId 里那段相对路径解成 [base] 下的真实文件，并确认它**确实落在 [base] 里面**。
 *
 * 逃逸检查在这里比在文件页里要紧得多：documentId 是别的 App 递进来的字符串，
 * 一个 `../../../databases/x.db` 不拦就是把私有目录拱手让人。用 canonicalPath 比而不是
 * 字符串前缀比——rootfs 里符号链接到处都是（`/bin` → `/usr/bin` 这种），
 * 光比字符串会把一个指向外面的链接当成自家的。
 *
 * @return 文件；逃出 [base] 或根本不存在时返回 null，由调用方翻成 FileNotFoundException
 */
internal fun resolveWithinRoot(base: File, relative: String): File? {
    val file = if (relative.isEmpty()) base else File(base, relative)
    val basePath = runCatching { base.canonicalPath }.getOrNull() ?: return null
    val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return null
    if (filePath != basePath && !filePath.startsWith("$basePath${File.separator}")) return null
    return file.takeIf { it.exists() }
}

class WorkspaceDocumentsProvider : DocumentsProvider() {

    /**
     * Koin 在 `Application.onCreate` 里启动，而 ContentProvider 的 `onCreate` 跑在**那之前**。
     * 所以这里一个字都不能取，等第一次真正的查询到来时再拿。
     */
    private val repository: WorkspaceRepository?
        get() = runCatching { GlobalContext.get().get<WorkspaceRepository>() }.getOrNull()

    override fun onCreate(): Boolean = true

    // -----------------------------------------------------------------------
    // 根
    // -----------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val repo = repository ?: return cursor
        // rootfs 还没装的时候不摆入口：摆了点进去也是空的，不如不出现在侧栏里
        if (!repo.linuxDir().isDirectory) return cursor

        val context = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_WORKSPACE)
            add(Root.COLUMN_DOCUMENT_ID, "$ROOT_WORKSPACE:")
            add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context.getString(R.string.documents_root_workspace))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ROOTFS)
            add(Root.COLUMN_DOCUMENT_ID, "$ROOT_ROOTFS:")
            add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, context.getString(R.string.documents_root_rootfs))
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
        return cursor
    }

    // -----------------------------------------------------------------------
    // 查
    // -----------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.addFile(documentId, resolve(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = resolve(parentDocumentId)
        val (rootId, relative) = split(parentDocumentId)
        parent.listFiles().orEmpty()
            .filterNot { rootId == ROOT_ROOTFS && relative.isEmpty() && it.name in KERNEL_DIRS }
            // 目录在前、同类按名字，和文件页一致
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { child ->
                val childId = joinId(rootId, if (relative.isEmpty()) child.name else "$relative/${child.name}")
                cursor.addFile(childId, child)
            }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentRoot, parentPath) = split(parentDocumentId)
        val (childRoot, childPath) = split(documentId)
        if (parentRoot != childRoot) return false
        return parentPath.isEmpty() || childPath.startsWith("$parentPath/")
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(
        resolve(documentId),
        ParcelFileDescriptor.parseMode(mode),
    )

    // -----------------------------------------------------------------------
    // 改
    // -----------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolve(parentDocumentId)
        val (rootId, relative) = split(parentDocumentId)
        // 重名不覆盖：系统文件 App 传进来的名字是用户敲的，撞上了要另起一个，
        // 而不是把已有的那份悄悄清空
        var candidate = File(parent, displayName)
        var n = 1
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (candidate.exists()) {
            candidate = File(parent, "$stem ($n)$ext")
            n++
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            candidate.mkdirs()
        } else {
            candidate.createNewFile()
        }
        if (!created) throw FileNotFoundException("failed to create $displayName in $parentDocumentId")
        return joinId(rootId, if (relative.isEmpty()) candidate.name else "$relative/${candidate.name}")
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (!deleted) throw FileNotFoundException("failed to delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolve(documentId)
        val target = File(file.parentFile, displayName)
        if (target.exists()) throw FileNotFoundException("$displayName already exists")
        if (!file.renameTo(target)) throw FileNotFoundException("failed to rename $documentId")
        val (rootId, relative) = split(documentId)
        val parentPath = relative.substringBeforeLast('/', "")
        return joinId(rootId, if (parentPath.isEmpty()) displayName else "$parentPath/$displayName")
    }

    // -----------------------------------------------------------------------
    // documentId <-> File
    // -----------------------------------------------------------------------

    /** `<rootId>:<相对路径>` 拆成两段。没有冒号的一律当非法 */
    private fun split(documentId: String): Pair<String, String> {
        val index = documentId.indexOf(':')
        if (index < 0) throw FileNotFoundException("bad document id: $documentId")
        return documentId.substring(0, index) to documentId.substring(index + 1).trim('/')
    }

    /** 解成宿主机上的真实文件。逃逸与存在性检查见 [resolveWithinRoot] */
    private fun resolve(documentId: String): File {
        val (rootId, relative) = split(documentId)
        val repo = repository ?: throw FileNotFoundException("workspace not ready")
        val base = when (rootId) {
            ROOT_WORKSPACE -> repo.filesDir()
            ROOT_ROOTFS -> repo.linuxDir()
            else -> throw FileNotFoundException("unknown root: $rootId")
        }
        return resolveWithinRoot(base, relative)
            ?: throw FileNotFoundException("bad document id: $documentId")
    }

    private fun joinId(rootId: String, relative: String) = "$rootId:$relative"

    private fun MatrixCursor.addFile(documentId: String, file: File) {
        val (rootId, relative) = split(documentId)
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            // 根那一行显示成挂载点的名字，比一个空字符串强
            add(
                Document.COLUMN_DISPLAY_NAME,
                if (relative.isEmpty()) {
                    if (rootId == ROOT_WORKSPACE) "workspace" else "ubuntu"
                } else {
                    file.name
                },
            )
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            // 没有后缀或认不出来的一律当纯文本：rootfs 里绝大多数是源码和配置，
            // 报 application/octet-stream 会让编辑器拒绝打开它们
            ?: if (ext.isEmpty() || ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }

    companion object {
        private const val ROOT_WORKSPACE = "workspace"
        private const val ROOT_ROOTFS = "rootfs"

        /** proot 运行时才挂上去的内核文件系统，宿主这边是空目录 */
        private val KERNEL_DIRS = setOf("dev", "proc", "sys")

        /** MimeTypeMap 认不出、但确实该按文本打开的后缀 */
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "gradle", "toml", "lock", "env", "gitignore",
            "md", "rs", "go", "yml", "yaml", "sh", "conf", "ini", "log",
        )

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
