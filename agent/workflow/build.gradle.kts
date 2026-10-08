plugins { id("min.android.library") }

afterEvaluate { AgentModuleRules.verify(this) }
val agentSourceCheck by tasks.registering(AgentSourceCheck::class) {
    sources.from(fileTree("src/main/kotlin") { include("**/*.kt") })
    report.set(layout.buildDirectory.file("agentSourceCheck/ok.txt"))
}
tasks.named("check") { dependsOn(agentSourceCheck) }

android {
    namespace = "dev.min.agent.workflow"
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
}

dependencies {
    implementation(project(":agent:spi"))
    implementation(project(":agent:ext"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("io.github.dokar3:quickjs-kt-android:1.0.15")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 桌面 JVM 无法加载 Android 的 ELF；测试必须运行同版本的桌面原生引擎。
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.15"))
    }
}
