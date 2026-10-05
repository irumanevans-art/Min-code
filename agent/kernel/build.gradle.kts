plugins {
    id("min.agent.jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agent:spi"))
    implementation(project(":agent:store"))
    implementation(project(":agent:llm"))
    implementation(project(":agent:policy"))
    implementation(project(":agent:context"))
    implementation(project(":agent:tools"))
    implementation(project(":agent:ext"))
    implementation(project(":agent:mcp"))
    testImplementation(project(":agent:testkit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
