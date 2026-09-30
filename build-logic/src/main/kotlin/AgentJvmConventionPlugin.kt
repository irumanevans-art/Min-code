import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Min Agent 内核模块（`agent/` 下）的共同约定：纯 Kotlin/JVM、JVM 17、依赖规则、源码检查。
 *
 * 为什么是纯 JVM 而不是 Android library：内核要能在桌面 JVM 上跑评测和恢复测试，
 * 所以它不能认识 Android（见 docs/agent/02-总体架构.md §2）。依赖规则在配置期检查，违反即构建失败。
 */
class AgentJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            group = "dev.min.agent"
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            tasks.withType<KotlinCompile>().configureEach {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }

            afterEvaluate { AgentModuleRules.verify(this) }

            val sourceCheck = tasks.register<AgentSourceCheck>("agentSourceCheck") {
                sources.from(fileTree("src/main/kotlin") { include("**/*.kt") })
                report.set(layout.buildDirectory.file("agentSourceCheck/ok.txt"))
            }
            tasks.named("check") { dependsOn(sourceCheck) }
        }
    }
}
