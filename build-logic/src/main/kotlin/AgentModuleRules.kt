import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/**
 * `agent/` 模块之间允许的依赖（docs/agent/02-总体架构.md §2 的表）。改这张表 = 改架构，要先改文档。
 *
 * 只允许表里列出的**直接**依赖；测试配置额外允许依赖 :agent:testkit。
 * 另外任何 agent 模块都不许依赖 Android / AndroidX（androidx.sqlite 的纯接口除外）或 dev.min.code——
 * 内核一旦认识 Android，就没法在桌面 JVM 上跑评测了。
 */
object AgentModuleRules {
    private const val PROTOCOL = ":agent:protocol"
    private const val SPI = ":agent:spi"
    private const val MCP = ":agent:mcp"
    private const val LSP = ":agent:lsp"
    private const val STORE = ":agent:store"
    private const val LLM = ":agent:llm"
    private const val POLICY = ":agent:policy"
    private const val CONTEXT = ":agent:context"
    private const val TOOLS = ":agent:tools"
    private const val EXT = ":agent:ext"
    private const val HOST = ":agent:host"
    private const val KERNEL = ":agent:kernel"
    private const val TESTKIT = ":agent:testkit"
    private const val CLI = ":agent:cli"
    private const val WORKFLOW = ":agent:workflow"

    private val BELOW_KERNEL = setOf(PROTOCOL, SPI, STORE, LLM, POLICY, CONTEXT, TOOLS, EXT, MCP, LSP)

    val allowed: Map<String, Set<String>> = mapOf(
        PROTOCOL to emptySet(),
        SPI to setOf(PROTOCOL),
        MCP to setOf(PROTOCOL, SPI),
        LSP to setOf(PROTOCOL, SPI),
        STORE to setOf(PROTOCOL, SPI),
        LLM to setOf(PROTOCOL, SPI),
        POLICY to setOf(PROTOCOL, SPI),
        CONTEXT to setOf(PROTOCOL, SPI, LLM),
        TOOLS to setOf(PROTOCOL, SPI, POLICY),
        EXT to setOf(PROTOCOL, SPI),
        // 宿主侧端口的纯 JVM 实现（rootfs 文件系统等），桌面 CLI 与 Android 宿主共用；内核不依赖它
        HOST to setOf(PROTOCOL, SPI),
        KERNEL to BELOW_KERNEL,
        TESTKIT to BELOW_KERNEL + KERNEL,
        CLI to BELOW_KERNEL + KERNEL + TESTKIT + HOST,
        // 唯一原生 Android 宿主适配：其他 agent 模块不得反向依赖它。
        WORKFLOW to setOf(SPI, EXT),
    )

    private val FORBIDDEN_GROUP_PREFIXES = listOf("com.android", "com.google.android", "androidx", "dev.min.code")
    private val ALLOWED_GROUPS = setOf("androidx.sqlite")

    fun verify(project: Project) {
        val allowedHere = allowed[project.path]
            ?: throw GradleException("${project.path} 没有登记在 AgentModuleRules 里：新模块先在 docs/agent/02-总体架构.md §2 和这张表里登记依赖规则")
        val problems = mutableListOf<String>()
        project.configurations.forEach { conf ->
            // AGP 的 lint/设备测试工具运行在 Gradle 中，不是宿主模块的发布依赖。
            if (project.path == WORKFLOW && (conf.name == "androidLintTool" || conf.name.startsWith("unified-test-platform-"))) return@forEach
            val isTest = conf.name.startsWith("test")
            conf.dependencies.forEach { dep ->
                when (dep) {
                    is ProjectDependency -> {
                        val ownAndroidTest = project.path == WORKFLOW && dep.path == WORKFLOW && conf.name.contains("Test") && conf.name.endsWith("Classpath")
                        val ok = dep.path in allowedHere || (isTest && dep.path == TESTKIT) || ownAndroidTest
                        if (!ok) problems += "${conf.name} -> ${dep.path}"
                    }
                    is ExternalModuleDependency -> {
                        val group = dep.group.orEmpty()
                        val forbidden = group !in ALLOWED_GROUPS &&
                            FORBIDDEN_GROUP_PREFIXES.any { group == it || group.startsWith("$it.") }
                        if (forbidden) problems += "${conf.name} -> $group:${dep.name}"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${project.path} 违反 agent 模块依赖规则（见 docs/agent/02-总体架构.md §2）：\n  " +
                    problems.joinToString("\n  ")
            )
        }
    }
}
