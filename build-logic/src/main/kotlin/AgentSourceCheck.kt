import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * agent/ 源码的两条机械检查：
 * - 禁止 `runCatching`：它会把协程的 CancellationException 当普通异常吞掉，取消就传不上去了
 *   （Min 现有代码里这种写法很多，内核不再重犯）。改用 `dev.min.agent.spi.catchingNonCancellation {}`。
 *   确实包的是非挂起代码且有理由的，同一行末尾写 `// allow-runCatching: 理由`。
 * - 文件行数只许缩小（docs/agent/07-工程规范.md 第 21 条）：新文件不得超过 [MAX_LINES] 行；
 *   已经超限的文件登记在 `agent/file-size-baseline.txt`，不得再长，缩小后要把上限同步调低。
 *   基线里的文件拆到 [MAX_LINES] 以内就把那一行删掉。
 */
@CacheableTask
abstract class AgentSourceCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** 超限文件的行数上限，每行 `<相对仓库根的路径> <上限>`，`#` 开头为注释 */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** 本模块相对仓库根的目录（如 `agent/kernel`），用来拼基线里的路径，免得消息和键里带机器绝对路径 */
    @get:Input
    abstract val moduleDir: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val limits = readBaseline()
        val violations = mutableListOf<String>()
        val sizeProblems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            val lines = file.readLines()
            val key = moduleDir.get() + "/src/main/kotlin/" + file.invariantSeparatorsPath.substringAfter("/src/main/kotlin/")
            seen += key
            val limit = limits[key]
            when {
                limit == null && lines.size > MAX_LINES ->
                    sizeProblems += "$key 有 ${lines.size} 行，超过 $MAX_LINES 行：拆开它（先补特征测试，沿真实边界拆）"
                limit != null && lines.size > limit ->
                    sizeProblems += "$key 有 ${lines.size} 行，超过基线 $limit：这个文件已经超限，不得再长，新逻辑放进旁边的小文件"
                limit != null && lines.size <= MAX_LINES ->
                    sizeProblems += "$key 已降到 ${lines.size} 行，不再超限：从 file-size-baseline.txt 删掉这一行"
                limit != null && lines.size < limit ->
                    sizeProblems += "$key 缩小到 ${lines.size} 行：把 file-size-baseline.txt 里的上限从 $limit 改成 ${lines.size}"
            }
            lines.forEachIndexed { index, line ->
                if (RUN_CATCHING.containsMatchIn(line) && ALLOW_MARK !in line) {
                    violations += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }
        // 基线里指向本模块、但文件已不存在（改名 / 删除）的条目：留着只会误导
        val prefix = moduleDir.get() + "/"
        limits.keys.filter { it.startsWith(prefix) && it !in seen }.forEach {
            sizeProblems += "$it 在基线里但文件已不存在：从 file-size-baseline.txt 删掉这一行"
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "agent/ 里禁止 runCatching（会吞掉协程取消），改用 catchingNonCancellation {}；" +
                    "确有理由的在行尾加 `// $ALLOW_MARK 理由`：\n  " + violations.joinToString("\n  ")
            )
        }
        if (sizeProblems.isNotEmpty()) {
            throw GradleException("agent/ 文件行数检查（规则见 docs/agent/07-工程规范.md 第 21 条）：\n  " + sizeProblems.joinToString("\n  "))
        }
        report.get().asFile.writeText("ok\n")
    }

    private fun readBaseline(): Map<String, Int> {
        val file = baseline.orNull?.asFile?.takeIf { it.exists() } ?: return emptyMap()
        return file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.associate { line ->
            val parts = line.split(Regex("\\s+"))
            val limit = parts.getOrNull(1)?.toIntOrNull()
                ?: throw GradleException("file-size-baseline.txt 格式应为 `<路径> <行数上限>`，读到：$line")
            parts[0] to limit
        }
    }

    private companion object {
        const val MAX_LINES = 800
        const val ALLOW_MARK = "allow-runCatching:"
        val RUN_CATCHING = Regex("""\brunCatching\s*[{(]""")
    }
}

/** 给 agent 模块注册 `agentSourceCheck` 并挂到 `check` 上；Kotlin/JVM 约定插件和 Android 库形态的 workflow 模块共用 */
fun Project.registerAgentSourceCheck() {
    val sourceCheck = tasks.register<AgentSourceCheck>("agentSourceCheck") {
        sources.from(fileTree("src/main/kotlin") { include("**/*.kt") })
        baseline.set(rootProject.layout.projectDirectory.file("agent/file-size-baseline.txt"))
        moduleDir.set(projectDir.relativeTo(rootProject.projectDir).invariantSeparatorsPath)
        report.set(layout.buildDirectory.file("agentSourceCheck/ok.txt"))
    }
    tasks.named("check") { dependsOn(sourceCheck) }
}
