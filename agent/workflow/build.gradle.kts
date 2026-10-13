plugins { id("min.android.library") }

afterEvaluate { AgentModuleRules.verify(this) }
registerAgentSourceCheck()

android {
    namespace = "dev.min.agent.workflow"
}

dependencies {
    implementation(project(":agent:spi"))
    implementation(project(":agent:ext"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.quickjs.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 桌面 JVM 无法加载 Android 的 ELF；测试必须运行同版本的桌面原生引擎。
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}
