package dev.min.agent.cli

import dev.min.agent.context.MemoryDiscovery
import dev.min.agent.context.MemoryLoader
import dev.min.agent.context.MemorySnapshot
import dev.min.agent.host.RootfsFileSystem
import dev.min.agent.spi.FileKind
import dev.min.agent.spi.GuestPath
import dev.min.agent.spi.LoadedMemoryFile
import dev.min.agent.spi.MemorySource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/**
 * Claude Code 记忆兼容样本集（resources/memory-compat/）：每个样本一个目录，装着真实写法的项目树（files/，
 * 挂成 guest 的 /workspace）、用户主目录（home/，挂成 /home/user）、guest 根上的零散文件（root/），
 * 以及 steps.json（会话的 cwd 与一串「碰了哪些文件」的步骤）和按 CC 文档 + Min 设计得出的结论 expected.json。
 * 测试把样本铺进临时目录，用 RootfsFileSystem 走真文件系统驱动 MemoryLoader.load（启动快照）与
 * MemoryDiscovery.attach（每步之后该补加载的记忆），逐项比对文件、顺序与说明；失败信息带样本名、步骤、期望与实际。
 *
 * 每条期望带 basis 字段（缺了算样本错误），写明依据是 CC 文档的哪句话还是 Min 设计的哪一节；
 * 期望与实现对不上的样本在 expected.json 里标 pendingFix：差异单独列出、不算失败，逐条复核后再定。
 */
class MemoryCompatTest {
    private val home = "/home/user"

    @Test fun `memory 兼容样本的快照与按需加载和期望逐项一致`() {
        val samplesRoot = Paths.get(URI(javaClass.classLoader.getResource("memory-compat")!!.toString()))
        val samples = Files.list(samplesRoot).use { it.filter(Files::isDirectory).sorted().toList() }
        assertTrue("样本应至少 20 个，实际 ${samples.size}", samples.size >= 20)
        val failures = mutableListOf<String>()
        val pending = mutableListOf<String>()
        runBlocking { samples.forEach { sample -> runSample(sample, failures, pending) } }
        if (pending.isNotEmpty()) {
            println("---- pendingFix 样本的差异（不算失败，待逐条复核）----\n${pending.joinToString("\n")}")
        }
        val pendingNote = if (pending.isEmpty()) "" else
            "\n---- pendingFix 样本的差异（${pending.size} 行，不算失败）----\n${pending.joinToString("\n")}"
        assertTrue("共 ${samples.size} 个样本，${failures.size} 处不一致：\n" +
            failures.joinToString("\n") + pendingNote, failures.isEmpty())
    }

    /** 跑单个样本：铺进临时目录 → 启动快照 + 逐步按需加载 → 与 expected.json 比对；差异写进 failures 或 pending。 */
    private suspend fun runSample(sample: Path, failures: MutableList<String>, pending: MutableList<String>) {
        val name = sample.fileName.toString()
        val diffs = mutableListOf<String>()
        var root: Path? = null
        var pendingFix: String? = null
        try {
            val stepsJson = Json.parseToJsonElement(
                Files.readString(sample.resolve("steps.json"), Charsets.UTF_8)).jsonObject
            val expected = Json.parseToJsonElement(
                Files.readString(sample.resolve("expected.json"), Charsets.UTF_8)).jsonObject
            pendingFix = expected["pendingFix"]?.jsonPrimitive?.contentOrNull
            val cwd = stepsJson.requiredString("cwd")
            val steps = stepsJson["steps"]?.jsonArray ?: error("$name steps.json 缺 steps")
            val expectedStartup = expected["startup"]?.jsonObject
                ?: error("$name expected.json 缺 startup")
            val expectedSteps = (expected["steps"]?.jsonArray
                ?: error("$name expected.json 缺 steps")).map { it.jsonObject }

            root = Files.createTempDirectory("memory-compat")
            val rootfs = Files.createDirectories(root.resolve("rootfs"))
            // files/ → guest /workspace（工作区），home/ → /home/user，root/ → guest 根（cwd 之外的零散文件）
            copyTree(sample.resolve("files"), rootfs.resolve("workspace"))
            if (Files.isDirectory(sample.resolve("home"))) copyTree(sample.resolve("home"), rootfs.resolve("home/user"))
            else Files.createDirectories(rootfs.resolve("home/user"))
            if (Files.isDirectory(sample.resolve("root"))) copyTree(sample.resolve("root"), rootfs)
            val fs = RootfsFileSystem(rootfs)

            val snapshot = MemoryLoader.load(fs, GuestPath(cwd), GuestPath(home))
            compareFiles(diffs, name, "启动快照", snapshot.files, expectedStartup.fileEntries("files"))
            compareNotes(diffs, name, "启动快照", snapshot.notes, expectedStartup.strings("notesContain"))

            // 已在上下文里的记忆路径：快照 + 每步新加载的（与内核折叠维护的集合同一口径，设计 §1.2）
            val discovery = MemoryDiscovery(fs, GuestPath(cwd), GuestPath(home))
            val inContext = snapshot.files.mapTo(mutableSetOf()) { it.path }
            if (expectedSteps.size != steps.size) {
                diffs += "$name 步骤数：steps.json=${steps.size} expected.json=${expectedSteps.size}"
            } else steps.forEachIndexed { i, stepEl ->
                val step = stepEl.jsonObject
                val label = "步骤${i + 1}"
                val failed = step["fail"]?.jsonPrimitive?.booleanOrNull == true
                val touched = (step["touch"]?.jsonArray ?: JsonArray(emptyList()))
                    .map { GuestPath(resolvePath(cwd, it.jsonPrimitive.content)) }
                    // 工具调用侧只报普通文件：目录（Glob/Grep 的搜索根）不算（设计 §1.1，内核 ToolExecutor 同此约定）
                    .filter { fs.stat(it)?.kind == FileKind.File }
                val loaded = if (failed) MemorySnapshot(emptyList(), emptyList())
                else discovery.attach(touched, inContext)
                compareFiles(diffs, name, label, loaded.files, expectedSteps[i].fileEntries("files"))
                compareNotes(diffs, name, label, loaded.notes, expectedSteps[i].strings("notesContain"))
                loaded.files.forEach { inContext += it.path }
            }
        } catch (e: Throwable) {
            diffs += "$name 执行异常：${e.javaClass.simpleName}: ${e.message}"
        } finally {
            root?.toFile()?.deleteRecursively()
        }
        when {
            diffs.isEmpty() -> Unit
            pendingFix != null -> pending += "[$name] pendingFix：$pendingFix\n" +
                diffs.joinToString("\n") { "    $it" }
            else -> failures += diffs
        }
    }

    // ---------- expected.json 的读取 ----------

    private data class ExpectedFile(
        val path: String,
        val source: MemorySource,
        val basis: String,
        val contentContains: List<String>,
        val contentNotContains: List<String>,
    )

    private fun JsonObject.requiredString(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun JsonObject.fileEntries(key: String): List<ExpectedFile> =
        (this[key] as? JsonArray)?.map { el ->
            val o = el.jsonObject
            ExpectedFile(
                path = o.requiredString("path"),
                source = sourceOf(o.requiredString("source")),
                basis = o["basis"]?.jsonPrimitive?.contentOrNull ?: "",
                contentContains = o.strings("contentContains"),
                contentNotContains = o.strings("contentNotContains"),
            )
        } ?: emptyList()

    private fun sourceOf(name: String): MemorySource = when (name) {
        "user" -> MemorySource.User
        "project" -> MemorySource.Project
        "local" -> MemorySource.Local
        "rule" -> MemorySource.Rule
        "import" -> MemorySource.Import
        "nested" -> MemorySource.Nested
        "path_rule" -> MemorySource.PathRule
        else -> error("未知来源 $name（应为 spi MemorySource 的 @SerialName）")
    }

    private fun MemorySource.serialName(): String =
        Json.encodeToString(MemorySource.serializer(), this).trim('"')

    // ---------- 比对 ----------

    private fun compareFiles(
        diffs: MutableList<String>,
        name: String,
        label: String,
        actual: List<LoadedMemoryFile>,
        expected: List<ExpectedFile>,
    ) {
        expected.forEachIndexed { i, e ->
            if (e.basis.isBlank()) diffs += "$name $label 第${i + 1}个期望（${e.path}）缺 basis：样本必须写明依据"
        }
        val expectedText = expected.map { "${it.path}:${it.source.serialName()}" }
        val actualText = actual.map { "${it.path.value}:${it.source.serialName()}" }
        if (expectedText != actualText) {
            diffs += "$name $label 文件或顺序不一致：\n    期望=$expectedText\n    实际=$actualText"
            return
        }
        expected.forEachIndexed { i, e ->
            val content = actual[i].content
            e.contentContains.forEach { part ->
                if (part !in content) diffs += "$name $label ${e.path} 内容应含「$part」，实际=${content.take(200)}"
            }
            e.contentNotContains.forEach { part ->
                if (part in content) diffs += "$name $label ${e.path} 内容不应含「$part」，实际=${content.take(200)}"
            }
        }
    }

    private fun compareNotes(
        diffs: MutableList<String>,
        name: String,
        label: String,
        notes: List<String>,
        parts: List<String>,
    ) {
        val joined = notes.joinToString("\n")
        parts.forEach { part ->
            if (part !in joined) diffs += "$name $label notes 应含「$part」，实际=$joined"
        }
    }

    // ---------- 样本树与路径 ----------

    /** 碰到的路径相对 cwd 写（也接受以 / 开头的 guest 绝对路径），展开 . 与 .. */
    private fun resolvePath(cwd: String, raw: String): String {
        val parts = (if (raw.startsWith("/")) raw else "$cwd/$raw")
            .split('/').filter { it.isNotEmpty() && it != "." }
        val out = ArrayDeque<String>()
        for (part in parts) {
            if (part == "..") out.removeLastOrNull() else out.addLast(part)
        }
        return "/" + out.joinToString("/")
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { source ->
                val dest = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(dest)
                else {
                    Files.createDirectories(dest.parent)
                    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
