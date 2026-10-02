plugins {
    id("min.agent.jvm")
    application
}

dependencies {
    implementation(project(":agent:kernel"))
    implementation(project(":agent:host"))
    // 组合根要直接拿到各模块的实现：工具、编解码器、存储、记忆加载（KernelDeps 里的 MemoryLimits）
    implementation(project(":agent:tools"))
    implementation(project(":agent:llm"))
    implementation(project(":agent:store"))
    implementation(project(":agent:context"))
    implementation(project(":agent:ext"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

application {
    mainClass.set("dev.min.agent.cli.MainKt")
}
