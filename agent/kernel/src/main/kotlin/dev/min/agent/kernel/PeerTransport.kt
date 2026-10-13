package dev.min.agent.kernel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFileAttributeView

/**
 * 跨会话邮箱的收件口径（照 Claude Code 的 `crossSessionInbound`，Min 只有宿主级一个开关）。
 * [Auto] = 按收发两边的权限模式分类：同一类直接送，两类之间的先由用户批准。
 */
enum class PeerInboundPolicy { Auto, Accept, Hold, Refuse }

/** 登记表里的一行：一个在本机运行的根会话。[busy] 是登记时刻的状态（忙 = 有回合在跑） */
@Serializable
data class PeerEntry(
    val session: String,
    val name: String?,
    val cwd: String,
    val busy: Boolean,
    /** 登记它的进程；由传输层写入，调用方不填 */
    val pid: Long = 0L,
)

/**
 * 一封在途消息的信封（桌面多进程走文件时才用；Hub 内直送不经过它）。
 * [from] 由发件进程按自己的登记写入；收件方查不到发件人的登记就丢掉，所以信封里的话不作为身份依据。
 */
@Serializable
data class PeerEnvelope(
    val messageId: String,
    val kind: Kind = Kind.Message,
    val from: String,
    val to: String,
    val text: String,
    /** 发件会话当时是不是「绕过权限提示」类（由发件 Hub 读它自己的状态填，不是模型填的） */
    val senderBypass: Boolean,
    val sentAtMs: Long,
) {
    @Serializable
    enum class Kind {
        /** 一封要送进收件方对话的消息 */
        Message,

        /** 对发件人的结论（拒收、超时未批准）：只进发件人的收件箱，不算对方的话 */
        Outcome,

        /** 请收件方（被盯的会话）空闲或退出时给发件人发一次通知（notify_when_idle）；没有正文 */
        Watch,

        /** 被盯的会话发回的空闲 / 退出通知：正文是通知文字，收件方（订阅的一方）按自己的口径决定交不交给模型 */
        IdleNotice,
    }
}

/** 收件目录里取到的一封（[fileId] 确认后删除；去重靠 messageId，见 [PeerMailbox]） */
data class TakenPeer(val fileId: String, val envelope: PeerEnvelope)

/**
 * 同一台机器上会话之间的传输：登记表（谁在线、叫什么、忙不忙）+ 每个会话一个收件目录。
 * Hub 内的会话之间不走它（直接写对方的日志），它只服务于「别的进程里的会话」。
 */
interface PeerTransport {
    /** 登记或更新一个本进程的会话（名字、目录、忙不忙） */
    fun publish(entry: PeerEntry)

    /** 注销：会话关闭或进程退出时调用 */
    fun withdraw(session: String)

    /** 在线会话的登记。登记它的进程已经不在了的，顺手清掉登记与收件目录 */
    fun entries(): List<PeerEntry>

    /** 把信封交进对方的收件目录。对方没登记或已不在线时返回 false，什么都不写 */
    fun deliver(to: String, envelope: PeerEnvelope): Boolean

    /** 取出发给 [session] 的信封（只读，不删）；收件方处理完后用 [ack] 删 */
    fun take(session: String): List<TakenPeer>

    /** 确认处理完，删掉这一封 */
    fun ack(session: String, fileId: String)
}

/**
 * 桌面的文件实现，布局：`<root>/sessions/<会话 id>.json`（登记）与 `<root>/inbox/<会话 id>/<messageId>.json`（收件）。
 *
 * - 写文件一律先写 `.tmp` 再原子改名：读的一方要么看见完整的一封，要么什么都看不见，不会读到半截。
 * - 每个会话一个收件目录，只有它自己的进程取、删；别的进程只往里面放新文件，所以不用锁，不丢（取了不确认就不删）、不重（去重在收件方）。
 * - 目录 0700、文件 0600（POSIX）；Windows 上把 ACL 收成只有当前用户，不留给 Everyone / Users。
 * - 会话 id 只许字母数字下划线连字符：它要拼进路径，允许 `..` 就能写到用户目录之外。
 *
 * 真实文件 IO，本应在 `:agent:host`；没搬是因为 [PeerTransport] 与它用的 [PeerEntry] / [PeerEnvelope] / [TakenPeer] 都在内核里，
 * host 不能依赖 kernel。要搬：先把这几个类型与接口搬进 spi（kernel 的 PeerMail / SessionHub 与测试改 import），
 * 再把本类与 FilePeerTransportTest 搬进 host，最后改 cli 的 `DesktopApp` 与两个 cli 测试的 import。
 */
class FilePeerTransport(
    private val root: Path,
    private val pid: Long = ProcessHandle.current().pid(),
    private val alive: (Long) -> Boolean = ::processAlive,
) : PeerTransport {
    private val sessions: Path = root.resolve("sessions")
    private val inboxes: Path = root.resolve("inbox")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        ensureDir(root)
        ensureDir(sessions)
        ensureDir(inboxes)
    }

    override fun publish(entry: PeerEntry) {
        val id = checkId(entry.session)
        ensureDir(inboxOf(id))
        writeAtomically(sessions.resolve("$id.json"), json.encodeToString(PeerEntry.serializer(), entry.copy(pid = pid)))
    }

    override fun withdraw(session: String) {
        val id = checkId(session)
        Files.deleteIfExists(sessions.resolve("$id.json"))
        deleteTree(inboxOf(id))
    }

    override fun entries(): List<PeerEntry> {
        val out = mutableListOf<PeerEntry>()
        for (f in listFiles(sessions)) {
            if (!f.fileName.toString().endsWith(".json")) continue
            val entry = readOrNull { json.decodeFromString(PeerEntry.serializer(), Files.readString(f)) } ?: continue
            // 登记它的进程已经不在了：登记与收件目录一起清掉。留着只会让别人把话投进没人取的目录
            if (!alive(entry.pid)) {
                withdraw(entry.session)
                continue
            }
            out += entry
        }
        return out
    }

    override fun deliver(to: String, envelope: PeerEnvelope): Boolean {
        val id = checkId(to)
        checkId(envelope.messageId)
        // 对方要登记着、还在线，且收件目录存在：不替没登记的会话建目录
        entries().firstOrNull { it.session == id } ?: return false
        if (!Files.isDirectory(inboxOf(id), LinkOption.NOFOLLOW_LINKS)) return false
        writeAtomically(inboxOf(id).resolve("${envelope.messageId}.json"), json.encodeToString(PeerEnvelope.serializer(), envelope))
        return true
    }

    override fun take(session: String): List<TakenPeer> {
        val id = checkId(session)
        val dir = inboxOf(id)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return listFiles(dir).filter { it.fileName.toString().endsWith(".json") }.mapNotNull { f ->
            // 写坏了的一封（不该出现：写入是原子的）直接删掉，免得每次都卡在它上面
            val env = readOrNull { json.decodeFromString(PeerEnvelope.serializer(), Files.readString(f)) }
            if (env == null) {
                Files.deleteIfExists(f)
                null
            } else TakenPeer(f.fileName.toString().removeSuffix(".json"), env)
        }
    }

    override fun ack(session: String, fileId: String) {
        Files.deleteIfExists(inboxOf(checkId(session)).resolve("${checkId(fileId)}.json"))
    }

    private fun inboxOf(id: String): Path = inboxes.resolve(id)

    private fun checkId(id: String): String {
        require(ID.matches(id)) { "会话 id 只能是字母、数字、下划线或连字符：$id" }
        return id
    }

    private fun ensureDir(dir: Path) {
        Files.createDirectories(dir)
        restrictToOwner(dir)
    }

    private fun writeAtomically(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(tmp, text)
        restrictToOwner(tmp)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(target)
    }

    private fun listFiles(dir: Path): List<Path> =
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) emptyList()
        else Files.list(dir).use { s -> s.filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList() }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9_-]{1,96}")
    }
}

/** 只许本用户读写：POSIX 直接设位；没有 POSIX 视图（Windows）时把 ACL 收成只有当前用户一条 */
internal fun restrictToOwner(path: Path) {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
        val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        val perms = if (isDir) {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        Files.setPosixFilePermissions(path, perms)
        return
    }
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
    val owner = view.owner
    view.acl = listOf(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(AclEntryPermission.values().toSet())
            .build(),
    )
}

private fun processAlive(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

private inline fun <T> readOrNull(block: () -> T): T? = try {
    block()
} catch (e: IOException) {
    null
} catch (e: kotlinx.serialization.SerializationException) {
    null
}
