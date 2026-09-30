import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * agent/ 源码的两条机械检查：
 * - 禁止 `runCatching`：它会把协程的 CancellationException 当普通异常吞掉，取消就传不上去了
 *   （Min 现有代码里这种写法很多，内核不再重犯）。改用 `dev.min.agent.spi.catchingNonCancellation {}`。
 *   确实包的是非挂起代码且有理由的，同一行末尾写 `// allow-runCatching: 理由`。
 * - 超过 800 行的文件只告警不失败：该拆了（docs/agent/07-工程规范.md 第 21 条）。
 */
@CacheableTask
abstract class AgentSourceCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun run() {
        val violations = mutableListOf<String>()
        sources.files.sortedBy { it.path }.forEach { file ->
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {
                logger.warn("agentSourceCheck: ${file.path} 有 ${lines.size} 行，超过 $MAX_LINES 行，该拆了")
            }
            lines.forEachIndexed { index, line ->
                if (RUN_CATCHING.containsMatchIn(line) && ALLOW_MARK !in line) {
                    violations += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "agent/ 里禁止 runCatching（会吞掉协程取消），改用 catchingNonCancellation {}；" +
                    "确有理由的在行尾加 `// $ALLOW_MARK 理由`：\n  " + violations.joinToString("\n  ")
            )
        }
        report.get().asFile.writeText("ok\n")
    }

    private companion object {
        const val MAX_LINES = 800
        const val ALLOW_MARK = "allow-runCatching:"
        val RUN_CATCHING = Regex("""\brunCatching\s*[{(]""")
    }
}
