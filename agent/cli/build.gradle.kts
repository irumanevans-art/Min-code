plugins {
    id("min.agent.jvm")
    application
}

dependencies {
    implementation(libs.quickjs.jvm)
    implementation(project(":agent:kernel"))
    implementation(project(":agent:host"))
    // 组合根要直接拿到各模块的实现：工具、编解码器、存储、记忆加载（KernelDeps 里的 MemoryLimits）
    implementation(project(":agent:tools"))
    implementation(project(":agent:llm"))
    implementation(project(":agent:store"))
    // 遥测（默认关）：CLI 进程退出时把会话日志的新事件导出（OTLP / 本机 JSONL）
    implementation(project(":agent:otel"))
    implementation(project(":agent:context"))
    implementation(project(":agent:ext"))
    testImplementation(project(":agent:testkit"))
    testImplementation(project(":agent:mcp"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.min.agent.cli.MainKt")
}

// 同一份引擎源码分别链接 JVM / Android 原生库，CLI 不依赖 Android AAR。
kotlin.sourceSets.named("main") { kotlin.srcDir("../workflow/src/main/kotlin") }
