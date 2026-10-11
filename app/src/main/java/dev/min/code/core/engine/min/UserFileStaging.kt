package dev.min.code.core.engine.min

import java.io.File
import java.security.MessageDigest

/*
 * SendUserFile 在手机上的落盘位置。通知（AndroidUserFileSink）与对话流里的文件卡片（UserFileCard）读同一份：
 * 卡片只认识那次工具调用（会话 id + 调用 id + 入参里的文件），不认识宿主的缓存目录，所以位置必须是两边都能算出来的。
 * 纯 JVM，没有 Android 依赖：单测直接在临时目录里跑。
 */

/** 卡片上的一行。[file] 为 null = 本机缓存里已经没有了（被系统清掉、被更新的文件挤掉），行还在，只是打不开 */
data class SentFileRow(val name: String, val sizeBytes: Long?, val file: File?)

/**
 * 一次调用的文件存在哪个目录：会话 id + 调用 id 的摘要。不直接拿调用 id 当目录名——
 * 它来自供应商，有的中转站按序号起（`call_0`），两个会话会撞上；而且原样当路径用也不安全。
 */
fun userFileStageKey(sessionId: String, callId: String): String =
    MessageDigest.getInstance("SHA-256").digest("$sessionId/$callId".toByteArray(Charsets.UTF_8))
        .take(STAGE_KEY_BYTES).joinToString("") { "%02x".format(it) }

/**
 * 把一份文件写进 `[root]/[key]/[index]/[name]`。同名文件在一批里靠 [index] 分开。
 * 写完把这次调用的目录记成最新，再只留最近 [keep] 次调用的目录：以前按通知槽位整个目录轮换，
 * 现在卡片要能往回翻，按「次」淘汰最旧的，缓存占用照样有上限。
 */
fun stageUserFile(root: File, key: String, index: Int, name: String, bytes: ByteArray, keep: Int = USER_FILE_CALLS_KEPT): File {
    val callDir = File(root, key)
    val dir = File(callDir, index.toString()).apply {
        deleteRecursively()
        mkdirs()
    }
    val target = File(dir, safeName(name)).apply { writeBytes(bytes) }
    callDir.setLastModified(System.currentTimeMillis())
    pruneCalls(root, keepKey = key, keep = keep)
    return target
}

/** 卡片要的行：按入参里的文件名顺序，第 i 个找 `[key]/i/` 下的同名文件 */
fun sentFileRows(root: File, key: String, names: List<String>): List<SentFileRow> = names.mapIndexed { index, name ->
    val file = File(File(File(root, key), index.toString()), safeName(name)).takeIf { it.isFile }
    SentFileRow(name, file?.length(), file)
}

/** 只留最近 [keep] 次调用（按目录修改时间）。刚写的那次（[keepKey]）无论如何不删：它的通知马上就要指着它 */
private fun pruneCalls(root: File, keepKey: String, keep: Int) {
    val calls = root.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.lastModified() }
    calls.filter { it.name != keepKey }.drop((keep - 1).coerceAtLeast(0)).forEach { it.deleteRecursively() }
}

/**
 * 文件名只当一段路径用：工具给的是工作区路径的最后一段，按理不会是 `.` / `..` 或带分隔符，
 * 但这里是把东西写进 App 自己的缓存，不能靠「按理」——不合格的一律换成 `file`
 */
private fun safeName(name: String): String =
    name.takeUnless { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it } ?: "file"

/** 缓存里最多留几次调用的文件：以前是 32 个通知槽位，按「次」留得多一点，卡片往回翻也大多打得开 */
const val USER_FILE_CALLS_KEPT = 64

/** 摘要取前 16 字节（32 个十六进制字符）：同一台手机上的调用数远到不了撞号的量级 */
private const val STAGE_KEY_BYTES = 16
